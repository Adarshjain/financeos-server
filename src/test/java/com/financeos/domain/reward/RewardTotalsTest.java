package com.financeos.domain.reward;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

import com.financeos.api.reward.dto.RewardReportResponse;
import com.financeos.api.reward.dto.RewardTotalResponse;
import com.financeos.core.security.UserContext;
import com.financeos.core.time.AppTime;
import com.financeos.domain.account.Account;
import com.financeos.domain.account.AccountRepository;
import com.financeos.domain.account.AccountType;
import com.financeos.domain.account.card.CardholderRepository;
import com.financeos.domain.statement.StatementRepository;
import com.financeos.domain.transaction.Transaction;
import com.financeos.domain.transaction.TransactionRepository;
import com.financeos.domain.transaction.TransactionSource;
import com.financeos.domain.transaction.TransactionType;
import com.financeos.domain.transaction.link.TransactionLinkRepository;
import com.financeos.domain.user.User;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@ExtendWith(MockitoExtension.class)
class RewardTotalsTest {

    @Mock
    private RewardRuleRepository rewardRuleRepository;
    @Mock
    private RewardRuleService rewardRuleService;
    @Mock
    private RewardMilestoneRepository rewardMilestoneRepository;
    @Mock
    private RewardMilestoneService rewardMilestoneService;
    @Mock
    private TransactionRepository transactionRepository;
    @Mock
    private TransactionLinkRepository transactionLinkRepository;
    @Mock
    private StatementRepository statementRepository;
    @Mock
    private AccountRepository accountRepository;
    @Mock
    private CardholderRepository cardholderRepository;

    private RewardCalculationService service;
    private User user;
    private int txnSeq = 0;

    @SuppressWarnings("unchecked")
    @BeforeEach
    void setUp() {
        // Pin "today" to 2026-10-19 (Asia/Kolkata).
        ZoneId zone = ZoneId.of("Asia/Kolkata");
        AppTime.useClock(Clock.fixed(LocalDate.of(2026, 10, 19).atTime(12, 0).atZone(zone).toInstant(), zone));

        user = new User();
        user.setId(UUID.randomUUID());
        UserContext.setCurrentUserId(user.getId());

        lenient().when(statementRepository.findByAccountIdOrderByPeriodEndDescNullsLast(any())).thenReturn(List.of());
        lenient().when(transactionLinkRepository.findLinkIdsByMemberTransactionIds(any())).thenReturn(List.of());
        lenient().when(cardholderRepository.findByAccountId(any())).thenReturn(List.of());
        // totals() reads every card's inputs through batch methods; answer them from the per-card stubs below.
        lenient().when(rewardRuleRepository.findByAccountIdInOrderByPriorityDesc(any())).thenAnswer(inv -> {
            List<RewardRule> all = new java.util.ArrayList<>();
            for (UUID id : (java.util.Collection<UUID>) inv.getArgument(0)) {
                all.addAll(rewardRuleRepository.findByAccountIdOrderByPriorityDesc(id));
            }
            return all;
        });
        lenient().when(rewardMilestoneRepository.findByAccountIdInOrderByCreatedAtAsc(any())).thenReturn(List.of());
        lenient().when(cardholderRepository.findByAccountIdIn(any())).thenReturn(List.of());
        lenient().when(statementRepository.findByAccountIdInOrderByPeriodEndDescNullsLast(any())).thenReturn(List.of());
        lenient().when(transactionRepository.findForRewardEvaluationIn(any(), any(), any())).thenAnswer(inv -> {
            List<Transaction> all = new java.util.ArrayList<>();
            for (UUID id : (java.util.Collection<UUID>) inv.getArgument(0)) {
                all.addAll(transactionRepository.findForRewardEvaluation(id, inv.getArgument(1), inv.getArgument(2)));
            }
            return all;
        });

        service = new RewardCalculationService(
                rewardRuleRepository, rewardRuleService, rewardMilestoneRepository, rewardMilestoneService,
                transactionRepository, transactionLinkRepository, statementRepository, accountRepository, cardholderRepository
        );
    }

    @AfterEach
    void tearDown() {
        UserContext.clear();
        AppTime.reset();
    }

    private Account card(String name, LocalDate anniversary) {
        Account a = new Account(name, AccountType.credit_card);
        a.setId(UUID.randomUUID());
        a.setUser(user);
        a.setRewardAnniversaryDate(anniversary);
        lenient().when(accountRepository.findById(a.getId())).thenReturn(Optional.of(a));
        return a;
    }

    /** One 2% cash rule on the card and one 1000 spend on the given date. */
    private void spend(Account card, LocalDate date) {
        RewardRule r = new RewardRule();
        r.setId(UUID.randomUUID());
        r.setAccount(card);
        r.setName("2%");
        r.setPriority(100);
        r.setStacking(RuleStacking.EXCLUSIVE);
        r.setRewardType(RewardType.CASH);
        r.setAccrualType(AccrualType.PERCENT);
        r.setPercentRate(new BigDecimal("2.00"));
        r.setCounterScope(CounterScope.ACCOUNT);
        lenient().when(rewardRuleRepository.findByAccountIdOrderByPriorityDesc(card.getId())).thenReturn(List.of(r));

        Transaction t = new Transaction();
        t.setId(new UUID(0L, ++txnSeq));
        t.setUser(user);
        t.setAccount(card);
        t.setDate(date);
        t.setAmount(new BigDecimal("1000.00"));
        t.setType(TransactionType.DEBIT);
        t.setSource(TransactionSource.manual);
        t.setDescription("Spend");
        lenient().when(transactionRepository.findForRewardEvaluation(eq(card.getId()), any(), any()))
                .thenReturn(List.of(t));
    }

    private void cards(Account... accounts) {
        when(accountRepository.findByUserIdAndType(user.getId(), AccountType.credit_card))
                .thenReturn(List.of(accounts));
    }

    @Test
    void anniversaryAlreadyPassedThisYear_windowStartsAtThisYearsAnniversary() {
        Account c = card("Infinia", LocalDate.of(2024, 6, 1));
        cards(c);

        RewardTotalResponse t = service.totals().get(0);

        assertThat(t.accountId()).isEqualTo(c.getId());
        assertThat(t.from()).isEqualTo(LocalDate.of(2026, 6, 1));
        assertThat(t.to()).isEqualTo(LocalDate.of(2027, 5, 31));
        assertThat(t.calendarYearFallback()).isFalse();
    }

    @Test
    void anniversaryLaterThisYear_windowStartsLastYear() {
        Account c = card("Regalia", LocalDate.of(2024, 12, 15));
        cards(c);

        RewardTotalResponse t = service.totals().get(0);

        assertThat(t.from()).isEqualTo(LocalDate.of(2025, 12, 15));
        assertThat(t.to()).isEqualTo(LocalDate.of(2026, 12, 14));
        assertThat(t.calendarYearFallback()).isFalse();
    }

    @Test
    void anniversaryToday_windowStartsToday() {
        Account c = card("Today", LocalDate.of(2023, 10, 19));
        cards(c);

        RewardTotalResponse t = service.totals().get(0);

        assertThat(t.from()).isEqualTo(LocalDate.of(2026, 10, 19));
        assertThat(t.to()).isEqualTo(LocalDate.of(2027, 10, 18));
    }

    @Test
    void noAnniversary_usesCalendarYearAndFlagsFallback() {
        Account c = card("Plain", null);
        cards(c);

        RewardTotalResponse t = service.totals().get(0);

        assertThat(t.from()).isEqualTo(LocalDate.of(2026, 1, 1));
        assertThat(t.to()).isEqualTo(LocalDate.of(2026, 12, 31));
        assertThat(t.calendarYearFallback()).isTrue();
    }

    @Test
    void totalsEqualReportSummaryForSameWindow() {
        Account withAnniv = card("A", LocalDate.of(2024, 6, 1));
        Account noAnniv = card("B", null);
        spend(withAnniv, LocalDate.of(2026, 7, 10));
        spend(noAnniv, LocalDate.of(2026, 3, 10));
        cards(withAnniv, noAnniv);

        List<RewardTotalResponse> totals = service.totals();

        assertThat(totals).hasSize(2);
        for (RewardTotalResponse t : totals) {
            RewardReportResponse.Summary s = service.report(t.accountId(), t.from(), t.to()).summary();
            assertThat(t.points()).isEqualByComparingTo(s.points());
            assertThat(t.grossValueInr()).isEqualByComparingTo(s.grossValueInr());
        }
        assertThat(totals.get(0).grossValueInr()).isEqualByComparingTo("20.00");
        assertThat(totals.get(1).grossValueInr()).isEqualByComparingTo("20.00");
    }

    @Test
    void milestoneFiguresEqualReportSummary_andAreZeroWithoutMilestones() {
        Account plain = card("Plain", LocalDate.of(2024, 6, 1));
        cards(plain);
        RewardTotalResponse none = service.totals().get(0);
        assertThat(none.milestonePoints()).isEqualByComparingTo("0");
        assertThat(none.milestoneValueInr()).isEqualByComparingTo("0");

        Account c = card("Ms", LocalDate.of(2024, 6, 1));
        spend(c, LocalDate.of(2026, 7, 10));
        RewardMilestone m = new RewardMilestone();
        m.setId(UUID.randomUUID());
        m.setAccount(c);
        m.setName("Spend 500");
        m.setWindowType(MilestoneWindow.ONE_TIME);
        m.setActiveFrom(LocalDate.of(2026, 6, 1));
        m.setActiveTo(LocalDate.of(2026, 12, 1));
        m.setBasis(MilestoneBasis.SPEND);
        m.setThreshold(new BigDecimal("500.00"));
        m.setRewardType(RewardType.POINTS);
        m.setPayoutType(MilestonePayoutType.CASH_VALUE);
        m.setPayoutValue(new BigDecimal("250"));
        m.setPayoutTiming(MilestonePayoutTiming.ON_ACHIEVEMENT);
        lenient().when(rewardMilestoneRepository.findByAccountIdInOrderByCreatedAtAsc(any())).thenReturn(List.of(m));
        lenient().when(rewardMilestoneRepository.findByAccountIdOrderByCreatedAtAsc(c.getId())).thenReturn(List.of(m));
        lenient().when(rewardMilestoneService.parseEligibility(m)).thenReturn(MilestoneEligibility.EMPTY);
        cards(c);

        RewardTotalResponse t = service.totals().get(0);
        RewardReportResponse.Summary s = service.report(t.accountId(), t.from(), t.to()).summary();

        assertThat(s.milestonesPts()).isEqualByComparingTo("250");
        assertThat(t.milestonePoints()).isEqualByComparingTo(s.milestonesPts());
        assertThat(t.milestoneValueInr()).isEqualByComparingTo(s.milestonesInr());
    }

    @Test
    void spendOutsideTheAnniversaryWindow_isNotCounted() {
        Account c = card("A", LocalDate.of(2024, 6, 1));
        // The repository is asked only for the window (expanded for caps); an empty result models no spend in it.
        lenient().when(transactionRepository.findForRewardEvaluation(eq(c.getId()), any(), any())).thenReturn(List.of());
        cards(c);

        RewardTotalResponse t = service.totals().get(0);

        assertThat(t.points()).isEqualByComparingTo("0");
        assertThat(t.grossValueInr()).isEqualByComparingTo("0");
    }

    @Test
    void onlyCurrentUsersCreditCardsAreQueried() {
        Account c = card("Mine", null);
        cards(c);

        service.totals();

        verify(accountRepository).findByUserIdAndType(user.getId(), AccountType.credit_card);
        verify(accountRepository, never()).findAll();
        verify(accountRepository, never()).findByType(any());
    }

    @Test
    void noCards_returnsEmptyList() {
        when(accountRepository.findByUserIdAndType(user.getId(), AccountType.credit_card)).thenReturn(List.of());

        assertThat(service.totals()).isEmpty();
    }

    private Account other(String name, User owner) {
        Account a = new Account(name, AccountType.bank_account);
        a.setId(UUID.randomUUID());
        a.setUser(owner);
        return a;
    }

    @Test
    void pointValueInr_presentWhenConfigured_nullWhenNot() {
        Account withValue = card("A", null);
        withValue.setPointValueInr(new BigDecimal("0.50"));
        Account without = card("B", null);
        cards(withValue, without);

        List<RewardTotalResponse> totals = service.totals();

        assertThat(totals.get(0).pointValueInr()).isEqualByComparingTo("0.50");
        assertThat(totals.get(1).pointValueInr()).isNull();
    }

    @Test
    void nonCardAccountWithRules_isIncludedAfterCards_inOneBatchedQuery() {
        Account c = card("Card", null);
        Account bank = other("Bank", user);
        bank.setPointValueInr(new BigDecimal("1.00"));
        spend(bank, LocalDate.of(2026, 3, 10));
        cards(c);
        when(rewardRuleRepository.findDistinctAccountIdsByUserId(user.getId())).thenReturn(List.of(bank.getId()));
        when(accountRepository.findAllById(List.of(bank.getId()))).thenReturn(List.of(bank));

        List<RewardTotalResponse> totals = service.totals();

        assertThat(totals).extracting(RewardTotalResponse::accountId).containsExactly(c.getId(), bank.getId());
        assertThat(totals.get(1).calendarYearFallback()).isTrue();
        assertThat(totals.get(1).grossValueInr()).isEqualByComparingTo("20.00");
        assertThat(totals.get(1).pointValueInr()).isEqualByComparingTo("1.00");
        verify(rewardRuleRepository, times(1)).findDistinctAccountIdsByUserId(any());
        verify(accountRepository, times(1)).findAllById(any());
    }

    @Test
    void nonCardAccountWithoutRules_isExcluded() {
        Account c = card("Card", null);
        cards(c);
        when(rewardRuleRepository.findDistinctAccountIdsByUserId(user.getId())).thenReturn(List.of());

        assertThat(service.totals()).extracting(RewardTotalResponse::accountId).containsExactly(c.getId());
        verify(accountRepository, never()).findAllById(any());
    }

    @Test
    void anotherUsersAccount_isExcluded() {
        User stranger = new User();
        stranger.setId(UUID.randomUUID());
        Account theirs = other("Theirs", stranger);
        cards();
        when(rewardRuleRepository.findDistinctAccountIdsByUserId(user.getId())).thenReturn(List.of(theirs.getId()));
        when(accountRepository.findAllById(List.of(theirs.getId()))).thenReturn(List.of(theirs));

        assertThat(service.totals()).isEmpty();
    }
}
