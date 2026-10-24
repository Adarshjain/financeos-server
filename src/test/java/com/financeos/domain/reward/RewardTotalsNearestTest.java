package com.financeos.domain.reward;

import com.financeos.domain.account.card.Card;
import com.financeos.domain.account.card.Cardholder;
import com.financeos.domain.account.card.CardholderRole;

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
import com.financeos.domain.transaction.link.LinkType;
import com.financeos.domain.transaction.link.TransactionLink;
import com.financeos.domain.transaction.link.TransactionLinkMember;
import com.financeos.domain.transaction.link.TransactionLinkRepository;
import com.financeos.domain.user.User;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/** totals() adds nearestCap, nextMilestone and lastMet per account from the same batched evaluation. */
@ExtendWith(MockitoExtension.class)
class RewardTotalsNearestTest {

    @Mock private RewardRuleRepository rewardRuleRepository;
    @Mock private RewardRuleService rewardRuleService;
    @Mock private RewardMilestoneRepository rewardMilestoneRepository;
    @Mock private RewardMilestoneService rewardMilestoneService;
    @Mock private TransactionRepository transactionRepository;
    @Mock private TransactionLinkRepository transactionLinkRepository;
    @Mock private StatementRepository statementRepository;
    @Mock private AccountRepository accountRepository;
    @Mock private CardholderRepository cardholderRepository;

    private RewardCalculationService service;
    private User user;
    private int txnSeq = 0;

    @BeforeEach
    void setUp() {
        ZoneId zone = ZoneId.of("Asia/Kolkata");
        AppTime.useClock(Clock.fixed(LocalDate.of(2026, 10, 19).atTime(12, 0).atZone(zone).toInstant(), zone));
        user = new User();
        user.setId(UUID.randomUUID());
        UserContext.setCurrentUserId(user.getId());
        lenient().when(statementRepository.findByAccountIdOrderByPeriodEndDescNullsLast(any())).thenReturn(List.of());
        lenient().when(statementRepository.findByAccountIdInOrderByPeriodEndDescNullsLast(any())).thenReturn(List.of());
        lenient().when(transactionLinkRepository.findLinkIdsByMemberTransactionIds(any())).thenReturn(List.of());
        lenient().when(cardholderRepository.findByAccountId(any())).thenReturn(List.of());
        lenient().when(cardholderRepository.findByAccountIdIn(any())).thenReturn(List.of());
        lenient().when(rewardMilestoneService.parseEligibility(any())).thenReturn(MilestoneEligibility.EMPTY);
        service = new RewardCalculationService(rewardRuleRepository, rewardRuleService, rewardMilestoneRepository,
                rewardMilestoneService, transactionRepository, transactionLinkRepository, statementRepository,
                accountRepository, cardholderRepository);
    }

    @AfterEach
    void tearDown() {
        UserContext.clear();
        AppTime.reset();
    }

    private Account card(String name) {
        Account a = new Account(name, AccountType.credit_card);
        a.setId(UUID.randomUUID());
        a.setUser(user);
        lenient().when(accountRepository.findById(a.getId())).thenReturn(Optional.of(a));
        return a;
    }

    private RewardRule twoPercent(Account card) {
        RewardRule r = new RewardRule();
        r.setId(UUID.randomUUID());
        r.setAccount(card);
        r.setName("2%");
        r.setPriority(100);
        r.setStacking(RuleStacking.EXCLUSIVE);
        r.setRewardType(RewardType.CASH);
        r.setAccrualType(AccrualType.PERCENT);
        r.setPercentRate(new BigDecimal("10.00"));
        r.setCounterScope(CounterScope.ACCOUNT);
        return r;
    }

    private RewardMilestone cashMilestone(Account card) {
        RewardMilestone m = new RewardMilestone();
        m.setId(UUID.randomUUID());
        m.setAccount(card);
        m.setName("Bonus");
        m.setWindowType(MilestoneWindow.CALENDAR_MONTH);
        m.setBasis(MilestoneBasis.SPEND);
        m.setThreshold(new BigDecimal("500"));
        m.setRewardType(RewardType.CASH);
        m.setPayoutType(MilestonePayoutType.CASH_VALUE);
        m.setPayoutValue(new BigDecimal("100"));
        m.setPayoutTiming(MilestonePayoutTiming.WINDOW_END);
        return m;
    }

    private Transaction spend(Account card, LocalDate date) {
        Transaction t = new Transaction();
        t.setId(new UUID(0L, ++txnSeq));
        t.setUser(user);
        t.setAccount(card);
        t.setDate(date);
        t.setAmount(new BigDecimal("1000.00"));
        t.setType(TransactionType.DEBIT);
        t.setSource(TransactionSource.manual);
        t.setDescription("Spend");
        return t;
    }


    private RewardRule capped(Account card, String name, String cap) {
        RewardRule r = twoPercent(card);
        r.setName(name);
        r.setPeriodCap(new BigDecimal(cap));
        r.setCapWindow(CapWindow.CALENDAR_MONTH);
        return r;
    }

    private void stub(List<Account> cards, List<RewardRule> rules, List<RewardMilestone> ms, List<Transaction> txns) {
        when(rewardRuleRepository.findByAccountIdInOrderByPriorityDesc(any())).thenReturn(rules);
        when(rewardMilestoneRepository.findByAccountIdInOrderByCreatedAtAsc(any())).thenReturn(ms);
        lenient().when(transactionRepository.findForRewardEvaluationIn(any(), any(), any())).thenReturn(txns);
        when(accountRepository.findByUserIdAndType(user.getId(), AccountType.credit_card)).thenReturn(cards);
    }

    private static final LocalDate OCT = LocalDate.of(2026, 10, 5);

    @Test
    void ownCapPartlyUsed_exhaustedWinsOverPartial_andSharedBucketCountsOnce() {
        Account a = card("A");
        Account b = card("B");
        Account c = card("C");
        Account none = card("None");
        RewardRule partly = capped(a, "Dining", "150");           // 100 of 150
        RewardRule exhausted = capped(b, "Fuel", "50");           // 50 of 50
        RewardRule exhaustedB2 = capped(b, "Groceries", "1000");  // lower ratio
        RewardRule uncapped = twoPercent(none);
        RewardCapBucket bucket = new RewardCapBucket();
        bucket.setId(UUID.randomUUID());
        bucket.setName("Shared 200");
        bucket.setCap(new BigDecimal("200"));
        bucket.setWindowType(CapWindow.CALENDAR_MONTH);
        bucket.setRewardType(RewardType.CASH);
        RewardRule s1 = twoPercent(c);
        s1.setCapBucket(bucket);
        RewardRule s2 = twoPercent(c);
        s2.setName("Other");
        s2.setPriority(50);
        s2.setCapBucket(bucket);
        exhaustedB2.setPriority(50);
        stub(List.of(a, b, c, none), List.of(partly, exhausted, exhaustedB2, uncapped, s1, s2), List.of(),
                List.of(spend(a, OCT), spend(b, OCT), spend(c, OCT), spend(none, OCT)));

        List<RewardTotalResponse> t = service.totals();

        assertThat(t.get(0).nearestCap()).isEqualTo(new RewardTotalResponse.NearestCap(
                "Dining", new BigDecimal("100.00"), new BigDecimal("150"), "RUPEES", LocalDate.of(2026, 10, 31)));
        assertThat(t.get(1).nearestCap().label()).isEqualTo("Fuel");
        assertThat(t.get(1).nearestCap().used()).isEqualByComparingTo("50");
        assertThat(t.get(2).nearestCap().label()).isEqualTo("Shared 200");
        assertThat(t.get(2).nearestCap().used()).isEqualByComparingTo("100");
        assertThat(t.get(2).nearestCap().cap()).isEqualByComparingTo("200");
        assertThat(t.get(3).nearestCap()).isNull();
        assertThat(t).allSatisfy(x -> {
            assertThat(x.nextMilestone()).isNull();
            assertThat(x.lastMet()).isNull();
        });
        // Still one read of each kind for all four accounts.
        verify(rewardRuleRepository, times(1)).findByAccountIdInOrderByPriorityDesc(any());
        verify(rewardMilestoneRepository, times(1)).findByAccountIdInOrderByCreatedAtAsc(any());
        verify(transactionRepository, times(1)).findForRewardEvaluationIn(any(), any(), any());
        verify(rewardRuleRepository, never()).findByAccountIdOrderByPriorityDesc(any());
    }

    @Test
    void milestoneInProgress_met_andNone() {
        Account progress = card("Progress");
        Account met = card("Met");
        Account none = card("None");
        RewardMilestone inProgress = cashMilestone(progress);       // 500 monthly, 1000 spent -> met
        inProgress.setThreshold(new BigDecimal("5000"));            // not met: 1000 of 5000
        RewardMilestone achieved = cashMilestone(met);
        achieved.setName("Met bonus");                              // 1000 >= 500
        stub(List.of(progress, met, none), List.of(), List.of(inProgress, achieved),
                List.of(spend(progress, OCT), spend(met, OCT), spend(none, OCT)));

        List<RewardTotalResponse> t = service.totals();

        RewardTotalResponse.NextMilestone next = t.get(0).nextMilestone();
        assertThat(next.name()).isEqualTo("Bonus");
        assertThat(next.progress()).isEqualByComparingTo("1000");
        assertThat(next.threshold()).isEqualByComparingTo("5000");
        assertThat(next.basis()).isEqualTo(MilestoneBasis.SPEND);
        assertThat(next.windowEnd()).isEqualTo(LocalDate.of(2026, 10, 31));
        assertThat(t.get(0).lastMet()).isNull();
        // Monthly milestone: the October window is met, so the next unmet one is a later (empty) window.
        assertThat(t.get(1).lastMet()).isEqualTo(new RewardTotalResponse.LastMet("Met bonus", OCT));
        assertThat(t.get(1).nextMilestone().progress()).isEqualByComparingTo("0");
        assertThat(t.get(1).nextMilestone().windowEnd()).isEqualTo(LocalDate.of(2026, 11, 30));
        assertThat(t.get(2).nextMilestone()).isNull();
        assertThat(t.get(2).lastMet()).isNull();
    }

    @Test
    void otherUsersRuleAccount_isNeverIncluded() {
        Account mine = card("Mine");
        User other = new User();
        other.setId(UUID.randomUUID());
        Account foreign = new Account("Foreign", AccountType.bank_account);
        foreign.setId(UUID.randomUUID());
        foreign.setUser(other);
        RewardRule mineRule = capped(mine, "Mine cap", "150");
        when(rewardRuleRepository.findDistinctAccountIdsByUserId(user.getId())).thenReturn(List.of(foreign.getId()));
        when(accountRepository.findAllById(any())).thenReturn(List.of(foreign));
        stub(List.of(mine), List.of(mineRule), List.of(), List.of(spend(mine, OCT)));

        List<RewardTotalResponse> t = service.totals();

        assertThat(t).extracting(RewardTotalResponse::accountId).containsExactly(mine.getId());
    }

    private RewardMilestone milestone(Account card, String name, MilestoneWindow w, String threshold) {
        RewardMilestone m = cashMilestone(card);
        m.setName(name);
        m.setWindowType(w);
        m.setThreshold(new BigDecimal(threshold));
        return m;
    }

    private RewardMilestone oneTime(Account card, String name, LocalDate from, LocalDate toExclusive, String threshold) {
        RewardMilestone m = milestone(card, name, MilestoneWindow.ONE_TIME, threshold);
        m.setActiveFrom(from);
        m.setActiveTo(toExclusive);
        return m;
    }

    @Test
    void perCardholderCap_usesFullestCardholder() {
        Account a = card("A");
        Cardholder h1 = new Cardholder();
        h1.setId(UUID.randomUUID());
        h1.setAccount(a);
        h1.setPersonName("One");
        Cardholder h2 = new Cardholder();
        h2.setId(UUID.randomUUID());
        h2.setAccount(a);
        h2.setPersonName("Two");
        h2.setRole(CardholderRole.ADDON);
        RewardRule rule = capped(a, "Per head", "300");
        rule.setCounterScope(CounterScope.PER_CARDHOLDER);
        Transaction t1 = spend(a, OCT);
        t1.setCard(plastic(h1));
        Transaction t2 = spend(a, OCT.plusDays(1));
        t2.setCard(plastic(h2));
        Transaction t3 = spend(a, OCT.plusDays(2));
        t3.setCard(plastic(h2));
        when(cardholderRepository.findByAccountIdIn(any())).thenReturn(List.of(h1, h2));
        stub(List.of(a), List.of(rule), List.of(), List.of(t1, t2, t3));

        RewardTotalResponse.NearestCap cap = service.totals().get(0).nearestCap();

        assertThat(cap.used()).isEqualByComparingTo("200");
        assertThat(cap.cap()).isEqualByComparingTo("300");
    }

    private Card plastic(Cardholder h) {
        Card c = new Card();
        c.setCardholder(h);
        return c;
    }

    @Test
    void capRatioTie_soonestWindowEndWins() {
        Account a = card("A");
        RewardRule monthly = capped(a, "Monthly", "100");
        RewardRule yearly = capped(a, "Yearly", "100");
        yearly.setCapWindow(CapWindow.CALENDAR_YEAR);
        yearly.setPriority(50);
        monthly.setStacking(RuleStacking.ADDITIVE);
        yearly.setStacking(RuleStacking.ADDITIVE);
        stub(List.of(a), List.of(yearly, monthly), List.of(), List.of(spend(a, OCT)));

        RewardTotalResponse.NearestCap cap = service.totals().get(0).nearestCap();

        assertThat(cap.label()).isEqualTo("Monthly");
        assertThat(cap.windowEnd()).isEqualTo(LocalDate.of(2026, 10, 31));
    }

    @Test
    void milestoneRatioTie_soonestWindowEndWins() {
        Account a = card("A");
        RewardMilestone yearly = milestone(a, "Yearly", MilestoneWindow.CALENDAR_YEAR, "5000");
        RewardMilestone monthly = milestone(a, "Monthly", MilestoneWindow.CALENDAR_MONTH, "5000");
        stub(List.of(a), List.of(), List.of(yearly, monthly), List.of(spend(a, OCT)));

        RewardTotalResponse.NextMilestone next = service.totals().get(0).nextMilestone();

        // Year progress 1000/5000 and month progress 1000/5000 tie; the month ends first.
        assertThat(next.name()).isEqualTo("Monthly");
        assertThat(next.windowEnd()).isEqualTo(LocalDate.of(2026, 10, 31));
    }

    @Test
    void pointsCap_reportsPointsUnit() {
        Account a = card("A");
        RewardRule r = capped(a, "Points", "80");
        r.setRewardType(RewardType.POINTS);
        r.setAccrualType(AccrualType.SLAB);
        r.setPercentRate(null);
        r.setSlabSize(new BigDecimal("100"));
        r.setPointsPerSlab(new BigDecimal("5"));
        stub(List.of(a), List.of(r), List.of(), List.of(spend(a, OCT)));

        RewardTotalResponse.NearestCap cap = service.totals().get(0).nearestCap();

        assertThat(cap.unit()).isEqualTo("POINTS");
        assertThat(cap.used()).isEqualByComparingTo("50");
        assertThat(cap.cap()).isEqualByComparingTo("80");
    }

    @Test
    void capWindowStartingBeforeRewardYear_countsSpendFromTheWholeWindow() {
        Account a = card("A");
        a.setRewardAnniversaryDate(LocalDate.of(2024, 10, 10)); // reward year 2026-10-10..2027-10-09
        RewardRule rule = capped(a, "Monthly", "300");
        stub(List.of(a), List.of(rule), List.of(), List.of(spend(a, LocalDate.of(2026, 10, 5)),
                spend(a, LocalDate.of(2026, 10, 12))));

        RewardTotalResponse t = service.totals().get(0);

        assertThat(t.from()).isEqualTo(LocalDate.of(2026, 10, 10));
        assertThat(t.nearestCap().used()).isEqualByComparingTo("200");
        assertThat(t.nearestCap().windowEnd()).isEqualTo(LocalDate.of(2026, 10, 31));
    }

    @Test
    void ruleEndedBeforeToday_isExcluded() {
        Account a = card("A");
        RewardRule rule = capped(a, "Old", "150");
        rule.setActiveTo(LocalDate.of(2026, 9, 1));
        stub(List.of(a), List.of(rule), List.of(), List.of(spend(a, OCT)));

        assertThat(service.totals().get(0).nearestCap()).isNull();
    }

    @Test
    void expiredUnmetMilestone_isNotNext() {
        Account a = card("A");
        RewardMilestone expired = oneTime(a, "Expired", LocalDate.of(2026, 3, 1), LocalDate.of(2026, 5, 1), "5000");
        stub(List.of(a), List.of(), List.of(expired), List.of(spend(a, LocalDate.of(2026, 3, 10))));

        RewardTotalResponse t = service.totals().get(0);

        assertThat(t.nextMilestone()).isNull();
        assertThat(t.lastMet()).isNull();
    }

    @Test
    void oneTimeMilestoneAchievedBeforeRewardYear_isNotLastMet() {
        Account a = card("A");
        RewardMilestone old = oneTime(a, "Welcome", LocalDate.of(2025, 11, 1), LocalDate.of(2026, 2, 1), "500");
        RewardMilestone inYear = oneTime(a, "In year", LocalDate.of(2026, 3, 1), LocalDate.of(2026, 6, 1), "500");
        stub(List.of(a), List.of(), List.of(old, inYear), List.of(spend(a, LocalDate.of(2025, 12, 1)),
                spend(a, LocalDate.of(2026, 3, 10))));

        RewardTotalResponse.LastMet last = service.totals().get(0).lastMet();

        assertThat(last).isEqualTo(new RewardTotalResponse.LastMet("In year", LocalDate.of(2026, 3, 10)));
    }
}
