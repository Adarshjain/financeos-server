package com.financeos.domain.account;

import com.financeos.api.account.dto.AccountFlowSummaryResponse;
import com.financeos.api.account.dto.AccountGmailSummaryResponse;
import com.financeos.api.account.dto.AccountPaymentsFromResponse;
import com.financeos.api.account.dto.AccountTabCountsResponse;
import com.financeos.api.account.dto.BalancePointResponse;
import com.financeos.core.exception.ResourceNotFoundException;
import com.financeos.core.exception.ValidationException;
import com.financeos.core.security.UserContext;
import com.financeos.core.time.AppTime;
import com.financeos.domain.account.card.CardholderRepository;
import com.financeos.domain.loan.Loan;
import com.financeos.domain.loan.LoanChargeRepository;
import com.financeos.domain.loan.LoanEventRepository;
import com.financeos.domain.loan.LoanPaymentRepository;
import com.financeos.domain.loan.LoanRepository;
import com.financeos.domain.loan.LoanStatus;
import com.financeos.domain.loan.schedule.LoanScheduleService;
import com.financeos.domain.loan.schedule.ScheduleResult;
import com.financeos.domain.notification.gmail.GmailAttentionNotificationService;
import com.financeos.domain.statement.StatementRepository;
import com.financeos.domain.transaction.Transaction;
import com.financeos.domain.transaction.TransactionRepository;
import com.financeos.domain.transaction.TransactionSource;
import com.financeos.domain.transaction.TransactionType;
import com.financeos.domain.transaction.link.TransactionLinkRepository;
import com.financeos.gmail.domain.GmailProcessedMessageRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * The small per-account reads behind the account page: tab counts, money in/out, what the account
 * pays, and the Gmail block. Every read is scoped to the current user's own account (404 otherwise).
 */
@Service
@Transactional(readOnly = true)
public class AccountInsightsService {

    public static final int DEFAULT_FLOW_DAYS = 90;
    public static final int MIN_FLOW_DAYS = 1;
    public static final int MAX_FLOW_DAYS = 366;
    private static final int TOP_IN = 2;
    private static final int TOP_OUT = 3;
    /** How far back a card bill payment still counts as "paid from this account". */
    private static final int CARD_BILL_MONTHS = 12;

    private final AccountRepository accountRepository;
    private final TransactionRepository transactionRepository;
    private final StatementRepository statementRepository;
    private final CardholderRepository cardholderRepository;
    private final AccountBalanceSeriesService balanceSeriesService;
    private final LoanRepository loanRepository;
    private final LoanEventRepository loanEventRepository;
    private final LoanPaymentRepository loanPaymentRepository;
    private final LoanChargeRepository loanChargeRepository;
    private final LoanScheduleService loanScheduleService;
    private final TransactionLinkRepository transactionLinkRepository;
    private final GmailProcessedMessageRepository processedMessageRepository;

    public AccountInsightsService(AccountRepository accountRepository,
                                  TransactionRepository transactionRepository,
                                  StatementRepository statementRepository,
                                  CardholderRepository cardholderRepository,
                                  AccountBalanceSeriesService balanceSeriesService,
                                  LoanRepository loanRepository,
                                  LoanEventRepository loanEventRepository,
                                  LoanPaymentRepository loanPaymentRepository,
                                  LoanChargeRepository loanChargeRepository,
                                  LoanScheduleService loanScheduleService,
                                  TransactionLinkRepository transactionLinkRepository,
                                  GmailProcessedMessageRepository processedMessageRepository) {
        this.accountRepository = accountRepository;
        this.transactionRepository = transactionRepository;
        this.statementRepository = statementRepository;
        this.cardholderRepository = cardholderRepository;
        this.balanceSeriesService = balanceSeriesService;
        this.loanRepository = loanRepository;
        this.loanEventRepository = loanEventRepository;
        this.loanPaymentRepository = loanPaymentRepository;
        this.loanChargeRepository = loanChargeRepository;
        this.loanScheduleService = loanScheduleService;
        this.transactionLinkRepository = transactionLinkRepository;
        this.processedMessageRepository = processedMessageRepository;
    }

    public AccountTabCountsResponse tabCounts(UUID accountId) {
        Account account = requireOwned(accountId);
        long cards = account.getType() == AccountType.bank_account || account.getType() == AccountType.credit_card
                ? cardholderRepository.countByAccountIdAndClosedOnIsNull(accountId)
                : 0;
        return new AccountTabCountsResponse(
                statementRepository.countByAccount_Id(accountId),
                cards,
                transactionRepository.countAllByAccountId(accountId));
    }

    public AccountFlowSummaryResponse flowSummary(UUID accountId, int days) {
        if (days < MIN_FLOW_DAYS || days > MAX_FLOW_DAYS) {
            throw new ValidationException("days must be between " + MIN_FLOW_DAYS + " and " + MAX_FLOW_DAYS);
        }
        requireOwned(accountId);
        LocalDate to = AppTime.today();
        LocalDate from = to.minusDays(days - 1L);

        BigDecimal moneyIn = BigDecimal.ZERO;
        BigDecimal moneyOut = BigDecimal.ZERO;
        for (TransactionRepository.TypeTotalProjection row : transactionRepository.sumByTypeBetween(accountId, from, to)) {
            if (row.getTotal() == null) {
                continue;
            }
            if (row.getType() == TransactionType.CREDIT) {
                moneyIn = moneyIn.add(row.getTotal());
            } else {
                moneyOut = moneyOut.add(row.getTotal());
            }
        }

        // Current quarter starts on the first of its first month; the last completed one is the 3 months before.
        LocalDate currentQuarterStart = LocalDate.of(to.getYear(), ((to.getMonthValue() - 1) / 3) * 3 + 1, 1);
        LocalDate interestFrom = currentQuarterStart.minusMonths(3);
        LocalDate interestTo = currentQuarterStart.minusDays(1);

        return new AccountFlowSummaryResponse(
                from, to, moneyIn, moneyOut,
                topCategories(accountId, TransactionType.CREDIT, from, to, TOP_IN),
                topCategories(accountId, TransactionType.DEBIT, from, to, TOP_OUT),
                averageDailyBalance(accountId, days),
                transactionRepository.sumInterestCreditsBetween(accountId, interestFrom, interestTo),
                interestFrom, interestTo);
    }

    public AccountPaymentsFromResponse paymentsFrom(UUID accountId) {
        requireOwned(accountId);

        List<AccountPaymentsFromResponse.Emi> emis = loanRepository
                .findByPaymentAccount_IdAndStatus(accountId, LoanStatus.active).stream()
                .map(this::toEmi)
                .sorted(Comparator
                        .comparing(AccountPaymentsFromResponse.Emi::nextDueDate, Comparator.nullsLast(Comparator.naturalOrder()))
                        .thenComparing(AccountPaymentsFromResponse.Emi::name))
                .toList();

        // Rows come latest first, so the first row seen for a card is its latest payment.
        LocalDate since = AppTime.today().minusMonths(CARD_BILL_MONTHS);
        Map<UUID, AccountPaymentsFromResponse.CardBill> latestPerCard = new LinkedHashMap<>();
        for (TransactionLinkRepository.CardBillRow row : transactionLinkRepository.findCardBillPayments(accountId, since)) {
            latestPerCard.putIfAbsent(row.getCardAccountId(), new AccountPaymentsFromResponse.CardBill(
                    row.getCardAccountId(), row.getCardName(), row.getPaidOn(), row.getAmount()));
        }
        List<AccountPaymentsFromResponse.CardBill> cardBills = latestPerCard.values().stream()
                .sorted(Comparator.comparing(AccountPaymentsFromResponse.CardBill::lastPaidOn).reversed())
                .toList();

        return new AccountPaymentsFromResponse(emis, cardBills);
    }

    public AccountGmailSummaryResponse gmailSummary(UUID accountId) {
        requireOwned(accountId);
        AccountGmailSummaryResponse.LastAlert lastAlert = transactionRepository
                .findFirstByAccount_IdAndSourceOrderByDateDescCreatedAtDesc(accountId, TransactionSource.gmail_transaction_alert)
                .map(AccountInsightsService::toLastAlert)
                .orElse(null);
        // The same statuses, and archived exclusion, as GET /api/v1/gmail/attention with its defaults.
        long unmatched = processedMessageRepository.countAttentionItemsForAccount(
                UserContext.getCurrentUserId(), accountId, GmailAttentionNotificationService.ATTENTION_STATUSES);
        return new AccountGmailSummaryResponse(lastAlert, unmatched);
    }

    private Account requireOwned(UUID accountId) {
        return accountRepository.findByIdAndUser_Id(accountId, UserContext.getCurrentUserId())
                .orElseThrow(() -> new ResourceNotFoundException("Account", accountId));
    }

    /** Names of the {@code limit} biggest categories of one direction; ties by name. */
    private List<String> topCategories(UUID accountId, TransactionType type, LocalDate from, LocalDate to, int limit) {
        return transactionRepository.sumByCategoryBetween(accountId, type, from, to).stream()
                .filter(row -> row.getName() != null && row.getTotal() != null)
                .sorted(Comparator
                        .comparing(TransactionRepository.CategoryTotalProjection::getTotal).reversed()
                        .thenComparing(TransactionRepository.CategoryTotalProjection::getName))
                .limit(limit)
                .map(TransactionRepository.CategoryTotalProjection::getName)
                .toList();
    }

    /** Mean of the balance series' daily balances over the window (the series allows at most 365 days); null for an empty series. */
    private BigDecimal averageDailyBalance(UUID accountId, int days) {
        List<BalancePointResponse> series = balanceSeriesService.series(
                accountId, Math.min(days, AccountBalanceSeriesService.MAX_DAYS));
        if (series.isEmpty()) {
            return null;
        }
        BigDecimal sum = series.stream().map(BalancePointResponse::balance).reduce(BigDecimal.ZERO, BigDecimal::add);
        return sum.divide(BigDecimal.valueOf(series.size()), 2, RoundingMode.HALF_UP);
    }

    private AccountPaymentsFromResponse.Emi toEmi(Loan loan) {
        UUID loanId = loan.getId();
        ScheduleResult schedule = loanScheduleService.compute(loan,
                loanEventRepository.findByLoan_IdOrderByEffectiveDateAscCreatedAtAsc(loanId),
                loanPaymentRepository.findByLoan_IdOrderByInstallmentSeqAsc(loanId),
                loanChargeRepository.findByLoan_IdOrderByChargeDateAscCreatedAtAsc(loanId));
        return new AccountPaymentsFromResponse.Emi(loanId, loan.getName(), schedule.currentEmi(), schedule.nextDueDate());
    }

    private static AccountGmailSummaryResponse.LastAlert toLastAlert(Transaction t) {
        return new AccountGmailSummaryResponse.LastAlert(t.getId(), t.getDate(), t.getDescription(), t.getAmount(), t.getType());
    }
}
