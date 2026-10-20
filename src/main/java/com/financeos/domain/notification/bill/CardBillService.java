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
import com.financeos.domain.account.CreditLimitPool;
import com.financeos.domain.account.cycle.BillingCycles;
import com.financeos.domain.statement.MissingDetails;
import com.financeos.domain.statement.Statement;
import com.financeos.domain.statement.StatementCreditCardDetails;
import com.financeos.domain.statement.StatementRepository;
import com.financeos.domain.statement.StatementVerdict;
import com.financeos.domain.transaction.TransactionRepository;
import com.financeos.domain.transaction.TransactionRepositoryCustom;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;
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
        return listBills(userId, null);
    }

    /**
     * {@link #listBills(UUID)} narrowed to one card: only that card's statements and sums are read.
     * Its limit pool's other members (closed ones included) are loaded for balances only, as pool
     * usage needs them. Empty when the card is missing, not the user's, not a credit card or closed.
     */
    public List<CardBill> listBills(UUID userId, @Nullable UUID accountId) {
        LocalDate today = AppTime.today();
        List<Account> cards;
        if (accountId == null) {
            cards = accountRepository.findByUserIdAndType(userId, AccountType.credit_card);
        } else {
            Account card = accountRepository.findById(accountId)
                    .filter(a -> a.getType() == AccountType.credit_card
                            && a.getUser() != null && a.getUser().getId().equals(userId))
                    .orElse(null);
            if (card == null || !isOpen(card, today)) {
                return List.of();
            }
            CreditLimitPool pool = CardUtilization.limitPool(card);
            cards = new ArrayList<>(List.of(card));
            if (pool != null) {
                accountRepository.findLimitPoolMembers(pool.getId(), userId).stream()
                        .filter(m -> !m.getId().equals(card.getId()))
                        .forEach(cards::add);
            }
        }
        final UUID onlyId = accountId;
        List<Account> open = cards.stream()
                .filter(account -> onlyId == null || onlyId.equals(account.getId()))
                .filter(account -> isOpen(account, today)).toList();
        Batch batch = loadBatch(cards, open);
        List<CardBill> bills = new ArrayList<>();
        for (Account account : open) {
            Statement live = batch.liveStatements().get(account.getId());
            bills.add(live != null ? buildEnriched(account, live, today, batch) : awaitingStatement(account, batch));
        }
        bills.sort(Comparator
                .comparingInt((CardBill b) -> urgency(b.status()))
                .thenComparing(CardBill::paymentDueDate, Comparator.nullsLast(Comparator.naturalOrder()))
                .thenComparing(CardBill::accountName, Comparator.nullsLast(String.CASE_INSENSITIVE_ORDER)));
        return bills;
    }

    public CardBill findByStatementId(UUID userId, UUID statementId) {
        Statement statement = loadOwnedCardStatement(userId, statementId);
        return buildEnriched(statement.getAccount(), statement, AppTime.today(), null);
    }

    /**
     * One card's bills, a bill per statement (every statement {@link #findByStatementId} accepts: credit
     * card type with card details, rejected ones included), newest statement date first (period end, else created date), each
     * equal to {@code findByStatementId}. {@code limit} keeps the newest n; {@code from}/{@code to} bound
     * the statement date inclusively; all null means every
     * statement. The account, its pool, statements, line dates and credit/debit sums are read once.
     * Unknown account: not found; someone else's or not a credit card: validation error.
     */
    public List<CardBill> historyForAccount(UUID userId, UUID accountId, @Nullable Integer limit,
                                            @Nullable LocalDate from, @Nullable LocalDate to) {
        if (limit != null && limit < 1) {
            throw new ValidationException("limit must be at least 1");
        }
        if (from != null && to != null && from.isAfter(to)) {
            throw new ValidationException("from must not be after to");
        }
        Account account = accountRepository.findById(accountId)
                .orElseThrow(() -> new ResourceNotFoundException("Account", accountId));
        if (userId != null && (account.getUser() == null || !account.getUser().getId().equals(userId))) {
            throw new ValidationException("You do not have permission to access this account.");
        }
        if (account.getType() != AccountType.credit_card) {
            throw new ValidationException("Only credit card accounts carry bills.");
        }
        LocalDate today = AppTime.today();
        List<Statement> all = statementRepository.findByAccountIdInOrderByPeriodEndDescNullsLast(List.of(accountId));
        List<Statement> ordered = new ArrayList<>(all);
        ordered.sort(Comparator.comparing(CardBillService::statementDate, Comparator.nullsLast(Comparator.<LocalDate>reverseOrder()))
                .thenComparing(Statement::getCreatedAt, Comparator.nullsLast(Comparator.<java.time.Instant>reverseOrder())));
        List<Statement> picked = new ArrayList<>();
        for (Statement s : ordered) {
            if (!"credit_card".equals(s.getStatementType()) || s.getCreditCardDetails() == null) {
                continue;
            }
            LocalDate end = statementDate(s);
            if ((from != null || to != null) && end == null) {
                continue;
            }
            if ((from != null && end.isBefore(from)) || (to != null && end.isAfter(to))) {
                continue;
            }
            picked.add(s);
            if (limit != null && picked.size() >= limit) {
                break;
            }
        }
        if (picked.isEmpty()) {
            return List.of();
        }
        accountService.populateLiveBalance(account);
        Map<UUID, LocalDate> lastLines = new HashMap<>();
        for (StatementRepository.StatementLastLine row : statementRepository.findLastLineDates(
                picked.stream().map(Statement::getId).toList())) {
            lastLines.put(row.getStatementId(), row.getLastLineDate());
        }
        Map<UUID, LocalDate> cutoffs = new HashMap<>();
        for (Statement s : picked) {
            LocalDate cutoff = BalanceMath.cutoff(s.getPeriodEnd(), lastLines.get(s.getId()));
            if (cutoff != null) {
                cutoffs.put(s.getId(), cutoff);
            }
        }
        Map<UUID, TransactionRepositoryCustom.SumsAfter> sums = new HashMap<>();
        if (!cutoffs.isEmpty()) {
            List<TransactionRepositoryCustom.DaySums> days = transactionRepository.sumsByDayAfter(
                    accountId, cutoffs.values().stream().min(Comparator.naturalOrder()).orElseThrow());
            cutoffs.forEach((statementId, cutoff) -> {
                BigDecimal credits = BigDecimal.ZERO;
                BigDecimal debits = BigDecimal.ZERO;
                for (TransactionRepositoryCustom.DaySums d : days) {
                    if (d.date().isAfter(cutoff)) {
                        credits = credits.add(d.credits());
                        debits = debits.add(d.debits());
                    }
                }
                sums.put(statementId, new TransactionRepositoryCustom.SumsAfter(credits, debits));
            });
        }
        Batch batch = new Batch(Map.of(), lastLines, Map.of(), Map.of(accountId, all), Map.of(), sums);
        List<CardBill> bills = new ArrayList<>();
        for (Statement s : picked) {
            bills.add(buildEnriched(account, s, today, batch));
        }
        return bills;
    }

    /** A statement's date: its period end, else the day it was created in the app zone; null when neither exists. */
    private static @Nullable LocalDate statementDate(Statement s) {
        if (s.getPeriodEnd() != null) {
            return s.getPeriodEnd();
        }
        return s.getCreatedAt() == null ? null : s.getCreatedAt().atZone(AppTime.zone()).toLocalDate();
    }

    /**
     * The newest non-rejected credit-card statement with card details: the current bill.
     * Latest by period end; statements without a period end only win when nothing else exists.
     */
    public Optional<Statement> latestLiveStatement(UUID accountId) {
        return Optional.ofNullable(latestLive(statementRepository.findQualifyingCreditCardStatements(accountId)));
    }

    @Nullable
    private static Statement latestLive(List<Statement> statements) {
        Statement best = null;
        for (Statement s : statements) {
            if (s.getCreditCardDetails() == null) {
                continue;
            }
            if (best == null || laterThan(s, best)) {
                best = s;
            }
        }
        return best;
    }

    /**
     * What {@link #listBills} reads for all its cards at once instead of card by card (each read is a
     * remote round trip): live statements, their last line dates, the credits and debits after each
     * cutoff, every statement (for the next expected one) and the awaiting cards' signed sums. The
     * cards' balances and utilisation are populated in one batch too ({@code AccountService#populateBalances}).
     * Null where a method runs for a single bill, which reads each figure as it needs it.
     */
    private record Batch(Map<UUID, Statement> liveStatements,
                         Map<UUID, LocalDate> lastLineDates,
                         Map<UUID, TransactionRepositoryCustom.SumsAfter> sumsAfter,
                         Map<UUID, List<Statement>> statementsByAccount,
                         Map<UUID, BigDecimal> signedSums,
                         Map<UUID, TransactionRepositoryCustom.SumsAfter> sumsByStatement) {
    }

    private Batch loadBatch(List<Account> allCards, List<Account> openCards) {
        if (openCards.isEmpty()) {
            return new Batch(Map.of(), Map.of(), Map.of(), Map.of(), Map.of(), Map.of());
        }
        // Pool usage needs every member's balance, closed cards included.
        accountService.populateBalances(allCards);
        List<UUID> openIds = openCards.stream().map(Account::getId).toList();
        Map<UUID, Statement> live = new HashMap<>();
        statementRepository.findQualifyingCreditCardStatementsIn(openIds).stream()
                .collect(Collectors.groupingBy(s -> s.getAccount().getId()))
                .forEach((accountId, statements) -> {
                    Statement best = latestLive(statements);
                    if (best != null) {
                        live.put(accountId, best);
                    }
                });
        Map<UUID, LocalDate> lastLineByStatement = new HashMap<>();
        if (!live.isEmpty()) {
            for (StatementRepository.StatementLastLine row : statementRepository.findLastLineDates(
                    live.values().stream().map(Statement::getId).toList())) {
                lastLineByStatement.put(row.getStatementId(), row.getLastLineDate());
            }
        }
        Map<UUID, LocalDate> cutoffs = new HashMap<>();
        live.forEach((accountId, statement) -> {
            LocalDate cutoff = BalanceMath.cutoff(statement.getPeriodEnd(), lastLineByStatement.get(statement.getId()));
            if (cutoff != null) {
                cutoffs.put(accountId, cutoff);
            }
        });
        Map<UUID, List<Statement>> statementsByAccount = new HashMap<>();
        statementRepository.findByAccountIdInOrderByPeriodEndDescNullsLast(openIds)
                .forEach(s -> statementsByAccount.computeIfAbsent(s.getAccount().getId(), k -> new ArrayList<>()).add(s));
        List<UUID> awaitingIds = openIds.stream().filter(id -> !live.containsKey(id)).toList();
        Map<UUID, BigDecimal> signedSums = new HashMap<>();
        if (!awaitingIds.isEmpty()) {
            for (TransactionRepository.AccountSumProjection row
                    : transactionRepository.findTotalTransactionSumsByAccountIds(awaitingIds)) {
                signedSums.put(row.getAccountId(), row.getTotal());
            }
        }
        return new Batch(live, lastLineByStatement, transactionRepository.sumsAfterCutoffs(cutoffs),
                statementsByAccount, signedSums, Map.of());
    }

    /**
     * The bill as the statement and the credits since describe it. {@code unbilledAmount},
     * {@code nextStatementExpectedOn} and the digest's live {@code utilizationPct} are left null
     * here: the notification tick does not need them and must not pay for the extra queries.
     * {@link #listBills} and the other read paths fill them in. Settlement reads two things beyond
     * the statement: the date of its latest line ({@link #lastBilledDay}) and the credit sum after it.
     */
    public CardBill build(Account account, Statement statement, LocalDate today) {
        return assemble(account, statement, today, null).bill();
    }

    /**
     * {@code cutoff} is the statement's last billed day ({@link #lastBilledDay}; null without a period
     * end) and {@code creditsSince} the credits dated after it (zero without a cutoff).
     */
    private record Built(CardBill bill, @Nullable LocalDate cutoff, BigDecimal creditsSince) {
    }

    private Built assemble(Account account, Statement statement, LocalDate today, @Nullable Batch batch) {
        StatementCreditCardDetails d = statement.getCreditCardDetails();
        BigDecimal total = d.getTotalAmountDue();
        LocalDate due = d.getPaymentDueDate();

        LocalDate cutoff = batch != null
                ? BalanceMath.cutoff(statement.getPeriodEnd(), batch.lastLineDates().get(statement.getId()))
                : lastBilledDay(statement);
        BigDecimal creditsSince = cutoff == null
                ? BigDecimal.ZERO
                : nonNegative(batch != null
                        ? sumsOf(account, statement, batch).credits()
                        : transactionRepository.sumCreditsAfter(account.getId(), cutoff));

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
        BigDecimal creditLimit = batch != null
                ? account.getEffectiveCreditLimit() // set by the batch balance population, same rule
                : CardUtilization.creditLimit(account, statementRepository);
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
                null,
                MissingDetails.of(statement));
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
    private CardBill buildEnriched(Account account, Statement statement, LocalDate today, @Nullable Batch batch) {
        Built built = assemble(account, statement, today, batch);
        BigDecimal unbilled = built.cutoff() == null
                ? null
                : unbilled(batch != null
                                ? sumsOf(account, statement, batch).debits()
                                : transactionRepository.sumDebitsAfter(account.getId(), built.cutoff()),
                        built.creditsSince(), statement.getCreditCardDetails().getTotalAmountDue());
        BigDecimal utilization = (batch != null ? account : accountService.populateLiveBalance(account)).getUtilizationPct();
        return built.bill().withUtilization(utilization)
                .withUnbilled(unbilled, nextStatementExpectedOn(account.getId(), batch));
    }

    /** The sums after this statement's cutoff: its own entry when the batch holds one per statement, else the account's. */
    private static TransactionRepositoryCustom.SumsAfter sumsOf(Account account, Statement statement, Batch batch) {
        TransactionRepositoryCustom.SumsAfter own = batch.sumsByStatement().get(statement.getId());
        return own != null ? own : batch.sumsAfter().get(account.getId());
    }

    /** An open card with no live statement: nothing to pay yet, only what the card owes so far. */
    private CardBill awaitingStatement(Account account, Batch batch) {
        // Nothing to count from, so the balance is the plain signed sum (credits positive) and the card
        // owes its negative part — the figure GET /accounts shows for an unanchored card.
        BigDecimal signedSum = batch.signedSums().get(account.getId());
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
                nextStatementExpectedOn(account.getId(), batch));
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
    private LocalDate nextStatementExpectedOn(UUID accountId, @Nullable Batch batch) {
        List<Statement> statements = batch != null
                ? batch.statementsByAccount().get(accountId)
                : statementRepository.findByAccountIdOrderByPeriodEndDescNullsLast(accountId);
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
        return buildEnriched(statement.getAccount(), statement, today, null);
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
        return buildEnriched(statement.getAccount(), statement, today, null);
    }

    static final int MAX_PERIOD_DAYS = 400;
    static final int MAX_DUE_AFTER_PERIOD_END_DAYS = 60;

    /**
     * Fill in (or correct) what the parser missed. Resets the sequence so reminders re-evaluate against the new date.
     * The merged values (request over the stored ones) must make sense together; only the checks that involve a
     * field in the request run, so an unrelated edit is never blocked by an old oddity. Old statements are valid:
     * nothing is compared with today.
     */
    @Transactional
    public CardBill updateDetails(UUID userId, UUID statementId, LocalDate periodStart, LocalDate periodEnd,
                                  LocalDate paymentDueDate, BigDecimal totalAmountDue, BigDecimal minimumAmountDue) {
        if (periodStart == null && periodEnd == null && paymentDueDate == null && totalAmountDue == null
                && minimumAmountDue == null) {
            throw new ValidationException("Provide a period start, period end, due date, total due or minimum due");
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
        LocalDate start = periodStart != null ? periodStart : statement.getPeriodStart();
        LocalDate end = periodEnd != null ? periodEnd : statement.getPeriodEnd();
        LocalDate due = paymentDueDate != null ? paymentDueDate : d.getPaymentDueDate();
        BigDecimal total = totalAmountDue != null ? totalAmountDue : d.getTotalAmountDue();
        BigDecimal min = minimumAmountDue != null ? minimumAmountDue : d.getMinimumAmountDue();
        if ((periodStart != null || periodEnd != null) && start != null && end != null) {
            if (start.isAfter(end)) {
                throw new ValidationException("Period start must be on or before period end");
            }
            if (ChronoUnit.DAYS.between(start, end) > MAX_PERIOD_DAYS) {
                throw new ValidationException("Statement period cannot be longer than " + MAX_PERIOD_DAYS + " days");
            }
        }
        if ((paymentDueDate != null || periodEnd != null) && due != null && end != null) {
            long gap = ChronoUnit.DAYS.between(end, due);
            if (gap < 0 || gap > MAX_DUE_AFTER_PERIOD_END_DAYS) {
                throw new ValidationException("Due date must be 0 to " + MAX_DUE_AFTER_PERIOD_END_DAYS
                        + " days after the period end");
            }
        }
        if ((totalAmountDue != null || minimumAmountDue != null) && total != null && min != null
                && min.compareTo(total) > 0) {
            throw new ValidationException("Minimum due cannot exceed total due");
        }
        if (periodStart != null) {
            statement.setPeriodStart(periodStart);
        }
        if (periodEnd != null) {
            statement.setPeriodEnd(periodEnd);
        }
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
        return buildEnriched(statement.getAccount(), statement, today, null);
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
