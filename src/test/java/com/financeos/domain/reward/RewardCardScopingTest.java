package com.financeos.domain.reward;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.financeos.api.reward.dto.RewardLineResponse;
import com.financeos.api.reward.dto.RewardReportResponse;
import com.financeos.core.security.UserContext;
import com.financeos.domain.account.Account;
import com.financeos.domain.account.AccountRepository;
import com.financeos.domain.account.AccountType;
import com.financeos.domain.account.card.AccountCard;
import com.financeos.domain.account.card.AccountCardRepository;
import com.financeos.domain.statement.StatementRepository;
import com.financeos.domain.transaction.Transaction;
import com.financeos.domain.transaction.TransactionChannel;
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
    private AccountCardRepository cardRepository;

    private RewardCalculationService service;
    private User user;
    private Account account;
    private AccountCard primaryCard;
    private AccountCard addonCard;

    @BeforeEach
    void setUp() {
        UUID userId = UUID.randomUUID();
        user = new User();
        user.setId(userId);
        UserContext.setCurrentUserId(userId);

        account = new Account("Infinia", AccountType.credit_card);
        account.setId(UUID.randomUUID());
        account.setUser(user);

        primaryCard = new AccountCard();
        primaryCard.setId(UUID.randomUUID());
        primaryCard.setAccount(account);
        primaryCard.setPrimary(true);
        primaryCard.setLast4("1234");
        primaryCard.setIssuedOn(LocalDate.of(2025, 1, 1));

        addonCard = new AccountCard();
        addonCard.setId(UUID.randomUUID());
        addonCard.setAccount(account);
        addonCard.setPrimary(false);
        addonCard.setLast4("5678");
        addonCard.setIssuedOn(LocalDate.of(2025, 6, 1));

        lenient().when(accountRepository.findById(account.getId())).thenReturn(Optional.of(account));
        lenient().when(cardRepository.findOpenByAccountId(account.getId())).thenReturn(List.of(primaryCard, addonCard));
        // The engine reads ALL cards (open + closed) so closed-card spend still appears in byCard.
        lenient().when(cardRepository.findByAccountIdOrderByIsPrimaryDescCreatedAtAsc(account.getId()))
                .thenReturn(List.of(primaryCard, addonCard));
        lenient().when(statementRepository.findByAccountIdOrderByPeriodEndDescNullsLast(any())).thenReturn(List.of());
        lenient().when(transactionLinkRepository.findLinkIdsByMemberTransactionIds(any())).thenReturn(List.of());

        service = new RewardCalculationService(
                rewardRuleRepository, rewardRuleService, rewardMilestoneRepository, rewardMilestoneService,
                transactionRepository, transactionLinkRepository, statementRepository, accountRepository, cardRepository
        );
    }

    @AfterEach
    void tearDown() {
        UserContext.clear();
    }

    /**
     * Monotonic so ids sort in creation order. The engine sorts same-date transactions by
     * {@code id.toString()}, so random ids made cap-drain order — and therefore which
     * transaction earns and which hits CAP_EXHAUSTED — depend on chance. That made test28
     * pass or fail at random rather than deterministically.
     */
    private int txnSeq = 0;

    private Transaction txn(BigDecimal amount, AccountCard card, LocalDate date) {
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

    private RewardRule rule(String name, AccountCard card, BigDecimal rate, CounterScope counterScope) {
        RewardRule r = new RewardRule();
        r.setId(UUID.randomUUID());
        r.setAccount(account);
        r.setCard(card);
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
    void cardScopedRule_onlyMatchesTransactionsForThatCard() {
        RewardRule addonOnlyRule = rule("Add-on 5%", addonCard, new BigDecimal("5.00"), CounterScope.ACCOUNT);
        when(rewardRuleRepository.findByAccountIdOrderByPriorityDesc(account.getId()))
                .thenReturn(List.of(addonOnlyRule));

        LocalDate date = LocalDate.of(2026, 3, 15);
        Transaction t1 = txn(new BigDecimal("1000.00"), primaryCard, date);
        Transaction t2 = txn(new BigDecimal("2000.00"), addonCard, date);
        Transaction t3 = txn(new BigDecimal("1500.00"), null, date); // unattributed

        when(transactionRepository.findForRewardEvaluation(eq(account.getId()), any(), any()))
                .thenReturn(List.of(t1, t2, t3));

        List<RewardLineResponse> lines = service.lines(account.getId(), date, date, null);

        // All 3 transactions appear in lines
        assertThat(lines).hasSize(3);
        RewardLineResponse l1 = lines.stream().filter(l -> l.transactionId().equals(t1.getId())).findFirst().get();
        RewardLineResponse l2 = lines.stream().filter(l -> l.transactionId().equals(t2.getId())).findFirst().get();
        RewardLineResponse l3 = lines.stream().filter(l -> l.transactionId().equals(t3.getId())).findFirst().get();

        // Only t2 matched the add-on rule; t1 (primary) and t3 (unattributed) get NO_RULE
        assertThat(l2.reason()).isEqualTo(RewardLineReason.MATCHED);
        assertThat(l2.ruleName()).isEqualTo("Add-on 5%");
        assertThat(l2.earned()).isEqualByComparingTo("100.00");

        assertThat(l1.reason()).isEqualTo(RewardLineReason.NO_RULE);
        assertThat(l1.earned()).isEqualByComparingTo("0");

        assertThat(l3.reason()).isEqualTo(RewardLineReason.NO_RULE);
        assertThat(l3.earned()).isEqualByComparingTo("0");
    }

    @Test
    void accountScopedRule_matchesAllCardsAndUnattributed() {
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
    void perCardCap_unattributedSpendChargesPrimaryCardCounter() {
        // Rule with PER_CARD counter scope and a ₹50 max cap per month
        RewardRule perCardRule = rule("2% with ₹50 cap per card", null, new BigDecimal("2.00"), CounterScope.PER_CARD);
        perCardRule.setPeriodCap(new BigDecimal("50.00"));
        perCardRule.setCapWindow(CapWindow.CALENDAR_MONTH);
        perCardRule.setOnCapExhausted(CapExhaustedBehavior.FALL_THROUGH);

        when(rewardRuleRepository.findByAccountIdOrderByPriorityDesc(account.getId()))
                .thenReturn(List.of(perCardRule));

        LocalDate d1 = LocalDate.of(2026, 3, 10);
        LocalDate d2 = LocalDate.of(2026, 3, 11);
        LocalDate d3 = LocalDate.of(2026, 3, 12);

        // Day 1: ₹2000 unattributed -> ₹40 reward -> uses 40 of 50 on primary card counter
        Transaction tUnattributed = txn(new BigDecimal("2000.00"), null, d1);
        // Day 2: ₹1000 on primary card -> ₹20 raw reward -> clamped to remaining ₹10 on primary card counter
        Transaction tPrimary = txn(new BigDecimal("1000.00"), primaryCard, d2);
        // Day 3: ₹2000 on addon card -> separate counter -> gets full ₹40 reward (uses 40 of 50 on add-on counter)
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

        // Primary card received clamp from the shared primary counter with unattributed spend
        assertThat(lPrimary.earned()).isEqualByComparingTo("10.00");
        assertThat(lPrimary.reason()).isEqualTo(RewardLineReason.PARTIAL_CAP);

        // Add-on card has its own clean ₹50 cap headroom
        assertThat(lAddon.earned()).isEqualByComparingTo("40.00");
        assertThat(lAddon.reason()).isEqualTo(RewardLineReason.MATCHED);
    }

    @Test
    void test22_perCardTierProgress_partitionsCountersPerCard() {
        RewardRule tieredRule = rule("Tiered spend", null, null, CounterScope.PER_CARD);
        tieredRule.setTierWindow(CapWindow.CALENDAR_MONTH);
        tieredRule.setTiers("[{\"upTo\":10000.00,\"rate\":1.0},{\"upTo\":null,\"rate\":3.0}]");

        when(rewardRuleRepository.findByAccountIdOrderByPriorityDesc(account.getId()))
                .thenReturn(List.of(tieredRule));
        when(rewardRuleService.parseTiers(tieredRule))
                .thenReturn(List.of(new RewardTier(new BigDecimal("10000.00"), new BigDecimal("1.0")),
                                    new RewardTier(null, new BigDecimal("3.0"))));

        LocalDate d = LocalDate.of(2026, 3, 15);
        // Primary spends 8000, Addon spends 8000 -> Neither crosses 10000 tier alone
        Transaction tPrimary = txn(new BigDecimal("8000.00"), primaryCard, d);
        Transaction tAddon = txn(new BigDecimal("8000.00"), addonCard, d);

        when(transactionRepository.findForRewardEvaluation(eq(account.getId()), any(), any()))
                .thenReturn(List.of(tPrimary, tAddon));

        List<RewardLineResponse> lines = service.lines(account.getId(), d, d, null);

        assertThat(lines).hasSize(2);
        RewardLineResponse lPrimary = lines.stream().filter(l -> l.transactionId().equals(tPrimary.getId())).findFirst().get();
        RewardLineResponse lAddon = lines.stream().filter(l -> l.transactionId().equals(tAddon.getId())).findFirst().get();

        // Both earn 1% = ₹80.00, proving tier counters are separate per card
        assertThat(lPrimary.earned()).isEqualByComparingTo("80.00");
        assertThat(lAddon.earned()).isEqualByComparingTo("80.00");
    }

    @Test
    void test23_anniversaryYearWindow_scopedToRuleCardIssuedOn() {
        RewardRule addonRule = rule("Addon Anniversary Rule", addonCard, new BigDecimal("1.00"), CounterScope.ACCOUNT);
        addonRule.setPeriodCap(new BigDecimal("1000.00"));
        addonRule.setCapWindow(CapWindow.ANNIVERSARY_YEAR);

        when(rewardRuleRepository.findByAccountIdOrderByPriorityDesc(account.getId()))
                .thenReturn(List.of(addonRule));

        LocalDate date = LocalDate.of(2026, 3, 15);
        // Addon card issuedOn is 2025-06-01 -> window for 2026-03-15 is 2025-06-01 .. 2026-05-31
        var report = service.report(account.getId(), date, date);

        var ruleReport = report.rules().get(0);
        assertThat(ruleReport.capStatus()).isNotNull();
        assertThat(ruleReport.capStatus().windowStart()).isEqualTo(LocalDate.of(2025, 6, 1));
        assertThat(ruleReport.capStatus().windowEnd()).isEqualTo(LocalDate.of(2026, 5, 31));
    }

    @Test
    void test24_cardScopedMilestone_onlyCountsMatchingCardSpend() {
        RewardMilestone milestone = new RewardMilestone();
        milestone.setId(UUID.randomUUID());
        milestone.setAccount(account);
        milestone.setCard(addonCard);
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
        // Only addon spend counted (30000), milestone not achieved
        assertThat(ms.progress()).isEqualByComparingTo("30000.00");
        assertThat(ms.achieved()).isFalse();
    }

    @Test
    void test28_sharedCapBucket_perCardScope_drainsPerCardAcrossRules() {
        RewardCapBucket bucket = new RewardCapBucket();
        bucket.setId(UUID.randomUUID());
        bucket.setName("Shared ₹100 Cap");
        bucket.setAccount(account);
        bucket.setUser(user);
        bucket.setCap(new BigDecimal("100.00"));
        bucket.setWindowType(CapWindow.CALENDAR_MONTH);
        bucket.setRewardType(RewardType.CASH);
        bucket.setCounterScope(CounterScope.PER_CARD);

        RewardRule r1 = rule("Dining 5%", null, new BigDecimal("5.00"), CounterScope.ACCOUNT);
        r1.setCapBucket(bucket);
        RewardRule r2 = rule("Grocery 5%", null, new BigDecimal("5.00"), CounterScope.ACCOUNT);
        r2.setCapBucket(bucket);

        when(rewardRuleRepository.findByAccountIdOrderByPriorityDesc(account.getId()))
                .thenReturn(List.of(r1, r2));

        LocalDate d = LocalDate.of(2026, 3, 15);
        // Primary spends ₹2000 on Dining -> ₹100 reward drains primary card bucket
        Transaction t1 = txn(new BigDecimal("2000.00"), primaryCard, d);
        // Primary spends ₹1000 on Grocery -> capped at ₹0
        Transaction t2 = txn(new BigDecimal("1000.00"), primaryCard, d);
        // Addon spends ₹1000 on Grocery -> separate counter in same bucket -> gets ₹50 reward
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
    void testF3_reportCapStatus_perCardPopulatesPerCardUsageAndNullUsed() {
        RewardRule perCardRule = rule("Per-card 2%", null, new BigDecimal("2.00"), CounterScope.PER_CARD);
        perCardRule.setPeriodCap(new BigDecimal("100.00"));
        perCardRule.setCapWindow(CapWindow.CALENDAR_MONTH);

        when(rewardRuleRepository.findByAccountIdOrderByPriorityDesc(account.getId()))
                .thenReturn(List.of(perCardRule));

        LocalDate d = LocalDate.of(2026, 3, 15);
        Transaction tPrimary = txn(new BigDecimal("1000.00"), primaryCard, d);
        Transaction tAddon = txn(new BigDecimal("2000.00"), addonCard, d);

        when(transactionRepository.findForRewardEvaluation(eq(account.getId()), any(), any()))
                .thenReturn(List.of(tPrimary, tAddon));

        var report = service.report(account.getId(), d, d);

        var ruleReport = report.rules().get(0);
        assertThat(ruleReport.capStatus()).isNotNull();
        assertThat(ruleReport.capStatus().counterScope()).isEqualTo(CounterScope.PER_CARD);
        assertThat(ruleReport.capStatus().used()).isNull();
        assertThat(ruleReport.capStatus().perCard()).hasSize(2);
        assertThat(report.byCard()).hasSize(2);
    }

    /**
     * The partition invariant: byCard slices the SAME report, so its parts must add back up
     * to the summary. Without this, a card silently missing from the breakdown looks like a
     * cosmetic gap rather than money vanishing from the view.
     */
    @Test
    void test30_byCardPartitionsReportExactly_withAndWithoutUnattributed() {
        RewardRule flat = rule("Flat 2%", null, new BigDecimal("2.00"), CounterScope.ACCOUNT);
        when(rewardRuleRepository.findByAccountIdOrderByPriorityDesc(account.getId()))
                .thenReturn(List.of(flat));

        LocalDate d = LocalDate.of(2026, 3, 15);
        Transaction tPrimary = txn(new BigDecimal("1000.00"), primaryCard, d);
        Transaction tAddon = txn(new BigDecimal("2000.00"), addonCard, d);
        Transaction tUnattributed = txn(new BigDecimal("500.00"), null, d); // statement-sourced

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

    /**
     * A card closed mid-period still owns its transactions. Iterating only OPEN cards dropped
     * that spend from byCard without putting it in Unattributed either — money disappearing
     * from the breakdown while the summary still counted it.
     */
    @Test
    void test30b_closedCardSpendStillAppearsInByCardAndPartitionHolds() {
        addonCard.setClosedOn(LocalDate.of(2026, 3, 20));
        when(cardRepository.findByAccountIdOrderByIsPrimaryDescCreatedAtAsc(account.getId()))
                .thenReturn(List.of(primaryCard, addonCard));

        RewardRule flat = rule("Flat 2%", null, new BigDecimal("2.00"), CounterScope.ACCOUNT);
        when(rewardRuleRepository.findByAccountIdOrderByPriorityDesc(account.getId()))
                .thenReturn(List.of(flat));

        LocalDate d = LocalDate.of(2026, 3, 15); // before closure
        Transaction tPrimary = txn(new BigDecimal("1000.00"), primaryCard, d);
        Transaction tClosed = txn(new BigDecimal("2000.00"), addonCard, d);

        when(transactionRepository.findForRewardEvaluation(eq(account.getId()), any(), any()))
                .thenReturn(List.of(tPrimary, tClosed));

        var report = service.report(account.getId(), d, d);

        // The closed card is still a card: the account stays "multi-card" and gets a row.
        assertThat(report.byCard()).hasSize(2);
        assertThat(report.byCard())
                .anyMatch(c -> addonCard.getId().equals(c.cardId())
                        && c.basisSpend().compareTo(new BigDecimal("2000.00")) == 0);

        BigDecimal basisSum = report.byCard().stream()
                .map(RewardReportResponse.CardBreakdown::basisSpend)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        assertThat(basisSum).isEqualByComparingTo(report.summary().basisSpend());
    }

    /**
     * The flag is a count of TRANSACTIONS, not of internal checks. capKey alone runs twice per
     * rule (clamp + consumeCap), so a counter would report several "transactions" for one.
     */
    @Test
    void testF5_perCardAttributionIncomplete_countsDistinctTransactionsNotChecks() {
        RewardRule perCardA = rule("Per-card A", null, new BigDecimal("2.00"), CounterScope.PER_CARD);
        perCardA.setPeriodCap(new BigDecimal("500.00"));
        perCardA.setCapWindow(CapWindow.CALENDAR_MONTH);
        perCardA.setStacking(RuleStacking.ADDITIVE);
        RewardRule perCardB = rule("Per-card B", null, new BigDecimal("1.00"), CounterScope.PER_CARD);
        perCardB.setPeriodCap(new BigDecimal("500.00"));
        perCardB.setCapWindow(CapWindow.CALENDAR_MONTH);
        perCardB.setStacking(RuleStacking.ADDITIVE);

        when(rewardRuleRepository.findByAccountIdOrderByPriorityDesc(account.getId()))
                .thenReturn(List.of(perCardA, perCardB));

        LocalDate d = LocalDate.of(2026, 3, 15);
        Transaction unattributed = txn(new BigDecimal("1000.00"), null, d);

        when(transactionRepository.findForRewardEvaluation(eq(account.getId()), any(), any()))
                .thenReturn(List.of(unattributed));

        var report = service.report(account.getId(), d, d);

        // One transaction, two rules, capKey hit twice per rule -> must still report 1.
        assertThat(report.perCardAttributionIncomplete()).isEqualTo(1);
    }

    @Test
    void testF6_singleCardAccount_resolvesNullTransactionCardCleanly() {
        // Account with only 1 card
        Account singleCardAccount = new Account("SingleCard", AccountType.credit_card);
        singleCardAccount.setId(UUID.randomUUID());
        singleCardAccount.setUser(user);

        AccountCard onlyCard = new AccountCard();
        onlyCard.setId(UUID.randomUUID());
        onlyCard.setAccount(singleCardAccount);
        onlyCard.setPrimary(true);
        onlyCard.setLast4("9999");

        when(accountRepository.findById(singleCardAccount.getId())).thenReturn(Optional.of(singleCardAccount));
        when(cardRepository.findByAccountIdOrderByIsPrimaryDescCreatedAtAsc(singleCardAccount.getId()))
                .thenReturn(List.of(onlyCard));

        RewardRule cardScopedRule = new RewardRule();
        cardScopedRule.setId(UUID.randomUUID());
        cardScopedRule.setAccount(singleCardAccount);
        cardScopedRule.setCard(onlyCard);
        cardScopedRule.setName("1-card only rule");
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
        tUnattributed.setCard(null); // Unattributed on single-card account
        tUnattributed.setDate(d);
        tUnattributed.setAmount(new BigDecimal("1000.00"));
        tUnattributed.setType(TransactionType.DEBIT);
        tUnattributed.setSource(TransactionSource.manual);

        when(transactionRepository.findForRewardEvaluation(eq(singleCardAccount.getId()), any(), any()))
                .thenReturn(List.of(tUnattributed));

        var report = service.report(singleCardAccount.getId(), d, d);

        // On single-card account, card-scoped rule matches unattributed spend
        assertThat(report.summary().matchedCount()).isEqualTo(1);
        assertThat(report.summary().cashbackInr()).isEqualByComparingTo("20.00");
        // byCard is empty for single-card account
        assertThat(report.byCard()).isEmpty();
        // perCardAttributionIncomplete is 0
        assertThat(report.perCardAttributionIncomplete()).isEqualTo(0);
    }

    @Test
    void test30_f10_ruleValidation_rejectsPerCardOnSingleCardAccount() {
        Account singleCardAccount = new Account("SingleCard", AccountType.credit_card);
        singleCardAccount.setId(UUID.randomUUID());
        singleCardAccount.setUser(user);

        when(accountRepository.findById(singleCardAccount.getId())).thenReturn(Optional.of(singleCardAccount));
        when(cardRepository.findOpenByAccountId(singleCardAccount.getId())).thenReturn(List.of(primaryCard)); // only 1 card

        com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper();
        RewardRuleService realRuleService = new RewardRuleService(
                rewardRuleRepository, mock(RewardCapBucketRepository.class), accountRepository,
                mock(com.financeos.domain.category.CategoryRepository.class), mock(com.financeos.domain.user.UserRepository.class),
                mapper, cardRepository
        );

        com.financeos.api.reward.dto.RewardRuleRequest request = new com.financeos.api.reward.dto.RewardRuleRequest(
                singleCardAccount.getId(), null, "PER_CARD", "Per card 2%", 100, "EXCLUSIVE",
                null, null, null, null, null, null, null, null, null, null,
                "INCLUDE", "INCLUDE", "INCLUDE",
                "CASH", "PERCENT", new BigDecimal("2.00"), "NONE", null, null, null, null, null,
                null, null, null, null, null
        );

        assertThatThrownBy(() -> realRuleService.create(request))
                .isInstanceOf(com.financeos.core.exception.ValidationException.class)
                .hasMessageContaining("Per-card counter scope requires an account with at least two open cards");
    }

    @Test
    void test30_f10_capBucketValidation_rejectsPerCardOnSingleCardAccount() {
        Account singleCardAccount = new Account("SingleCard", AccountType.credit_card);
        singleCardAccount.setId(UUID.randomUUID());
        singleCardAccount.setUser(user);

        when(accountRepository.findById(singleCardAccount.getId())).thenReturn(Optional.of(singleCardAccount));
        when(cardRepository.findOpenByAccountId(singleCardAccount.getId())).thenReturn(List.of(primaryCard)); // only 1 card

        RewardCapBucketService bucketService = new RewardCapBucketService(
                mock(RewardCapBucketRepository.class), rewardRuleRepository, accountRepository,
                mock(com.financeos.domain.user.UserRepository.class), cardRepository
        );

        com.financeos.api.reward.dto.RewardCapBucketRequest request = new com.financeos.api.reward.dto.RewardCapBucketRequest(
                singleCardAccount.getId(), "Shared Cap", new BigDecimal("100.00"), "CASH", "CALENDAR_MONTH", CounterScope.PER_CARD
        );

        assertThatThrownBy(() -> bucketService.create(request))
                .isInstanceOf(com.financeos.core.exception.ValidationException.class)
                .hasMessageContaining("Per-card counter scope requires an account with at least two open cards");
    }
}
