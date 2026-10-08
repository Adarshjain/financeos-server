package com.financeos.domain.notification.bill;

import com.financeos.core.time.AppTime;
import com.financeos.domain.account.Account;
import com.financeos.domain.account.AccountRepository;
import com.financeos.domain.account.AccountType;
import com.financeos.domain.notification.NotificationKind;
import com.financeos.domain.notification.NotificationOutcome;
import com.financeos.domain.notification.NotificationProducer;
import com.financeos.domain.notification.NotificationSettingsCodec;
import com.financeos.domain.notification.NotificationSettingsService;
import com.financeos.domain.notification.UserNotificationSettings;
import com.financeos.domain.notification.UserNotificationSettingsRepository;
import com.financeos.domain.notification.push.WebPushSender;
import com.financeos.domain.statement.Statement;
import com.financeos.domain.statement.StatementCreditCardDetails;
import com.financeos.domain.statement.StatementRepository;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Decides, per card bill, which notification (if any) is due now, sends it and records the
 * marker. Two entry points: {@link #evaluateUser} from the hourly tick and
 * {@link #onStatementCreated} right after a statement commits.
 *
 * <p>Both run in their own transaction ({@code REQUIRES_NEW}) so they work from the after-commit
 * listener and the scheduler alike; the caller sets {@code UserContext} so the Hibernate user
 * filter scopes every read. Markers advance even when nothing can be sent (push off, card
 * muted, kind disabled) — a notification the user did not want is still "done", so enabling
 * push later never replays a backlog.
 */
@Service
public class BillNotificationService implements NotificationProducer {

    private static final Logger log = LoggerFactory.getLogger(BillNotificationService.class);

    /** A statement ingested this long after its own due date is history, not a bill to nag about. */
    static final int STALE_AFTER_DUE_DAYS = 7;
    /** Same guard for statements without a due date, measured from the period end. */
    static final int STALE_AFTER_PERIOD_END_DAYS = 45;

    public record Outcome(int evaluated, int recorded, int sent) {
        static final Outcome NONE = new Outcome(0, 0, 0);
    }

    private final UserNotificationSettingsRepository settingsRepository;
    private final NotificationSettingsService settingsService;
    private final CardBillService cardBillService;
    private final AccountRepository accountRepository;
    private final StatementRepository statementRepository;
    private final WebPushSender sender;

    public BillNotificationService(UserNotificationSettingsRepository settingsRepository,
                                   NotificationSettingsService settingsService,
                                   CardBillService cardBillService,
                                   AccountRepository accountRepository,
                                   StatementRepository statementRepository,
                                   WebPushSender sender) {
        this.settingsRepository = settingsRepository;
        this.settingsService = settingsService;
        this.cardBillService = cardBillService;
        this.accountRepository = accountRepository;
        this.statementRepository = statementRepository;
        this.sender = sender;
    }

    @Override
    public String name() {
        return "bills";
    }

    @Override
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public NotificationOutcome evaluate(UUID userId) {
        Outcome outcome = evaluateUser(userId);
        return new NotificationOutcome(outcome.evaluated(), outcome.recorded(), outcome.sent());
    }

    /** Hourly tick for one user: reminders, overdue nags, due-missing nudges, paid markers. */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public Outcome evaluateUser(UUID userId) {
        LocalDate today = AppTime.today();
        LocalDateTime now = AppTime.now();
        Prefs prefs = prefsFor(userId);
        if (now.getHour() < prefs.sendHour()) {
            return Outcome.NONE;
        }
        int evaluated = 0;
        int recorded = 0;
        int sent = 0;
        for (Account account : accountRepository.findByUserIdAndType(userId, AccountType.credit_card)) {
            if (!CardBillService.isOpen(account, today)) {
                continue;
            }
            Optional<Statement> latest = cardBillService.latestLiveStatement(account.getId());
            if (latest.isEmpty()) {
                continue;
            }
            Statement statement = latest.get();
            CardBill bill = cardBillService.build(account, statement, today);
            evaluated++;
            if (isStale(bill)) {
                continue;
            }
            StatementCreditCardDetails d = statement.getCreditCardDetails();
            // A PAID marker left behind by a deleted link must not silence an unpaid bill forever.
            if (!bill.isPaid() && BillNotificationKinds.PAID.equals(d.getLastNotifiedKind()) && bill.paidMarkedOn() == null) {
                record(statement, BillNotificationKinds.RECEIVED, today);
            }
            String kind = applicableKind(bill, prefs.offsets());
            if (kind == null || !shouldSend(kind, d.getLastNotifiedKind(), d.getLastNotifiedOn(), today)) {
                continue;
            }
            boolean deliverable = prefs.canSend() && !bill.muted() && prefs.allows(kind);
            if (deliverable) {
                sent += deliver(prefs.settings(), kind, bill);
            }
            record(statement, kind, today);
            recorded++;
        }
        return new Outcome(evaluated, recorded, sent);
    }

    /** A statement just committed: send the digest if it is the card's current bill. */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public Outcome onStatementCreated(UUID userId, UUID statementId) {
        LocalDate today = AppTime.today();
        Statement statement = statementRepository.findById(statementId).orElse(null);
        if (statement == null || !"credit_card".equals(statement.getStatementType()) || statement.getCreditCardDetails() == null) {
            return Outcome.NONE;
        }
        Account account = statement.getAccount();
        if (account == null || account.getType() != AccountType.credit_card || !CardBillService.isOpen(account, today)) {
            return Outcome.NONE;
        }
        Optional<Statement> latest = cardBillService.latestLiveStatement(account.getId());
        if (latest.isEmpty() || !latest.get().getId().equals(statementId)) {
            return Outcome.NONE; // an older period arriving late (backfill) is not the bill
        }
        CardBill bill = cardBillService.build(account, statement, today);
        if (isStale(bill)) {
            return Outcome.NONE;
        }
        StatementCreditCardDetails d = statement.getCreditCardDetails();
        String kind = bill.status() == BillStatus.DUE_UNKNOWN ? BillNotificationKinds.DUE_MISSING : BillNotificationKinds.RECEIVED;
        if (!BillNotificationKinds.isLater(kind, d.getLastNotifiedKind())) {
            return new Outcome(1, 0, 0);
        }
        Prefs prefs = prefsFor(userId);
        int sent = 0;
        if (prefs.canSend() && !bill.muted() && prefs.allows(kind)) {
            sent = deliver(prefs.settings(), kind, bill);
        }
        // The digest already names the due date, so a reminder that would fire today is covered by it.
        String dueKind = applicableDueKind(bill, prefs.offsets());
        record(statement, BillNotificationKinds.isLater(dueKind, kind) ? dueKind : kind, today);
        return new Outcome(1, 1, sent);
    }

    // ---------------------------------------------------------------- decision logic (pure, package-private for tests)

    /** The single most urgent kind that applies to the bill right now, or null. */
    static String applicableKind(CardBill bill, List<Integer> offsets) {
        return switch (bill.status()) {
            case PAID -> BillNotificationKinds.PAID;
            case NO_DUE -> null;
            case DUE_UNKNOWN -> BillNotificationKinds.DUE_MISSING;
            case OVERDUE -> BillNotificationKinds.OVERDUE;
            case OPEN, PARTIAL -> applicableDueKind(bill, offsets);
        };
    }

    /** Of the configured offsets, the smallest one that has already been reached ({@code offset >= days left}). */
    static String applicableDueKind(CardBill bill, List<Integer> offsets) {
        if (bill.daysUntilDue() == null || bill.daysUntilDue() < 0
                || (bill.status() != BillStatus.OPEN && bill.status() != BillStatus.PARTIAL)) {
            return null;
        }
        long days = bill.daysUntilDue();
        Integer best = null;
        for (Integer offset : offsets) {
            if (offset != null && offset >= days && (best == null || offset < best)) {
                best = offset;
            }
        }
        return best == null ? null : BillNotificationKinds.dueIn(best);
    }

    /** Later in the sequence than the marker; OVERDUE additionally repeats once per day. */
    static boolean shouldSend(String kind, String lastKind, LocalDate lastOn, LocalDate today) {
        if (kind == null) {
            return false;
        }
        if (BillNotificationKinds.OVERDUE.equals(kind)) {
            if (BillNotificationKinds.OVERDUE.equals(lastKind)) {
                return lastOn == null || lastOn.isBefore(today);
            }
            return BillNotificationKinds.rank(lastKind) < BillNotificationKinds.RANK_OVERDUE;
        }
        return BillNotificationKinds.isLater(kind, lastKind);
    }

    /** Backfilled history must produce zero notifications. */
    static boolean isStale(CardBill bill) {
        if (bill.statementCreatedAt() == null) {
            return false;
        }
        LocalDate ingested = LocalDate.ofInstant(bill.statementCreatedAt(), AppTime.zone());
        if (bill.paymentDueDate() != null) {
            return ingested.isAfter(bill.paymentDueDate().plusDays(STALE_AFTER_DUE_DAYS));
        }
        if (bill.periodEnd() != null) {
            return ingested.isAfter(bill.periodEnd().plusDays(STALE_AFTER_PERIOD_END_DAYS));
        }
        return false;
    }

    static NotificationKind preferenceFor(String kind) {
        if (BillNotificationKinds.RECEIVED.equals(kind) || BillNotificationKinds.DUE_MISSING.equals(kind)) {
            return NotificationKind.STATEMENT_RECEIVED;
        }
        if (BillNotificationKinds.OVERDUE.equals(kind)) {
            return NotificationKind.BILL_OVERDUE;
        }
        if (BillNotificationKinds.isDue(kind)) {
            return NotificationKind.BILL_DUE_REMINDER;
        }
        return null; // PAID: marker only, never pushed
    }

    // ---------------------------------------------------------------- plumbing

    record Prefs(UserNotificationSettings settings, boolean canSend, int sendHour, List<Integer> offsets,
                 Map<NotificationKind, Boolean> kinds) {
        boolean allows(String kind) {
            NotificationKind preference = preferenceFor(kind);
            return preference != null && Boolean.TRUE.equals(kinds.get(preference));
        }
    }

    private Prefs prefsFor(UUID userId) {
        UserNotificationSettings settings = settingsRepository.findById(userId).orElse(null);
        if (settings == null) {
            return new Prefs(null, false, UserNotificationSettings.DEFAULT_SEND_HOUR,
                    NotificationSettingsCodec.parseOffsets(null), NotificationSettingsCodec.parseKinds(null));
        }
        boolean canSend = !Boolean.FALSE.equals(settings.getPushEnabled())
                && !NotificationSettingsCodec.parseSubscriptions(settings.getPushSubscriptions()).isEmpty()
                && sender.isConfigured();
        return new Prefs(settings, canSend,
                settings.getSendHour() == null ? UserNotificationSettings.DEFAULT_SEND_HOUR : settings.getSendHour(),
                NotificationSettingsCodec.parseOffsets(settings.getReminderOffsets()),
                NotificationSettingsCodec.parseKinds(settings.getKindsJson()));
    }

    private int deliver(UserNotificationSettings settings, String kind, CardBill bill) {
        int sent = settingsService.deliver(settings, BillMessages.forKind(kind, bill));
        log.info("Bill notification: kind={}, statementId={}, accountId={}, devices={}",
                kind, bill.statementId(), bill.accountId(), sent);
        return sent;
    }

    private void record(Statement statement, String kind, LocalDate today) {
        StatementCreditCardDetails d = statement.getCreditCardDetails();
        d.setLastNotifiedKind(kind);
        d.setLastNotifiedOn(today);
        statementRepository.save(statement);
    }
}
