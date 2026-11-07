package com.financeos.domain.transaction.match;

import com.financeos.domain.account.Account;
import com.financeos.domain.account.AccountRepository;
import com.financeos.domain.account.card.Card;
import com.financeos.domain.statement.StatementCoverage;
import com.financeos.domain.statement.StatementTransaction;
import com.financeos.domain.statement.StatementTransactionRepository;
import com.financeos.domain.transaction.ReviewReason;
import com.financeos.domain.transaction.ReviewStatusManager;
import com.financeos.domain.transaction.ReviewType;
import com.financeos.domain.transaction.Transaction;
import com.financeos.domain.transaction.TransactionRepository;
import com.financeos.domain.transaction.TransactionSource;
import com.financeos.domain.transaction.TransactionType;
import com.financeos.domain.user.User;
import com.financeos.gmail.domain.GmailProcessedMessageRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Predicate;

/**
 * The single match of the matching design: what one alert email is, against the account's ledger. In order: a statement
 * line (of a covering statement, or within W when not covered), the same bank's resend, the other side of a provider /
 * issuer pair, a manual entry. Else, inside a statement's period it is created tagged
 * {@link ReviewReason#NOT_ON_STATEMENT} (or, in the period's last W days, pairs with the next statement's line), and
 * outside every period it is
 * created Pending. Uses the same pair test as {@link StatementBatchMatcher}, under the same account lock.
 */
@Service
public class AlertMatcher {

    private static final Logger log = LoggerFactory.getLogger(AlertMatcher.class);
    private static final DateTimeFormatter DMY = DateTimeFormatter.ofPattern("dd/MM/yyyy");

    /** Two identical alerts from one sender sent this close together are one payment sent twice. */
    public static final Duration RESEND_WINDOW = Duration.ofMinutes(10);
    /** Two senders of one bank's domain (ICICI cards@ + credit_cards@) mail one standing-instruction charge this close together. */
    public static final Duration SECOND_NOTICE_WINDOW = Duration.ofMinutes(60);
    /** A provider's mail and the issuer's mail for one payment are dated at most this far apart. */
    public static final int PROVIDER_WINDOW_DAYS = 2;
    /** Senders (domain or subdomain) whose mails duplicate the issuer's alert for the same payment. */
    public static final Set<String> PROVIDER_SENDER_DOMAINS = Set.of("cred.club", "paytm.com", "amazonpay.in");

    /**
     * One alert as read from its email; {@code refs} come from the whole email text. {@code amount} is in
     * {@code currency} (null or INR for rupees; ₹ / Rs / $ / € / £ are read as their ISO codes).
     */
    public record Alert(User user, Account account, Card card, TransactionType type, BigDecimal amount, String currency,
                        LocalDate date, String text, String cardLast4, Set<String> refs, String senderAddress,
                        Instant sentAt, String gmailMessageId) {

        public Alert {
            currency = com.financeos.gmail.extract.ExtractParsing.parseCurrency(currency, null);
        }

        /** A charge in a currency other than rupees: its rupee amount is known only from the statement. */
        public boolean isForeign() {
            return MatchRecord.isForeignCode(currency);
        }

        MatchRecord record() {
            String last4 = card != null && card.getLast4() != null ? card.getLast4() : cardLast4;
            if (isForeign()) {
                return new MatchRecord(account.getId(), type, null, date, last4, text, currency, refs, amount);
            }
            return new MatchRecord(account.getId(), type, amount, date, last4, text, currency, refs);
        }

        /** Sets the transaction's amounts: rupees in amount; a foreign charge in original_* with amount null. */
        public void applyAmounts(Transaction txn) {
            if (isForeign()) {
                txn.setAmount(null);
                txn.setOriginalAmount(amount);
                txn.setOriginalCurrency(currency);
            } else {
                txn.setAmount(amount);
            }
        }
    }

    /** What an attach joined. */
    public enum Kind {
        STATEMENT_LINE, ISSUER_EMAIL, PROVIDER_EMAIL, RESENT_ALERT, MANUAL_ENTRY
    }

    /** The alert joined {@code txn}; {@code detail} is the ledger row's text. */
    public record Attached(Transaction txn, Kind kind, String detail) {
    }

    /** The result: an attach, or the transaction created for the alert. */
    public record Outcome(Transaction txn, boolean created, Attached attached) {
    }

    /** The decision before anything is written: an attach target, or create (tagged by {@code notOnStatementId}). */
    private record Decision(Transaction target, Kind kind, UUID notOnStatementId) {
        boolean attaches() {
            return target != null;
        }
    }

    private final AccountRepository accountRepository;
    private final TransactionRepository transactionRepository;
    private final StatementTransactionRepository statementTransactionRepository;
    private final GmailProcessedMessageRepository processedMessageRepository;
    private final StatementCoverage statementCoverage;
    private final ReviewStatusManager reviewStatusManager;
    private final MatchWindow matchWindow;

    /** Optional so tests that build the matcher by hand need none: re-reads candidates after the lock. */
    @jakarta.persistence.PersistenceContext
    private jakarta.persistence.EntityManager entityManager;

    public AlertMatcher(AccountRepository accountRepository,
                        TransactionRepository transactionRepository,
                        StatementTransactionRepository statementTransactionRepository,
                        GmailProcessedMessageRepository processedMessageRepository,
                        StatementCoverage statementCoverage,
                        ReviewStatusManager reviewStatusManager,
                        MatchWindow matchWindow) {
        this.accountRepository = accountRepository;
        this.transactionRepository = transactionRepository;
        this.statementTransactionRepository = statementTransactionRepository;
        this.processedMessageRepository = processedMessageRepository;
        this.statementCoverage = statementCoverage;
        this.reviewStatusManager = reviewStatusManager;
        this.matchWindow = matchWindow;
    }

    /**
     * Attaches the alert when it is a payment already on the ledger; creates nothing otherwise (the pre-AI shortcut).
     * The account is locked only when it attaches, so a miss holds no lock through the AI read that follows.
     */
    @Transactional
    public Optional<Attached> attachIfMatched(Alert alert) {
        if (!usable(alert)) {
            return Optional.empty();
        }
        Ledger firstLook = ledger(alert);
        if (!decide(alert, firstLook).attaches()) {
            return Optional.empty();
        }
        accountRepository.lockById(alert.account().getId());
        refreshCandidates(alert, firstLook);
        Decision d = decide(alert, ledger(alert));
        return d.attaches() ? Optional.of(attach(alert, d)) : Optional.empty();
    }

    /** Attaches the alert to the payment it is, or creates its transaction; under the account lock. */
    @Transactional
    public Outcome matchOrCreate(Alert alert) {
        accountRepository.lockById(alert.account().getId());
        Decision d = usable(alert) ? decide(alert, ledger(alert)) : new Decision(null, null, null);
        if (d.attaches()) {
            Attached a = attach(alert, d);
            return new Outcome(a.txn(), false, a);
        }
        return new Outcome(create(alert, d.notOnStatementId()), true, null);
    }

    private static boolean usable(Alert a) {
        return a.account() != null && a.account().getId() != null && a.date() != null && a.amount() != null && a.type() != null;
    }

    // ------------------------------------------------------------------ decision

    /**
     * The first look ran before the lock, so the rows it loaded are managed with what they held then; the ones that
     * could pair (same direction and amount: usually one or two) are read again before the locked decision.
     */
    private void refreshCandidates(Alert alert, Ledger firstLook) {
        if (entityManager == null) return;
        MatchRecord r = alert.record();
        int reach = reach(alert);
        for (Transaction t : firstLook.txns()) {
            if (alert.isForeign() || MatchRecord.isForeign(t)) {
                if (!PairRules.canPair(r, firstLook.record(t), reach, reach)) continue;
            } else if (t.getType() != alert.type() || t.getAmount() == null || t.getAmount().compareTo(alert.amount()) != 0) {
                continue;
            }
            try {
                if (entityManager.contains(t)) entityManager.refresh(t);
            } catch (jakarta.persistence.EntityNotFoundException gone) {
                // deleted meanwhile: the locked ledger query no longer returns it
            }
        }
    }

    /** The ledger around the alert: rows within the widest window any check uses. */
    private Ledger ledger(Alert alert) {
        int reach = reach(alert);
        return ledger(alert.account().getId(), alert.date().minusDays(reach), alert.date().plusDays(reach), alert.gmailMessageId());
    }

    /** The widest window any check uses; a foreign alert reaches the foreign window. */
    private int reach(Alert alert) {
        int reach = Math.max(matchWindow.daysFor(alert.account()), PROVIDER_WINDOW_DAYS);
        return alert.isForeign() ? Math.max(reach, matchWindow.foreignDays()) : reach;
    }

    private Decision decide(Alert alert, Ledger ledger) {
        UUID accountId = alert.account().getId();
        int w = matchWindow.daysFor(alert.account());
        int fw = matchWindow.foreignDays();
        LocalDate date = alert.date();
        MatchRecord r = alert.record();

        List<StatementCoverage.Period> covering = statementCoverage.statementsCovering(accountId, date);
        Set<UUID> coveringIds = new HashSet<>();
        covering.forEach(p -> coveringIds.add(p.statementId()));

        // 1. A statement line: of a covering statement, or (not covered) any line within W, e.g. just before a period's start.
        Transaction line = cheapest(r, w, fw, ledger, t -> !ledger.alerted(t)
                && (covering.isEmpty() ? ledger.hasLine(t) : ledger.onAnyOf(t, coveringIds)));
        if (line != null) return new Decision(line, Kind.STATEMENT_LINE, null);

        // 2. The same bank's resend: the row may carry its statement line's date by now, so the pair test, not equality.
        Transaction resent = cheapest(r, w, fw, ledger, t -> isResendOf(alert, ledger.mails(t))
                || isSecondNoticeOf(alert, ledger.mails(t)));
        if (resent != null) return new Decision(resent, Kind.RESENT_ALERT, null);

        // 3. The other side of a provider / issuer pair.
        boolean thisIsProvider = isProviderSender(alert.senderAddress());
        Transaction other = cheapest(r, PROVIDER_WINDOW_DAYS, fw, ledger, t -> {
            List<GmailProcessedMessageRepository.AlertMail> mails = ledger.mails(t);
            boolean issuer = mails.stream().anyMatch(m -> !isProviderSender(m.getSenderAddress()));
            boolean provider = mails.stream().anyMatch(m -> isProviderSender(m.getSenderAddress()));
            return thisIsProvider ? issuer && !provider : provider && !issuer;
        });
        if (other != null) return new Decision(other, thisIsProvider ? Kind.ISSUER_EMAIL : Kind.PROVIDER_EMAIL, null);

        // 4. A payment the user typed in.
        Transaction manual = cheapest(r, w, fw, ledger, t -> t.getSource() == TransactionSource.manual && !ledger.alerted(t)
                // A foreign alert's rupee amount is unknown: it never joins a manual entry that has one.
                && (!alert.isForeign() || t.getAmount() == null));
        if (manual != null) return new Decision(manual, Kind.MANUAL_ENTRY, null);

        // 5. Not covered: Pending. Covered: tagged on its statement, else a line of the next statement, else Pending.
        if (covering.isEmpty()) return new Decision(null, null, null);
        StatementCoverage.Period s = covering.stream().max(Comparator.comparing(StatementCoverage.Period::end)).orElseThrow();
        // A foreign charge may post up to the foreign window later, so only one dated before that is missing.
        if (!date.isAfter(s.end().minusDays(alert.isForeign() ? fw : w))) return new Decision(null, null, s.statementId());
        Optional<StatementCoverage.Period> next = statementCoverage.nextAfter(accountId, s.end());
        if (next.isPresent()) {
            UUID nextId = next.get().statementId();
            Transaction nextLine = cheapest(r, w, fw, ledger, t -> !ledger.alerted(t) && ledger.onAnyOf(t, Set.of(nextId)));
            if (nextLine != null) return new Decision(nextLine, Kind.STATEMENT_LINE, null);
        }
        return new Decision(null, null, null);
    }

    /**
     * A resend: a mail of the same sender already points at the row, and all of that sender's mails on it plus this
     * one fall within ten minutes, measured from the first, so 0 / 8 / 16 minutes do not chain into one payment.
     */
    private static boolean isResendOf(Alert alert, List<GmailProcessedMessageRepository.AlertMail> mails) {
        if (alert.senderAddress() == null || alert.sentAt() == null) return false;
        Instant first = alert.sentAt();
        Instant last = alert.sentAt();
        boolean sameSender = false;
        for (GmailProcessedMessageRepository.AlertMail m : mails) {
            if (m.getSenderAddress() == null || m.getInternalDate() == null) continue;
            if (!m.getSenderAddress().equalsIgnoreCase(alert.senderAddress())) continue;
            sameSender = true;
            if (m.getInternalDate().isBefore(first)) first = m.getInternalDate();
            if (m.getInternalDate().isAfter(last)) last = m.getInternalDate();
        }
        return sameSender && Duration.between(first, last).compareTo(RESEND_WINDOW) <= 0;
    }

    /**
     * A second notice: the row holds mails of this sender's domain but none of this exact address, and they plus this
     * one fall within an hour, measured from the earliest. Providers pair through their own step.
     */
    private static boolean isSecondNoticeOf(Alert alert, List<GmailProcessedMessageRepository.AlertMail> mails) {
        if (alert.senderAddress() == null || alert.sentAt() == null || isProviderSender(alert.senderAddress())) return false;
        String domain = domainOf(alert.senderAddress());
        Instant first = alert.sentAt();
        Instant last = alert.sentAt();
        boolean sameDomain = false;
        for (GmailProcessedMessageRepository.AlertMail m : mails) {
            if (m.getSenderAddress() == null || m.getInternalDate() == null) continue;
            if (m.getSenderAddress().equalsIgnoreCase(alert.senderAddress())) return false;
            if (!domainOf(m.getSenderAddress()).equals(domain)) continue;
            sameDomain = true;
            if (m.getInternalDate().isBefore(first)) first = m.getInternalDate();
            if (m.getInternalDate().isAfter(last)) last = m.getInternalDate();
        }
        return sameDomain && Duration.between(first, last).compareTo(SECOND_NOTICE_WINDOW) <= 0;
    }

    private static String domainOf(String address) {
        return address.substring(address.lastIndexOf('@') + 1).trim().toLowerCase(Locale.ROOT);
    }

    /** The lowest-cost transaction that passes the pair test with window {@code w} and {@code also}; ties keep date then creation order. */
    private static Transaction cheapest(MatchRecord r, int w, int foreignW, Ledger ledger, Predicate<Transaction> also) {
        Transaction best = null;
        int bestCost = Integer.MAX_VALUE;
        for (Transaction t : ledger.txns()) {
            MatchRecord tr = ledger.record(t);
            if (!PairRules.canPair(r, tr, w, foreignW) || !also.test(t)) continue;
            int c = PairRules.cost(r, tr);
            if (c < bestCost) {
                best = t;
                bestCost = c;
            }
        }
        return best;
    }

    public static boolean isProviderSender(String address) {
        if (address == null) {
            return false;
        }
        int at = address.lastIndexOf('@');
        String domain = (at >= 0 ? address.substring(at + 1) : address).trim().toLowerCase(Locale.ROOT);
        return PROVIDER_SENDER_DOMAINS.stream().anyMatch(p -> domain.equals(p) || domain.endsWith("." + p));
    }

    // ------------------------------------------------------------------ effects

    /**
     * Joins the alert to the transaction. Statement lines win, so a line's transaction is left as it is (a
     * not-on-statement tag too: a statement decides that); the issuer's text and date win over a provider's; a manual
     * entry keeps its description and category and gains the bank text.
     */
    private Attached attach(Alert alert, Decision d) {
        Transaction t = d.target();
        String detail;
        switch (d.kind()) {
            case STATEMENT_LINE -> {
                detail = "statement line " + (t.getDate() == null ? "" : DMY.format(t.getDate()));
                // The line row has the rupee amount; a foreign alert adds what it was charged in.
                if (alert.isForeign() && t.getOriginalCurrency() == null) {
                    t.setOriginalAmount(alert.amount());
                    t.setOriginalCurrency(alert.currency());
                    t = transactionRepository.save(t);
                }
            }
            case ISSUER_EMAIL -> detail = "issuer email";
            case PROVIDER_EMAIL -> {
                detail = "provider email";
                if (statementTransactionRepository.findByIdTransactionId(t.getId()).isEmpty()) {
                    t.setDate(alert.date());
                    if (alert.text() != null && !alert.text().isBlank()) t.setSourcedDescription(alert.text());
                    t = transactionRepository.save(t);
                }
            }
            case RESENT_ALERT -> detail = "resent alert";
            case MANUAL_ENTRY -> {
                detail = "manual entry";
                if (alert.text() != null && !alert.text().isBlank()) {
                    t.setSourcedDescription(alert.text());
                    t = transactionRepository.save(t);
                }
            }
            default -> detail = "existing transaction";
        }
        log.info("gmail.alert.attached kind={}", d.kind());
        return new Attached(t, d.kind(), "Attached to an existing transaction (" + detail + ")");
    }

    /** Pending (no reason) or, when its statement lacks it, tagged not on statement. */
    private Transaction create(Alert alert, UUID notOnStatementId) {
        Transaction txn = new Transaction();
        txn.setUser(alert.user());
        txn.setAccount(alert.account());
        txn.setCard(alert.card());
        txn.setDate(alert.date());
        alert.applyAmounts(txn);
        txn.setSourcedDescription(alert.text());
        txn.setSource(TransactionSource.gmail_transaction_alert);
        txn.setType(alert.type());
        txn.setSourceMessageId(alert.gmailMessageId());
        txn.setTransactionUnderMonitoring(false);
        txn.setTransactionExcluded(false);
        if (notOnStatementId != null) {
            reviewStatusManager.addReason(txn, ReviewReason.NOT_ON_STATEMENT);
            txn.setNotOnStatementId(notOnStatementId);
        } else {
            reviewStatusManager.transitionTo(txn, ReviewType.AUTO_REVIEWED);
        }
        return transactionRepository.save(txn);
    }

    // ------------------------------------------------------------------ the ledger around the alert

    /** The account's transactions in the range, their statement links and the alert mails pointing at them. */
    private record Ledger(List<Transaction> txns, Map<UUID, Set<UUID>> statementsByTxn,
                          Map<UUID, List<GmailProcessedMessageRepository.AlertMail>> mailsByTxn,
                          Map<Transaction, MatchRecord> records) {
        /** Built once per decision; every check reads the same records. */
        MatchRecord record(Transaction t) {
            return records.computeIfAbsent(t, MatchRecord::of);
        }

        boolean alerted(Transaction t) {
            return mailsByTxn.containsKey(t.getId());
        }

        boolean hasLine(Transaction t) {
            return statementsByTxn.containsKey(t.getId());
        }

        boolean onAnyOf(Transaction t, Set<UUID> statementIds) {
            Set<UUID> s = statementsByTxn.get(t.getId());
            return s != null && s.stream().anyMatch(statementIds::contains);
        }

        List<GmailProcessedMessageRepository.AlertMail> mails(Transaction t) {
            return mailsByTxn.getOrDefault(t.getId(), List.of());
        }
    }

    /** Three queries per alert, never one per row. Mails of {@code ownMessageId} (this alert, on a retry) are left out. */
    private Ledger ledger(UUID accountId, LocalDate lo, LocalDate hi, String ownMessageId) {
        List<Transaction> txns = new ArrayList<>(transactionRepository.findLedgerByAccountIdAndDateRange(accountId, lo, hi));
        txns.sort(Comparator.comparing(Transaction::getDate, Comparator.nullsLast(Comparator.naturalOrder()))
                .thenComparing(Transaction::getCreatedAt, Comparator.nullsLast(Comparator.naturalOrder()))
                .thenComparing(t -> String.valueOf(t.getId())));
        Map<UUID, Set<UUID>> statementsByTxn = new HashMap<>();
        Map<UUID, List<GmailProcessedMessageRepository.AlertMail>> mailsByTxn = new HashMap<>();
        if (txns.isEmpty()) {
            return new Ledger(txns, statementsByTxn, mailsByTxn, new java.util.IdentityHashMap<>());
        }
        for (StatementTransaction st : statementTransactionRepository.findLinksForAccountInRange(accountId, lo, hi)) {
            statementsByTxn.computeIfAbsent(st.getId().getTransactionId(), k -> new HashSet<>()).add(st.getId().getStatementId());
        }
        List<UUID> ids = txns.stream().map(Transaction::getId).toList();
        for (GmailProcessedMessageRepository.AlertMail m : processedMessageRepository.findAlertMailsByTransactionIdIn(ids)) {
            if (ownMessageId != null && ownMessageId.equals(m.getGmailMessageId())) continue;
            mailsByTxn.computeIfAbsent(m.getTransactionId(), k -> new ArrayList<>()).add(m);
        }
        return new Ledger(txns, statementsByTxn, mailsByTxn, new java.util.IdentityHashMap<>());
    }
}
