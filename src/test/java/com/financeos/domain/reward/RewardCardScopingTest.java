package com.financeos.domain.reward;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

import com.financeos.api.reward.dto.RewardLineResponse;
import com.financeos.api.reward.dto.RewardReportResponse;
import com.financeos.core.security.UserContext;
import com.financeos.domain.account.Account;
import com.financeos.domain.account.AccountRepository;
import com.financeos.domain.account.AccountType;
import com.financeos.domain.account.card.*;
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
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@ExtendWith(MockitoExtension.class)
class RewardCardScopingTest {

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
    private Account account;
    private Cardholder primaryCardholder;
    private Cardholder addonCardholder;
    private Card primaryCard;
    private Card addonCard;

    @BeforeEach
    void setUp() {
        UUID userId = UUID.randomUUID();
        user = new User();
        user.setId(userId);
        UserContext.setCurrentUserId(userId);

        account = new Account("Infinia", AccountType.credit_card);
        account.setId(UUID.randomUUID());
        account.setUser(user);
        account.setRewardAnniversaryDate(LocalDate.of(2025, 6, 1));

        primaryCardholder = new Cardholder();
        primaryCardholder.setId(UUID.randomUUID());
        primaryCardholder.setAccount(account);
        primaryCardholder.setRole(CardholderRole.PRIMARY);
        primaryCardholder.setPersonName("Primary Holder");
        primaryCardholder.setRelationship(CardholderRelationship.SELF);
        primaryCardholder.setOpenedOn(LocalDate.of(2025, 1, 1));

        primaryCard = new Card();
        primaryCard.setId(UUID.randomUUID());
        primaryCard.setAccount(account);
        primaryCard.setCardholder(primaryCardholder);
        primaryCard.setLast4("1234");
        primaryCard.setIssuedOn(LocalDate.of(2025, 1, 1));
        primaryCardholder.getCards().add(primaryCard);

        addonCardholder = new Cardholder();
        addonCardholder.setId(UUID.randomUUID());
        addonCardholder.setAccount(account);
        addonCardholder.setRole(CardholderRole.ADDON);
        addonCardholder.setPersonName("Wife");
        addonCardholder.setRelationship(CardholderRelationship.SPOUSE);
        addonCardholder.setOpenedOn(LocalDate.of(2025, 6, 1));

        addonCard = new Card();
        addonCard.setId(UUID.randomUUID());
        addonCard.setAccount(account);
        addonCard.setCardholder(addonCardholder);
        addonCard.setLast4("5678");
        addonCard.setIssuedOn(LocalDate.of(2025, 6, 1));
        addonCardholder.getCards().add(addonCard);

        lenient().when(accountRepository.findById(account.getId())).thenReturn(Optional.of(account));
        lenient().when(cardholderRepository.findByAccountId(account.getId()))
                .thenReturn(List.of(primaryCardholder, addonCardholder));
        lenient().when(statementRepository.findByAccountIdOrderByPeriodEndDescNullsLast(any())).thenReturn(List.of());
        lenient().when(transactionLinkRepository.findLinkIdsByMemberTransactionIds(any())).thenReturn(List.of());

        service = new RewardCalculationService(
                rewardRuleRepository, rewardRuleService, rewardMilestoneRepository, rewardMilestoneService,
                transactionRepository, transactionLinkRepository, statementRepository, accountRepository, cardholderRepository
        );
    }

    @AfterEach
    void tearDown() {
        UserContext.clear();
    }

    private int txnSeq = 0;

    private Transaction txn(BigDecimal amount, Card card, LocalDate date) {
        Transaction t = new Transaction();
        t.setId(new UUID(0L, ++txnSeq));
        t.setUser(user);
        t.setAccount(account);
        t.setCard(card);
        t.setDate(date);
        t.setAmount(amount);
        t.setType(TransactionType.DEBIT);
        t.setSource(TransactionSource.manual);
        t.setDescription("Test spend");
        return t;
    }

    private RewardRule rule(String name, Cardholder cardholder, BigDecimal rate, CounterScope counterScope) {
        RewardRule r = new RewardRule();
        r.setId(UUID.randomUUID());
        r.setAccount(account);
        r.setCardholder(cardholder);
        r.setName(name);
        r.setPriority(100);
        r.setStacking(RuleStacking.EXCLUSIVE);
        r.setRewardType(RewardType.CASH);
        r.setAccrualType(AccrualType.PERCENT);
        r.setPercentRate(rate);
        r.setCounterScope(counterScope);
        return r;
    }

    @Test
    void cardholderScopedRule_onlyMatchesTransactionsForThatCardholder() {
        RewardRule addonOnlyRule = rule("Add-on 5%", addonCardholder, new BigDecimal("5.00"), CounterScope.ACCOUNT);
        when(rewardRuleRepository.findByAccountIdOrderByPriorityDesc(account.getId()))
                .thenReturn(List.of(addonOnlyRule));

        LocalDate date = LocalDate.of(2026, 3, 15);
        Transaction t1 = txn(new BigDecimal("1000.00"), primaryCard, date);
        Transaction t2 = txn(new BigDecimal("2000.00"), addonCard, date);
        Transaction t3 = txn(new BigDecimal("1500.00"), null, date); // unattributed

        when(transactionRepository.findForRewardEvaluation(eq(account.getId()), any(), any()))
                .thenReturn(List.of(t1, t2, t3));

        List<RewardLineResponse> lines = service.lines(account.getId(), date, date, null);

        assertThat(lines).hasSize(3);
        RewardLineResponse l1 = lines.stream().filter(l -> l.transactionId().equals(t1.getId())).findFirst().get();
        RewardLineResponse l2 = lines.stream().filter(l -> l.transactionId().equals(t2.getId())).findFirst().get();
        RewardLineResponse l3 = lines.stream().filter(l -> l.transactionId().equals(t3.getId())).findFirst().get();

        assertThat(l2.reason()).isEqualTo(RewardLineReason.MATCHED);
        assertThat(l2.ruleName()).isEqualTo("Add-on 5%");
        assertThat(l2.earned()).isEqualByComparingTo("100.00");

        assertThat(l1.reason()).isEqualTo(RewardLineReason.NO_RULE);
        assertThat(l1.earned()).isEqualByComparingTo("0");

        assertThat(l3.reason()).isEqualTo(RewardLineReason.NO_RULE);
        assertThat(l3.earned()).isEqualByComparingTo("0");
    }

    @Test
    void accountScopedRule_matchesAllCardholdersAndUnattributed() {
        RewardRule accountRule = rule("General 2%", null, new BigDecimal("2.00"), CounterScope.ACCOUNT);
        when(rewardRuleRepository.findByAccountIdOrderByPriorityDesc(account.getId()))
                .thenReturn(List.of(accountRule));

        LocalDate date = LocalDate.of(2026, 3, 15);
        Transaction t1 = txn(new BigDecimal("1000.00"), primaryCard, date);
        Transaction t2 = txn(new BigDecimal("2000.00"), addonCard, date);
        Transaction t3 = txn(new BigDecimal("1500.00"), null, date);

        when(transactionRepository.findForRewardEvaluation(eq(account.getId()), any(), any()))
                .thenReturn(List.of(t1, t2, t3));

        List<RewardLineResponse> lines = service.lines(account.getId(), date, date, null);

        assertThat(lines).hasSize(3);
        assertThat(lines).allMatch(l -> l.reason() == RewardLineReason.MATCHED);
    }

    @Test
    void perCardholderCap_unattributedSpendChargesPrimaryCardholderCounter() {
        RewardRule perCardholderRule = rule("2% with ₹50 cap per cardholder", null, new BigDecimal("2.00"), CounterScope.PER_CARDHOLDER);
        perCardholderRule.setPeriodCap(new BigDecimal("50.00"));
        perCardholderRule.setCapWindow(CapWindow.CALENDAR_MONTH);
        perCardholderRule.setOnCapExhausted(CapExhaustedBehavior.FALL_THROUGH);

        when(rewardRuleRepository.findByAccountIdOrderByPriorityDesc(account.getId()))
                .thenReturn(List.of(perCardholderRule));

        LocalDate d1 = LocalDate.of(2026, 3, 10);
        LocalDate d2 = LocalDate.of(2026, 3, 11);
        LocalDate d3 = LocalDate.of(2026, 3, 12);

        Transaction tUnattributed = txn(new BigDecimal("2000.00"), null, d1);
        Transaction tPrimary = txn(new BigDecimal("1000.00"), primaryCard, d2);
        Transaction tAddon = txn(new BigDecimal("2000.00"), addonCard, d3);

        when(transactionRepository.findForRewardEvaluation(eq(account.getId()), any(), any()))
                .thenReturn(List.of(tUnattributed, tPrimary, tAddon));

        List<RewardLineResponse> lines = service.lines(account.getId(), d1, d3, null);

        assertThat(lines).hasSize(3);
        RewardLineResponse lUnattributed = lines.stream().filter(l -> l.transactionId().equals(tUnattributed.getId())).findFirst().get();
        RewardLineResponse lPrimary = lines.stream().filter(l -> l.transactionId().equals(tPrimary.getId())).findFirst().get();
        RewardLineResponse lAddon = lines.stream().filter(l -> l.transactionId().equals(tAddon.getId())).findFirst().get();

        assertThat(lUnattributed.earned()).isEqualByComparingTo("40.00");
        assertThat(lUnattributed.reason()).isEqualTo(RewardLineReason.MATCHED);

        assertThat(lPrimary.earned()).isEqualByComparingTo("10.00");
        assertThat(lPrimary.reason()).isEqualTo(RewardLineReason.PARTIAL_CAP);

        assertThat(lAddon.earned()).isEqualByComparingTo("40.00");
        assertThat(lAddon.reason()).isEqualTo(RewardLineReason.MATCHED);
    }

    @Test
    void test22_perCardholderTierProgress_partitionsCountersPerCardholder() {
        RewardRule tieredRule = rule("Tiered spend", null, null, CounterScope.PER_CARDHOLDER);
        tieredRule.setTierWindow(CapWindow.CALENDAR_MONTH);
        tieredRule.setTiers("[{\"upTo\":10000.00,\"rate\":1.0},{\"upTo\":null,\"rate\":3.0}]");

        when(rewardRuleRepository.findByAccountIdOrderByPriorityDesc(account.getId()))
                .thenReturn(List.of(tieredRule));
        when(rewardRuleService.parseTiers(tieredRule))
                .thenReturn(List.of(new RewardTier(new BigDecimal("10000.00"), new BigDecimal("1.0")),
                                    new RewardTier(null, new BigDecimal("3.0"))));

        LocalDate d = LocalDate.of(2026, 3, 15);
        Transaction tPrimary = txn(new BigDecimal("8000.00"), primaryCard, d);
        Transaction tAddon = txn(new BigDecimal("8000.00"), addonCard, d);

        when(transactionRepository.findForRewardEvaluation(eq(account.getId()), any(), any()))
                .thenReturn(List.of(tPrimary, tAddon));

        List<RewardLineResponse> lines = service.lines(account.getId(), d, d, null);

        assertThat(lines).hasSize(2);
        RewardLineResponse lPrimary = lines.stream().filter(l -> l.transactionId().equals(tPrimary.getId())).findFirst().get();
        RewardLineResponse lAddon = lines.stream().filter(l -> l.transactionId().equals(tAddon.getId())).findFirst().get();

        assertThat(lPrimary.earned()).isEqualByComparingTo("80.00");
        assertThat(lAddon.earned()).isEqualByComparingTo("80.00");
    }

    @Test
    void test23_anniversaryYearWindow_anchorsOnAccountAnniversaryDate() {
        RewardRule rule = rule("Account Anniversary Rule", null, new BigDecimal("1.00"), CounterScope.ACCOUNT);
        rule.setPeriodCap(new BigDecimal("1000.00"));
        rule.setCapWindow(CapWindow.ANNIVERSARY_YEAR);

        when(rewardRuleRepository.findByAccountIdOrderByPriorityDesc(account.getId()))
                .thenReturn(List.of(rule));

        LocalDate date = LocalDate.of(2026, 3, 15);
        // Account anniversaryDate is 2025-06-01 -> window for 2026-03-15 is 2025-06-01 .. 2026-05-31
        var report = service.report(account.getId(), date, date);

        var ruleReport = report.rules().get(0);
        assertThat(ruleReport.capStatus()).isNotNull();
        assertThat(ruleReport.capStatus().windowStart()).isEqualTo(LocalDate.of(2025, 6, 1));
        assertThat(ruleReport.capStatus().windowEnd()).isEqualTo(LocalDate.of(2026, 5, 31));
    }

    @Test
    void test24_cardholderScopedMilestone_onlyCountsMatchingCardholderSpend() {
        RewardMilestone milestone = new RewardMilestone();
        milestone.setId(UUID.randomUUID());
        milestone.setAccount(account);
        milestone.setCardholder(addonCardholder);
        milestone.setName("Addon ₹50k spend bonus");
        milestone.setWindowType(MilestoneWindow.CALENDAR_MONTH);
        milestone.setBasis(MilestoneBasis.SPEND);
        milestone.setThreshold(new BigDecimal("50000.00"));
        milestone.setRewardType(RewardType.CASH);
        milestone.setPayoutType(MilestonePayoutType.CASH_VALUE);
        milestone.setPayoutValue(new BigDecimal("1000.00"));
        milestone.setPayoutTiming(MilestonePayoutTiming.ON_ACHIEVEMENT);

        when(rewardMilestoneRepository.findByAccountIdOrderByCreatedAtAsc(account.getId()))
                .thenReturn(List.of(milestone));
        when(rewardMilestoneService.parseEligibility(milestone))
                .thenReturn(MilestoneEligibility.EMPTY);
        when(rewardRuleRepository.findByAccountIdOrderByPriorityDesc(account.getId()))
                .thenReturn(List.of());

        LocalDate d = LocalDate.of(2026, 3, 15);
        Transaction tPrimary = txn(new BigDecimal("40000.00"), primaryCard, d);
        Transaction tAddon = txn(new BigDecimal("30000.00"), addonCard, d);

        when(transactionRepository.findForRewardEvaluation(eq(account.getId()), any(), any()))
                .thenReturn(List.of(tPrimary, tAddon));

        var report = service.report(account.getId(), LocalDate.of(2026, 3, 1), LocalDate.of(2026, 3, 31));

        assertThat(report.milestones()).hasSize(1);
        var ms = report.milestones().get(0);
        assertThat(ms.progress()).isEqualByComparingTo("30000.00");
        assertThat(ms.achieved()).isFalse();
    }

    @Test
    void test28_sharedCapBucket_perCardholderScope_drainsPerCardholderAcrossRules() {
        RewardCapBucket bucket = new RewardCapBucket();
        bucket.setId(UUID.randomUUID());
        bucket.setName("Shared ₹100 Cap");
        bucket.setAccount(account);
        bucket.setUser(user);
        bucket.setCap(new BigDecimal("100.00"));
        bucket.setWindowType(CapWindow.CALENDAR_MONTH);
        bucket.setRewardType(RewardType.CASH);
        bucket.setCounterScope(CounterScope.PER_CARDHOLDER);

        RewardRule r1 = rule("Dining 5%", null, new BigDecimal("5.00"), CounterScope.ACCOUNT);
        r1.setCapBucket(bucket);
        RewardRule r2 = rule("Grocery 5%", null, new BigDecimal("5.00"), CounterScope.ACCOUNT);
        r2.setCapBucket(bucket);

        when(rewardRuleRepository.findByAccountIdOrderByPriorityDesc(account.getId()))
                .thenReturn(List.of(r1, r2));

        LocalDate d = LocalDate.of(2026, 3, 15);
        Transaction t1 = txn(new BigDecimal("2000.00"), primaryCard, d);
        Transaction t2 = txn(new BigDecimal("1000.00"), primaryCard, d);
        Transaction t3 = txn(new BigDecimal("1000.00"), addonCard, d);

        when(transactionRepository.findForRewardEvaluation(eq(account.getId()), any(), any()))
                .thenReturn(List.of(t1, t2, t3));

        List<RewardLineResponse> lines = service.lines(account.getId(), d, d, null);

        RewardLineResponse l1 = lines.stream().filter(l -> l.transactionId().equals(t1.getId())).findFirst().get();
        RewardLineResponse l2 = lines.stream().filter(l -> l.transactionId().equals(t2.getId())).findFirst().get();
        RewardLineResponse l3 = lines.stream().filter(l -> l.transactionId().equals(t3.getId())).findFirst().get();

        assertThat(l1.earned()).isEqualByComparingTo("100.00");
        assertThat(l2.earned()).isEqualByComparingTo("0.00");
        assertThat(l2.reason()).isEqualTo(RewardLineReason.CAP_EXHAUSTED);
        assertThat(l3.earned()).isEqualByComparingTo("50.00");
        assertThat(l3.reason()).isEqualTo(RewardLineReason.MATCHED);
    }

    @Test
    void testF3_reportCapStatus_perCardholderPopulatesPerCardUsageAndNullUsed() {
        RewardRule perCardholderRule = rule("Per-cardholder 2%", null, new BigDecimal("2.00"), CounterScope.PER_CARDHOLDER);
        perCardholderRule.setPeriodCap(new BigDecimal("100.00"));
        perCardholderRule.setCapWindow(CapWindow.CALENDAR_MONTH);

        when(rewardRuleRepository.findByAccountIdOrderByPriorityDesc(account.getId()))
                .thenReturn(List.of(perCardholderRule));

        LocalDate d = LocalDate.of(2026, 3, 15);
        Transaction tPrimary = txn(new BigDecimal("1000.00"), primaryCard, d);
        Transaction tAddon = txn(new BigDecimal("2000.00"), addonCard, d);

        when(transactionRepository.findForRewardEvaluation(eq(account.getId()), any(), any()))
                .thenReturn(List.of(tPrimary, tAddon));

        var report = service.report(account.getId(), d, d);

        var ruleReport = report.rules().get(0);
        assertThat(ruleReport.capStatus()).isNotNull();
        assertThat(ruleReport.capStatus().counterScope()).isEqualTo(CounterScope.PER_CARDHOLDER);
        assertThat(ruleReport.capStatus().used()).isNull();
        assertThat(ruleReport.capStatus().perCard()).hasSize(2);
        assertThat(report.byCard()).hasSize(2);
    }

    @Test
    void test30_byCardPartitionsReportExactly_withAndWithoutUnattributed() {
        RewardRule flat = rule("Flat 2%", null, new BigDecimal("2.00"), CounterScope.ACCOUNT);
        when(rewardRuleRepository.findByAccountIdOrderByPriorityDesc(account.getId()))
                .thenReturn(List.of(flat));

        LocalDate d = LocalDate.of(2026, 3, 15);
        Transaction tPrimary = txn(new BigDecimal("1000.00"), primaryCard, d);
        Transaction tAddon = txn(new BigDecimal("2000.00"), addonCard, d);
        Transaction tUnattributed = txn(new BigDecimal("500.00"), null, d);

        when(transactionRepository.findForRewardEvaluation(eq(account.getId()), any(), any()))
                .thenReturn(List.of(tPrimary, tAddon, tUnattributed));

        var report = service.report(account.getId(), d, d);

        assertThat(report.byCard()).hasSize(3);
        assertThat(report.byCard()).anyMatch(RewardReportResponse.CardBreakdown::unattributed);

        BigDecimal basisSum = report.byCard().stream()
                .map(RewardReportResponse.CardBreakdown::basisSpend)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal cashSum = report.byCard().stream()
                .map(RewardReportResponse.CardBreakdown::cashbackInr)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        int txnSum = report.byCard().stream()
                .mapToInt(RewardReportResponse.CardBreakdown::txnCount).sum();

        assertThat(basisSum).isEqualByComparingTo(report.summary().basisSpend());
        assertThat(cashSum).isEqualByComparingTo(report.summary().cashbackInr());
        assertThat(txnSum).isEqualTo(report.summary().transactionCount());
    }

    @Test
    void testF6_singleCardholderAccount_resolvesNullTransactionCardCleanly() {
        Account singleCardAccount = new Account("SingleCardholder", AccountType.credit_card);
        singleCardAccount.setId(UUID.randomUUID());
        singleCardAccount.setUser(user);

        Cardholder onlyCardholder = new Cardholder();
        onlyCardholder.setId(UUID.randomUUID());
        onlyCardholder.setAccount(singleCardAccount);
        onlyCardholder.setRole(CardholderRole.PRIMARY);
        onlyCardholder.setPersonName("Sole Holder");

        when(accountRepository.findById(singleCardAccount.getId())).thenReturn(Optional.of(singleCardAccount));
        when(cardholderRepository.findByAccountId(singleCardAccount.getId()))
                .thenReturn(List.of(onlyCardholder));

        RewardRule cardScopedRule = new RewardRule();
        cardScopedRule.setId(UUID.randomUUID());
        cardScopedRule.setAccount(singleCardAccount);
        cardScopedRule.setCardholder(onlyCardholder);
        cardScopedRule.setName("1-cardholder only rule");
        cardScopedRule.setPriority(100);
        cardScopedRule.setStacking(RuleStacking.EXCLUSIVE);
        cardScopedRule.setRewardType(RewardType.CASH);
        cardScopedRule.setAccrualType(AccrualType.PERCENT);
        cardScopedRule.setPercentRate(new BigDecimal("2.00"));
        cardScopedRule.setCounterScope(CounterScope.ACCOUNT);

        when(rewardRuleRepository.findByAccountIdOrderByPriorityDesc(singleCardAccount.getId()))
                .thenReturn(List.of(cardScopedRule));

        LocalDate d = LocalDate.of(2026, 3, 15);
        Transaction tUnattributed = new Transaction();
        tUnattributed.setId(UUID.randomUUID());
        tUnattributed.setUser(user);
        tUnattributed.setAccount(singleCardAccount);
        tUnattributed.setCard(null);
        tUnattributed.setDate(d);
        tUnattributed.setAmount(new BigDecimal("1000.00"));
        tUnattributed.setType(TransactionType.DEBIT);
        tUnattributed.setSource(TransactionSource.manual);

        when(transactionRepository.findForRewardEvaluation(eq(singleCardAccount.getId()), any(), any()))
                .thenReturn(List.of(tUnattributed));

        var report = service.report(singleCardAccount.getId(), d, d);

        assertThat(report.summary().matchedCount()).isEqualTo(1);
        assertThat(report.summary().cashbackInr()).isEqualByComparingTo("20.00");
        assertThat(report.byCard()).isEmpty();
        assertThat(report.perCardAttributionIncomplete()).isEqualTo(0);
    }
}
