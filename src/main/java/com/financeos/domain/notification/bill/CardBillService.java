package com.financeos.domain.notification.bill;

import com.financeos.core.exception.ResourceNotFoundException;
import com.financeos.core.exception.ValidationException;
import com.financeos.core.time.AppTime;
import com.financeos.domain.account.Account;
import com.financeos.domain.account.AccountRepository;
import com.financeos.domain.account.BalanceMath;
import com.financeos.domain.account.AccountService;
import com.financeos.domain.account.AccountType;
import com.financeos.domain.account.CardUtilization;
import com.financeos.domain.account.cycle.BillingCycles;
import com.financeos.domain.statement.Statement;
import com.financeos.domain.statement.StatementCreditCardDetails;
import com.financeos.domain.statement.StatementRepository;
import com.financeos.domain.statement.StatementVerdict;
import com.financeos.domain.transaction.TransactionRepository;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The statement is the bill. This service turns a card's latest live statement plus what the card
 * has been credited since into a {@link CardBill}, and owns the three writes a user can make on it:
 * mark paid, undo, fill in missing details.
 *
 * <p>The bill owes what the account balance owes. Both count from the statement's last billed day
 * ({@link BalanceMath#cutoff}: the period end, or the latest line's date when earlier): the total
 * due, less every credit dated after that day (payments, refunds, cashback — linked or not,
 * excluded or not, exactly the credits the balance subtracts), plus every debit after it as
 * unbilled spend. So {@code remainingAmount + unbilledAmount} is the negative balance GET /accounts
 * reports for the card.
 *
 * <p>Settlement precedence: a manual mark wins over the credits (a payment whose transaction has not
 * arrived yet); otherwise the credits since the last billed day pay the bill, and whatever they
 * cover beyond it comes off the unbilled spend.
 */
@Service
@Transactional(readOnly = true)
public class CardBillService {

    private final AccountRepository accountRepository;
    private final StatementRepository statementRepository;
    private final TransactionRepository transactionRepository;
    private final AccountService accountService;

    public CardBillService(AccountRepository accountRepository,
                           StatementRepository statementRepository,
                           TransactionRepository transactionRepository,
                           AccountService accountService) {
        this.accountRepository = accountRepository;
        this.statementRepository = statementRepository;
        this.transactionRepository = transactionRepository;
        this.accountService = accountService;
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
     * The bill as the statement and the credits since describe it. {@code unbilledAmount},
     * {@code nextStatementExpectedOn} and the digest's live {@code utilizationPct} are left null
     * here: the notification tick does not need them and must not pay for the extra queries.
     * {@link #listBills} and the other read paths fill them in. Settlement reads two things beyond
     * the statement: the date of its latest line ({@link #lastBilledDay}) and the credit sum after it.
     */
    public CardBill build(Account account, Statement statement, LocalDate today) {
        return assemble(account, statement, today).bill();
    }

    /**
     * {@code cutoff} is the statement's last billed day ({@link #lastBilledDay}; null without a period
     * end) and {@code creditsSince} the credits dated after it (zero without a cutoff).
     */
    private record Built(CardBill bill, @Nullable LocalDate cutoff, BigDecimal creditsSince) {
    }

    private Built assemble(Account account, Statement statement, LocalDate today) {
        StatementCreditCardDetails d = statement.getCreditCardDetails();
        BigDecimal total = d.getTotalAmountDue();
        LocalDate due = d.getPaymentDueDate();

        LocalDate cutoff = lastBilledDay(statement);
        BigDecimal creditsSince = cutoff == null
                ? BigDecimal.ZERO
                : nonNegative(transactionRepository.sumCreditsAfter(account.getId(), cutoff));

        BigDecimal paid;
        PaidSource source;
        if (d.getPaidMarkedOn() != null) {
            paid = d.getPaidMarkedAmount() != null ? d.getPaidMarkedAmount() : total;
            source = PaidSource.MANUAL;
        } else if (creditsSince.signum() > 0) {
            paid = creditsSince;
            source = PaidSource.CREDITS;
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
        Long daysUntilDue = due == null ? null : ChronoUnit.DAYS.between(today, due);

        // The limit utilisation divides by: the card's own, else the latest statement's that has one
        // (CardUtilization.creditLimit, the same rule as GET /accounts and the cycle summary).
        BigDecimal creditLimit = CardUtilization.creditLimit(account, statementRepository);
        CardBill.Digest digest = new CardBill.Digest(
                d.getTotalPurchases(), d.getPaymentsReceived(), d.getFinanceCharges(), d.getFeesAndCharges(),
                d.getRewardPointsEarned(), d.getRewardPointsBalance(), creditLimit, null,
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
                Boolean.TRUE.equals(account.getNotificationsMuted()),
                statement.getCreatedAt(),
                d.getLastNotifiedKind(),
                d.getLastNotifiedOn(),
                digest,
                null,
                null);
        return new Built(bill, cutoff, creditsSince);
    }

    /**
     * The last day the statement bills ({@link BalanceMath#cutoff}): its period end, or the date of
     * its latest linked line when that comes earlier (an issuer cutting by posting date lists
     * nothing dated on the period end itself). Null for a statement without a period end. The
     * credits that pay the bill, the unbilled spend and the account balance all count from the day
     * after it, so the bill and GET /accounts owe the same money.
     */
    @Nullable
    private LocalDate lastBilledDay(Statement statement) {
        LocalDate periodEnd = statement.getPeriodEnd();
        return BalanceMath.cutoff(periodEnd,
                periodEnd == null ? null : statementRepository.findLastLineDate(statement.getId()));
    }

    /**
     * {@link #build} plus the read-side extras: unbilled spend since the last billed day, the next
     * expected statement and the digest's live utilisation (balance now ÷ limit, see
     * {@link CardUtilization}) — the same figure GET /accounts reports for the card.
     */
    private CardBill buildEnriched(Account account, Statement statement, LocalDate today) {
        Built built = assemble(account, statement, today);
        BigDecimal unbilled = built.cutoff() == null
                ? null
                : unbilled(transactionRepository.sumDebitsAfter(account.getId(), built.cutoff()),
                        built.creditsSince(), statement.getCreditCardDetails().getTotalAmountDue());
        BigDecimal utilization = accountService.populateLiveBalance(account).getUtilizationPct();
        return built.bill().withUtilization(utilization).withUnbilled(unbilled, nextStatementExpectedOn(account.getId()));
    }

    /** An open card with no live statement: nothing to pay yet, only what the card owes so far. */
    private CardBill awaitingStatement(Account account) {
        // Nothing to count from, so the balance is the plain signed sum (credits positive) and the card
        // owes its negative part — the figure GET /accounts shows for an unanchored card.
        BigDecimal signedSum = transactionRepository.findTotalTransactionSumByAccountId(account.getId());
        BigDecimal owedSoFar = signedSum == null ? BigDecimal.ZERO : signedSum.negate().max(BigDecimal.ZERO);
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
                Boolean.TRUE.equals(account.getNotificationsMuted()),
                null,
                null,
                null,
                null,
                owedSoFar,
                nextStatementExpectedOn(account.getId()));
    }

    /**
     * What the card owes beyond the bill: the debits dated after the last billed day, less whatever
     * the credits since cover beyond the total due (a credit larger than the bill pays down the new
     * spend too, and a statement in credit — a negative total — counts its credit the same way).
     * Never negative. A null total (details missing) leaves the credits to the bill alone, and a
     * null sum (a mocked repository) counts as zero.
     */
    static BigDecimal unbilled(@Nullable BigDecimal debitsSince, BigDecimal creditsSince, @Nullable BigDecimal total) {
        BigDecimal debits = debitsSince == null ? BigDecimal.ZERO : debitsSince;
        BigDecimal beyondBill = total == null ? BigDecimal.ZERO : creditsSince.subtract(total).max(BigDecimal.ZERO);
        return debits.subtract(beyondBill).max(BigDecimal.ZERO);
    }

    /** A repository sum, never negative; null (a mocked repository) counts as zero. */
    private static BigDecimal nonNegative(@Nullable BigDecimal sum) {
        return sum == null ? BigDecimal.ZERO : sum.max(BigDecimal.ZERO);
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
            // A card statement's closing balance is its total due, and the account balance anchors on
            // it (StatementRepository.findEligibleAnchorStatements): a corrected total moves the owed too.
            statement.setClosingBalance(totalAmountDue);
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
