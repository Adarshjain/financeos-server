package com.financeos.domain.notification.reminder;

import com.financeos.api.loan.dto.InstallmentDto;
import com.financeos.core.time.AppTime;
import com.financeos.domain.loan.Loan;
import com.financeos.domain.loan.LoanRepository;
import com.financeos.domain.loan.LoanService;
import com.financeos.domain.loan.LoanStatus;
import com.financeos.domain.notification.NotificationKind;
import com.financeos.domain.notification.NotificationOutcome;
import com.financeos.domain.notification.NotificationPrefs;
import com.financeos.domain.notification.NotificationPrefsLoader;
import com.financeos.domain.notification.NotificationProducer;
import com.financeos.domain.notification.bill.BillNotificationService;
import com.financeos.domain.notification.bill.BillStatus;
import com.financeos.domain.notification.bill.CardBill;
import com.financeos.domain.notification.bill.CardBillService;
import com.financeos.domain.notification.emi.EmiNotificationService;
import com.financeos.domain.notification.feed.AppNotificationKind;
import com.financeos.domain.notification.feed.AppNotificationRefs;
import com.financeos.domain.notification.feed.NotificationFeedService;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Service;

/**
 * The feed side of bill and EMI reminders, for every user whether push can reach them or not: one feed row per
 * unpaid card bill and per unpaid EMI installment that is due within {@value ReminderStages#WINDOW_DAYS} days or
 * overdue (up to {@value ReminderStages#OVERDUE_LIMIT_DAYS} days), updated in place as the days pass and cleared
 * when the bill is paid or the installment recorded. Push is a separate nudge on top
 * ({@code BillNotificationService}, {@code EmiNotificationService}).
 *
 * <p>Runs once a day per user, from the user's send hour (the tick is hourly; a late tick or a restart just runs it
 * then). The per-kind switches apply to feed rows too: a stage whose kind is off gets no row, and a row of a
 * stage that is off is cleared. Nothing is resolved when the bills or loans could not be read.
 */
@Service
public class ReminderFeedService implements NotificationProducer {

    private static final Set<BillStatus> UNPAID = Set.of(BillStatus.OPEN, BillStatus.PARTIAL, BillStatus.OVERDUE);
    private static final List<String> KINDS = List.of(AppNotificationKind.BILL_REMINDER, AppNotificationKind.EMI_REMINDER);

    private final CardBillService cardBillService;
    private final LoanService loanService;
    private final LoanRepository loanRepository;
    private final NotificationPrefsLoader prefsLoader;
    private final NotificationFeedService feedService;
    /** The business day each user was last processed on (in memory: a restart just repeats a cheap idempotent pass). */
    private final Map<UUID, LocalDate> lastRun = new ConcurrentHashMap<>();

    public ReminderFeedService(CardBillService cardBillService,
                               LoanService loanService,
                               LoanRepository loanRepository,
                               NotificationPrefsLoader prefsLoader,
                               NotificationFeedService feedService) {
        this.cardBillService = cardBillService;
        this.loanService = loanService;
        this.loanRepository = loanRepository;
        this.prefsLoader = prefsLoader;
        this.feedService = feedService;
    }

    @Override
    public String name() {
        return "reminder-feed";
    }

    @Override
    public boolean runsWithoutPush() {
        return true;
    }

    /** Users with an active loan: the tick visits them even without a card or push. */
    @Override
    public Collection<UUID> extraFeedUserIds() {
        return loanRepository.findDistinctUserIdsByStatus(LoanStatus.active);
    }

    @Override
    public NotificationOutcome evaluate(UUID userId) {
        LocalDate today = AppTime.today();
        NotificationPrefs prefs = prefsLoader.load(userId);
        if (!prefs.pastSendHour(AppTime.now()) || today.equals(lastRun.get(userId))) {
            return NotificationOutcome.NONE;
        }
        List<NotificationFeedService.Reminder> reminders = new ArrayList<>();
        int evaluated = bills(userId, prefs, today, reminders) + emis(prefs, today, reminders);
        Set<String> keep = new HashSet<>();
        reminders.forEach(r -> keep.add(r.dedupeKey()));
        feedService.upsertAll(userId, reminders);
        feedService.resolveAllExcept(userId, KINDS, keep);
        lastRun.put(userId, today);
        return new NotificationOutcome(evaluated, reminders.size(), 0);
    }

    private int bills(UUID userId, NotificationPrefs prefs, LocalDate today, List<NotificationFeedService.Reminder> out) {
        int evaluated = 0;
        for (CardBill bill : cardBillService.listBills(userId)) {
            if (bill.statementId() == null || bill.status() == null || !UNPAID.contains(bill.status())
                    || bill.paymentDueDate() == null || BillNotificationService.isStale(bill)) {
                continue;
            }
            evaluated++;
            long days = ChronoUnit.DAYS.between(today, bill.paymentDueDate());
            String stage = ReminderStages.stage(days);
            if (stage == null || !prefs.allows(preference(stage, NotificationKind.BILL_DUE_REMINDER, NotificationKind.BILL_OVERDUE))) {
                continue;
            }
            BigDecimal amount = bill.remainingAmount() != null ? bill.remainingAmount() : bill.totalAmountDue();
            out.add(new NotificationFeedService.Reminder(ReminderKeys.bill(bill.statementId()), AppNotificationKind.BILL_REMINDER,
                    ReminderStages.billTitle(bill.accountName(), days), ReminderStages.billBody(amount, bill.paymentDueDate()),
                    AppNotificationRefs.ofAccount(bill.accountId()), stage));
        }
        return evaluated;
    }

    private int emis(NotificationPrefs prefs, LocalDate today, List<NotificationFeedService.Reminder> out) {
        int evaluated = 0;
        for (LoanService.LoanWithSchedule entry : loanService.getLoansWithSchedule(l -> l.getStatus() == LoanStatus.active)) {
            Loan loan = entry.loan();
            List<InstallmentDto> installments = entry.schedule().installments();
            for (InstallmentDto installment : installments) {
                if ("settled".equals(installment.status()) || installment.dueDate() == null || installment.seq() == null) {
                    continue;
                }
                long days = ChronoUnit.DAYS.between(today, installment.dueDate());
                String stage = ReminderStages.stage(days);
                if (stage == null || (ReminderStages.isOverdue(stage) && EmiNotificationService.isStale(loan, installment, today))) {
                    continue;
                }
                evaluated++;
                if (!prefs.allows(preference(stage, NotificationKind.EMI_DUE_REMINDER, NotificationKind.EMI_OVERDUE))) {
                    continue;
                }
                out.add(new NotificationFeedService.Reminder(ReminderKeys.emi(loan.getId(), installment.seq()),
                        AppNotificationKind.EMI_REMINDER, ReminderStages.emiTitle(loan.getName(), days),
                        ReminderStages.emiBody(installment.emi(), installment.dueDate(), installment.seq(), installments.size()),
                        AppNotificationRefs.ofLoan(loan.getId()), stage));
            }
        }
        return evaluated;
    }

    private static NotificationKind preference(String stage, NotificationKind due, NotificationKind overdue) {
        return ReminderStages.isOverdue(stage) ? overdue : due;
    }
}
