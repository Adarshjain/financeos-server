package com.financeos.domain.notification.bill;

import com.financeos.core.exception.ResourceNotFoundException;
import com.financeos.core.exception.ValidationException;
import com.financeos.core.time.AppTime;
import com.financeos.domain.account.Account;
import com.financeos.domain.account.AccountRepository;
import com.financeos.domain.account.AccountType;
import com.financeos.domain.account.cycle.BillingCycles;
import com.financeos.domain.statement.Statement;
import com.financeos.domain.statement.StatementCreditCardDetails;
import com.financeos.domain.statement.StatementRepository;
import com.financeos.domain.statement.StatementVerdict;
import com.financeos.domain.transaction.Transaction;
import com.financeos.domain.transaction.TransactionRepository;
import com.financeos.domain.transaction.link.LinkType;
import com.financeos.domain.transaction.link.TransactionLink;
import com.financeos.domain.transaction.link.TransactionLinkMember;
import com.financeos.domain.transaction.link.TransactionLinkRepository;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The statement is the bill. This service turns a card's latest live statement plus the
 * settlement signals (manual mark, CC_PAYMENT and REFUND/REVERSAL links, unlinked credits) into a {@link CardBill},
 * and owns the three writes a user can make on it: mark paid, undo, fill in missing details.
 *
 * <p>Settlement precedence: a manual mark wins; otherwise the card-side credits of CC_PAYMENT
 * links, and credits linked as a REFUND or REVERSAL, after the period end count as paid; plain credits are only ever surfaced as
 * "possible payments" for the user to confirm — they never close a bill on their own.
 */
@Service
@Transactional(readOnly = true)
public class CardBillService {

    static final int MAX_POSSIBLE_PAYMENTS = 5;

    private final AccountRepository accountRepository;
    private final StatementRepository statementRepository;
    private final TransactionRepository transactionRepository;
    private final TransactionLinkRepository transactionLinkRepository;

    public CardBillService(AccountRepository accountRepository,
                           StatementRepository statementRepository,
                           TransactionRepository transactionRepository,
                           TransactionLinkRepository transactionLinkRepository) {
        this.accountRepository = accountRepository;
        this.statementRepository = statementRepository;
        this.transactionRepository = transactionRepository;
        this.transactionLinkRepository = transactionLinkRepository;
    }

    /**
     * Every open credit card, most urgent first: a card with a live statement carries that bill
     * (enriched with the unbilled spend and the next expected statement); a card without one is an
     * {@link BillStatus#AWAITING_STATEMENT} row. Notifications never see the awaiting rows — they
     * go through {@link #build} directly.
     */
    public List<CardBill> listBills(UUID userId) {
        LocalDate today = AppTime.today();
        List<CardBill> bills = new ArrayList<>();
        for (Account account : accountRepository.findByUserIdAndType(userId, AccountType.credit_card)) {
            if (!isOpen(account, today)) {
                continue;
            }
            Optional<Statement> live = latestLiveStatement(account.getId());
            bills.add(live.isPresent() ? buildEnriched(account, live.get(), today) : awaitingStatement(account));
        }
        bills.sort(Comparator
                .comparingInt((CardBill b) -> urgency(b.status()))
                .thenComparing(CardBill::paymentDueDate, Comparator.nullsLast(Comparator.naturalOrder()))
                .thenComparing(CardBill::accountName, Comparator.nullsLast(String.CASE_INSENSITIVE_ORDER)));
        return bills;
    }

    public CardBill findByStatementId(UUID userId, UUID statementId) {
        Statement statement = loadOwnedCardStatement(userId, statementId);
        return buildEnriched(statement.getAccount(), statement, AppTime.today());
    }

    /**
     * The newest non-rejected credit-card statement with card details: the current bill.
     * Latest by period end; statements without a period end only win when nothing else exists.
     */
    public Optional<Statement> latestLiveStatement(UUID accountId) {
        List<Statement> statements = statementRepository.findQualifyingCreditCardStatements(accountId);
        Statement best = null;
        for (Statement s : statements) {
            if (s.getCreditCardDetails() == null) {
                continue;
            }
            if (best == null || laterThan(s, best)) {
                best = s;
            }
        }
        return Optional.ofNullable(best);
    }

    /**
     * The bill as the settlement signals describe it. {@code unbilledAmount} and
     * {@code nextStatementExpectedOn} are left null here: the notification tick does not need them
     * and must not pay for the extra queries. {@link #listBills} fills them in.
     */
    public CardBill build(Account account, Statement statement, LocalDate today) {
        return assemble(account, statement, today).bill();
    }

    private record Built(CardBill bill, Settlement settlement) {
    }

    private Built assemble(Account account, Statement statement, LocalDate today) {
        StatementCreditCardDetails d = statement.getCreditCardDetails();
        BigDecimal total = d.getTotalAmountDue();
        LocalDate due = d.getPaymentDueDate();

        Settlement settlement = settle(account.getId(), statement.getPeriodEnd());

        BigDecimal paid;
        PaidSource source;
        if (d.getPaidMarkedOn() != null) {
            paid = d.getPaidMarkedAmount() != null ? d.getPaidMarkedAmount() : total;
            source = PaidSource.MANUAL;
        } else if (settlement.linkedPaid.signum() > 0) {
            paid = settlement.linkedPaid;
            source = PaidSource.LINK;
        } else {
            paid = BigDecimal.ZERO;
            source = PaidSource.NONE;
        }

        BillStatus status;
        if (total == null || due == null) {
            status = source == PaidSource.MANUAL ? BillStatus.PAID : BillStatus.DUE_UNKNOWN;
        } else if (total.signum() <= 0) {
            status = BillStatus.NO_DUE;
        } else if (paid != null && paid.compareTo(total) >= 0) {
            status = BillStatus.PAID;
        } else if (due.isBefore(today)) {
            status = BillStatus.OVERDUE;
        } else if (paid != null && paid.signum() > 0) {
            status = BillStatus.PARTIAL;
        } else {
            status = BillStatus.OPEN;
        }

        BigDecimal remaining = null;
        if (total != null) {
            remaining = status == BillStatus.PAID || status == BillStatus.NO_DUE
                    ? BigDecimal.ZERO
                    : total.subtract(paid == null ? BigDecimal.ZERO : paid).max(BigDecimal.ZERO);
        }
        List<CardBill.PossiblePayment> possible = status == BillStatus.PAID || status == BillStatus.NO_DUE
                ? List.of()
                : settlement.possible;
        Long daysUntilDue = due == null ? null : ChronoUnit.DAYS.between(today, due);

        BigDecimal creditLimit = d.getCreditLimit() != null
                ? d.getCreditLimit()
                : (account.getCreditCardDetails() != null ? account.getCreditCardDetails().getCreditLimit() : null);
        BigDecimal utilization = null;
        if (total != null && creditLimit != null && creditLimit.signum() > 0) {
            utilization = total.multiply(BigDecimal.valueOf(100)).divide(creditLimit, 1, RoundingMode.HALF_UP);
        }
        CardBill.Digest digest = new CardBill.Digest(
                d.getTotalPurchases(), d.getPaymentsReceived(), d.getFinanceCharges(), d.getFeesAndCharges(),
                d.getRewardPointsEarned(), d.getRewardPointsBalance(), creditLimit, utilization,
                statement.getTransactionCount());

        CardBill bill = new CardBill(
                account.getId(),
                account.getName(),
                account.primaryLast4(),
                statement.getId(),
                statement.getPeriodStart(),
                statement.getPeriodEnd(),
                due,
                total,
                d.getMinimumAmountDue(),
                paid,
                remaining,
                source,
                status,
                daysUntilDue,
                d.getPaidMarkedOn(),
                possible,
                Boolean.TRUE.equals(account.getNotificationsMuted()),
                statement.getCreatedAt(),
                d.getLastNotifiedKind(),
                d.getLastNotifiedOn(),
                digest,
                null,
                null);
        return new Built(bill, settlement);
    }

    /** {@link #build} plus the two read-side extras: unbilled spend since the period end and the next expected statement. */
    private CardBill buildEnriched(Account account, Statement statement, LocalDate today) {
        Built built = assemble(account, statement, today);
        LocalDate periodEnd = statement.getPeriodEnd();
        BigDecimal unbilled = periodEnd == null
                ? null
                : unbilled(transactionRepository.sumIncludedDebitsAfter(account.getId(), periodEnd));
        return built.bill().withUnbilled(unbilled, nextStatementExpectedOn(account.getId()));
    }

    /** An open card with no live statement: nothing to pay yet, only what has been spent so far. */
    private CardBill awaitingStatement(Account account) {
        BigDecimal spendSinceEver = unbilled(transactionRepository.sumIncludedDebits(account.getId()));
        return new CardBill(
                account.getId(),
                account.getName(),
                account.primaryLast4(),
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                PaidSource.NONE,
                BillStatus.AWAITING_STATEMENT,
                null,
                null,
                List.of(),
                Boolean.TRUE.equals(account.getNotificationsMuted()),
                null,
                null,
                null,
                null,
                spendSinceEver,
                nextStatementExpectedOn(account.getId()));
    }

    /**
     * Card spend not yet on a statement: the non-excluded debits. Credits never reduce it: bill
     * payments and refunds both count towards the open bill instead (see {@link #settle}).
     * Null-guarded because a mocked repository returns null.
     */
    private static BigDecimal unbilled(BigDecimal debits) {
        return (debits == null ? BigDecimal.ZERO : debits).max(BigDecimal.ZERO);
    }

    /**
     * The projected close of the cycle after the card's latest dated statement, from
     * {@link BillingCycles}. May already be past when the next statement is late (the client shows
     * the lateness); null when no non-rejected statement carries both period dates.
     */
    private LocalDate nextStatementExpectedOn(UUID accountId) {
        List<Statement> statements = statementRepository.findByAccountIdOrderByPeriodEndDescNullsLast(accountId);
        if (statements == null || statements.isEmpty()) {
            return null;
        }
        BillingCycles cycles = BillingCycles.fromStatements(statements);
        if (!cycles.hasStatements()) {
            return null;
        }
        LocalDate latestPeriodEnd = null;
        for (Statement s : statements) {
            if (s.getVerdict() == StatementVerdict.REJECTED || s.getPeriodStart() == null || s.getPeriodEnd() == null
                    || s.getPeriodEnd().isBefore(s.getPeriodStart())) {
                continue; // the same statements BillingCycles ignores
            }
            if (latestPeriodEnd == null || s.getPeriodEnd().isAfter(latestPeriodEnd)) {
                latestPeriodEnd = s.getPeriodEnd();
            }
        }
        return latestPeriodEnd == null ? null : cycles.containing(latestPeriodEnd.plusDays(1)).end();
    }

    // ---------------------------------------------------------------- writes

    /** "Mark as paid": {@code amount} null means in full. A full payment silently ends the notification sequence. */
    @Transactional
    public CardBill markPaid(UUID userId, UUID statementId, BigDecimal amount, LocalDate paidOn) {
        LocalDate today = AppTime.today();
        if (amount != null && amount.signum() <= 0) {
            throw new ValidationException("Paid amount must be greater than zero");
        }
        if (paidOn != null && paidOn.isAfter(today)) {
            throw new ValidationException("Paid date cannot be in the future");
        }
        Statement statement = loadOwnedCardStatement(userId, statementId);
        StatementCreditCardDetails d = statement.getCreditCardDetails();
        d.setPaidMarkedOn(paidOn != null ? paidOn : today);
        d.setPaidMarkedAmount(amount);
        CardBill bill = build(statement.getAccount(), statement, today);
        if (bill.isPaid()) {
            d.setLastNotifiedKind(BillNotificationKinds.PAID);
            d.setLastNotifiedOn(today);
        }
        statementRepository.save(statement);
        return buildEnriched(statement.getAccount(), statement, today);
    }

    /** Undo a manual mark; reminders resume from the start of the sequence. */
    @Transactional
    public CardBill unmarkPaid(UUID userId, UUID statementId) {
        LocalDate today = AppTime.today();
        Statement statement = loadOwnedCardStatement(userId, statementId);
        StatementCreditCardDetails d = statement.getCreditCardDetails();
        d.setPaidMarkedOn(null);
        d.setPaidMarkedAmount(null);
        if (BillNotificationKinds.PAID.equals(d.getLastNotifiedKind())) {
            d.setLastNotifiedKind(BillNotificationKinds.RECEIVED);
            d.setLastNotifiedOn(today);
        }
        statementRepository.save(statement);
        return buildEnriched(statement.getAccount(), statement, today);
    }

    /** Fill in (or correct) what the parser missed. Resets the sequence so reminders re-evaluate against the new date. */
    @Transactional
    public CardBill updateDetails(UUID userId, UUID statementId, LocalDate paymentDueDate,
                                  BigDecimal totalAmountDue, BigDecimal minimumAmountDue) {
        if (paymentDueDate == null && totalAmountDue == null && minimumAmountDue == null) {
            throw new ValidationException("Provide a due date, total due or minimum due");
        }
        if (totalAmountDue != null && totalAmountDue.signum() < 0) {
            throw new ValidationException("Total due cannot be negative");
        }
        if (minimumAmountDue != null && minimumAmountDue.signum() < 0) {
            throw new ValidationException("Minimum due cannot be negative");
        }
        LocalDate today = AppTime.today();
        Statement statement = loadOwnedCardStatement(userId, statementId);
        StatementCreditCardDetails d = statement.getCreditCardDetails();
        if (paymentDueDate != null) {
            d.setPaymentDueDate(paymentDueDate);
        }
        if (totalAmountDue != null) {
            d.setTotalAmountDue(totalAmountDue);
        }
        if (minimumAmountDue != null) {
            d.setMinimumAmountDue(minimumAmountDue);
        }
        if (!BillNotificationKinds.PAID.equals(d.getLastNotifiedKind())) {
            d.setLastNotifiedKind(BillNotificationKinds.RECEIVED);
            d.setLastNotifiedOn(today);
        }
        statementRepository.save(statement);
        return buildEnriched(statement.getAccount(), statement, today);
    }

    // ---------------------------------------------------------------- helpers

    static boolean isOpen(Account account, LocalDate today) {
        return account.getClosedOn() == null || account.getClosedOn().isAfter(today);
    }

    private record Settlement(BigDecimal linkedPaid, List<CardBill.PossiblePayment> possible) {
    }

    /**
     * Credits after the period end, split into ones that count towards the bill (CC_PAYMENT-linked
     * payments, and REFUND/REVERSAL-linked credits) and unlinked "possible" payments.
     */
    private Settlement settle(UUID accountId, LocalDate periodEnd) {
        if (periodEnd == null) {
            return new Settlement(BigDecimal.ZERO, List.of());
        }
        List<Transaction> credits = transactionRepository.findCreditsAfter(accountId, periodEnd).stream()
                .filter(t -> !t.isTransactionExcluded())
                .toList();
        if (credits.isEmpty()) {
            return new Settlement(BigDecimal.ZERO, List.of());
        }
        Set<UUID> creditIds = new HashSet<>();
        credits.forEach(t -> creditIds.add(t.getId()));

        Map<UUID, LinkType> linkedAs = new HashMap<>();
        Set<UUID> anchors = new HashSet<>();
        for (TransactionLink link : transactionLinkRepository.findDistinctByMembers_Transaction_IdIn(creditIds)) {
            for (TransactionLinkMember member : link.getMembers()) {
                UUID txnId = member.getTransaction().getId();
                if (creditIds.contains(txnId)) {
                    linkedAs.put(txnId, link.getType());
                    if (member.isAnchor()) {
                        anchors.add(txnId);
                    }
                }
            }
        }

        BigDecimal linkedPaid = BigDecimal.ZERO;
        List<CardBill.PossiblePayment> possible = new ArrayList<>();
        for (Transaction credit : credits) {
            LinkType type = linkedAs.get(credit.getId());
            if (type == null) {
                if (possible.size() < MAX_POSSIBLE_PAYMENTS) {
                    possible.add(new CardBill.PossiblePayment(credit.getId(), credit.getDate(), credit.getAmount(),
                            credit.getDescription()));
                }
            } else if ((type == LinkType.CC_PAYMENT && !anchors.contains(credit.getId()))
                    || type == LinkType.REFUND || type == LinkType.REVERSAL) {
                // A refund or reversal credited to the card pays down the open bill like a payment does.
                linkedPaid = linkedPaid.add(credit.getAmount() == null ? BigDecimal.ZERO : credit.getAmount());
            }
        }
        return new Settlement(linkedPaid, possible);
    }

    private Statement loadOwnedCardStatement(UUID userId, UUID statementId) {
        Statement statement = statementRepository.findById(statementId)
                .orElseThrow(() -> new ResourceNotFoundException("Statement", statementId));
        if (userId != null && (statement.getUser() == null || !statement.getUser().getId().equals(userId))) {
            throw new ValidationException("You do not have permission to access this statement.");
        }
        if (!"credit_card".equals(statement.getStatementType()) || statement.getCreditCardDetails() == null) {
            throw new ValidationException("Only credit card statements carry a bill.");
        }
        return statement;
    }

    private static boolean laterThan(Statement a, Statement b) {
        LocalDate ea = a.getPeriodEnd();
        LocalDate eb = b.getPeriodEnd();
        if (ea != null && eb != null && !ea.equals(eb)) {
            return ea.isAfter(eb);
        }
        if (ea != null && eb == null) {
            return true;
        }
        if (ea == null && eb != null) {
            return false;
        }
        if (a.getCreatedAt() != null && b.getCreatedAt() != null) {
            return a.getCreatedAt().isAfter(b.getCreatedAt());
        }
        return a.getCreatedAt() != null;
    }

    private static int urgency(BillStatus status) {
        return switch (status) {
            case OVERDUE -> 0;
            case DUE_UNKNOWN -> 1;
            case OPEN, PARTIAL -> 2;
            case AWAITING_STATEMENT -> 3;
            case NO_DUE -> 4;
            case PAID -> 5;
        };
    }
}
