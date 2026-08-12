package com.financeos.domain.reward;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.financeos.api.reward.dto.RewardCardRecommendationResponse;
import com.financeos.api.reward.dto.RewardRecommendationRequest;
import com.financeos.api.reward.dto.RewardRecommendationResponse;
import com.financeos.core.exception.ValidationException;
import com.financeos.core.security.UserContext;
import com.financeos.domain.account.Account;
import com.financeos.domain.account.AccountRepository;
import com.financeos.domain.account.AccountType;
import com.financeos.domain.category.CategoryRepository;
import com.financeos.domain.transaction.TransactionChannel;
import com.financeos.domain.transaction.TransactionRepository;
import com.financeos.domain.transaction.link.TransactionLinkRepository;
import com.financeos.domain.statement.StatementRepository;
import com.financeos.domain.user.User;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

class RewardRecommendationServiceTest {

    private AccountRepository accountRepository;
    private CategoryRepository categoryRepository;
    private RewardRuleRepository rewardRuleRepository;
    private RewardMilestoneRepository rewardMilestoneRepository;
    private RewardMilestoneService rewardMilestoneService;
    private RewardRuleService rewardRuleService;
    private TransactionRepository transactionRepository;
    private TransactionLinkRepository transactionLinkRepository;
    private StatementRepository statementRepository;

    private RewardCalculationService rewardCalculationService;
    private RewardRecommendationService recommendationService;

    private UUID userId;
    private User user;

    @BeforeEach
    void setUp() {
        userId = UUID.randomUUID();
        user = new User();
        user.setId(userId);
        UserContext.setCurrentUserId(userId);

        accountRepository = mock(AccountRepository.class);
        categoryRepository = mock(CategoryRepository.class);
        rewardRuleRepository = mock(RewardRuleRepository.class);
        rewardMilestoneRepository = mock(RewardMilestoneRepository.class);
        rewardMilestoneService = mock(RewardMilestoneService.class);
        rewardRuleService = mock(RewardRuleService.class);
        transactionRepository = mock(TransactionRepository.class);
        transactionLinkRepository = mock(TransactionLinkRepository.class);
        statementRepository = mock(StatementRepository.class);

        when(transactionRepository.findForRewardEvaluation(any(), any(), any())).thenReturn(List.of());

        rewardCalculationService = new RewardCalculationService(
                rewardRuleRepository, rewardRuleService, rewardMilestoneRepository, rewardMilestoneService,
                transactionRepository, transactionLinkRepository, statementRepository, accountRepository);

        recommendationService = new RewardRecommendationService(
                accountRepository, categoryRepository, rewardRuleRepository, rewardCalculationService);
    }

    @AfterEach
    void tearDown() {
        UserContext.clear();
    }

    private Account createCardAccount(String name, BigDecimal pointValueInr) {
        Account account = new Account(name, AccountType.credit_card);
        account.setId(UUID.randomUUID());
        account.setUser(user);
        account.setPointValueInr(pointValueInr);
        return account;
    }

    private RewardRule createPercentRule(Account account, String name, BigDecimal percentRate, RuleStacking stacking, int priority) {
        RewardRule rule = new RewardRule();
        rule.setId(UUID.randomUUID());
        rule.setAccount(account);
        rule.setName(name);
        rule.setPercentRate(percentRate);
        rule.setAccrualType(AccrualType.PERCENT);
        rule.setRewardType(RewardType.CASH);
        rule.setStacking(stacking);
        rule.setPriority(priority);
        return rule;
    }

    @Test
    void baseRanking_higherValueFirst_noRuleLast() {
        Account card2Pct = createCardAccount("Card 2%", null);
        Account card1Pct = createCardAccount("Card 1%", null);
        Account cardNoRule = createCardAccount("Card No Rules", null);

        when(accountRepository.findByUserId(userId)).thenReturn(List.of(card2Pct, card1Pct, cardNoRule));
        when(accountRepository.findById(card2Pct.getId())).thenReturn(Optional.of(card2Pct));
        when(accountRepository.findById(card1Pct.getId())).thenReturn(Optional.of(card1Pct));
        when(accountRepository.findById(cardNoRule.getId())).thenReturn(Optional.of(cardNoRule));

        RewardRule rule2Pct = createPercentRule(card2Pct, "2% Cash", new BigDecimal("2.0"), RuleStacking.EXCLUSIVE, 10);
        RewardRule rule1Pct = createPercentRule(card1Pct, "1% Cash", new BigDecimal("1.0"), RuleStacking.EXCLUSIVE, 10);

        when(rewardRuleRepository.findByAccountIdOrderByPriorityDesc(card2Pct.getId())).thenReturn(List.of(rule2Pct));
        when(rewardRuleRepository.findByAccountIdOrderByPriorityDesc(card1Pct.getId())).thenReturn(List.of(rule1Pct));
        when(rewardRuleRepository.findByAccountIdOrderByPriorityDesc(cardNoRule.getId())).thenReturn(List.of());
        when(rewardRuleRepository.countByAccountId(cardNoRule.getId())).thenReturn(0L);

        RewardRecommendationRequest req = new RewardRecommendationRequest(
                new BigDecimal("1000.00"), LocalDate.now(), null, null, null, null, false, false, null);

        RewardRecommendationResponse resp = recommendationService.recommend(req);

        assertEquals(3, resp.recommendations().size());
        assertEquals(card2Pct.getId(), resp.recommendations().get(0).accountId());
        assertEquals(1, resp.recommendations().get(0).rank());
        assertEquals(new BigDecimal("20.00"), resp.recommendations().get(0).totalValueInr());

        assertEquals(card1Pct.getId(), resp.recommendations().get(1).accountId());
        assertEquals(2, resp.recommendations().get(1).rank());
        assertEquals(new BigDecimal("10.00"), resp.recommendations().get(1).totalValueInr());

        assertEquals(cardNoRule.getId(), resp.recommendations().get(2).accountId());
        assertEquals(3, resp.recommendations().get(2).rank());
        assertEquals(BigDecimal.ZERO, resp.recommendations().get(2).totalValueInr());
        assertTrue(resp.recommendations().get(2).noRulesConfigured());
    }

    @Test
    void capFlip_partialCapAndCapExhausted() {
        Account cappedCard = createCardAccount("5% Capped Card", null);
        Account flatCard = createCardAccount("1.5% Flat Card", null);

        when(accountRepository.findByUserId(userId)).thenReturn(List.of(cappedCard, flatCard));
        when(accountRepository.findById(cappedCard.getId())).thenReturn(Optional.of(cappedCard));
        when(accountRepository.findById(flatCard.getId())).thenReturn(Optional.of(flatCard));

        RewardRule rule5PctCapped = createPercentRule(cappedCard, "5% Capped", new BigDecimal("5.0"), RuleStacking.EXCLUSIVE, 10);
        rule5PctCapped.setPeriodCap(new BigDecimal("10.00")); // Cap total = ₹10
        rule5PctCapped.setCapWindow(CapWindow.CALENDAR_MONTH);

        RewardRule rule15PctFlat = createPercentRule(flatCard, "1.5% Flat", new BigDecimal("1.5"), RuleStacking.EXCLUSIVE, 10);

        when(rewardRuleRepository.findByAccountIdOrderByPriorityDesc(cappedCard.getId())).thenReturn(List.of(rule5PctCapped));
        when(rewardRuleRepository.findByAccountIdOrderByPriorityDesc(flatCard.getId())).thenReturn(List.of(rule15PctFlat));

        RewardRecommendationRequest req = new RewardRecommendationRequest(
                new BigDecimal("1000.00"), LocalDate.now(), null, null, null, null, false, false, null);

        RewardRecommendationResponse resp = recommendationService.recommend(req);

        // 1.5% flat card earns ₹15.00, capped card earns raw ₹50 clamped to ₹10.00 cap -> flat card wins!
        assertEquals(flatCard.getId(), resp.recommendations().get(0).accountId());
        assertEquals(new BigDecimal("15.00"), resp.recommendations().get(0).totalValueInr());

        assertEquals(cappedCard.getId(), resp.recommendations().get(1).accountId());
        assertEquals(new BigDecimal("10.00"), resp.recommendations().get(1).totalValueInr());
        assertEquals(RewardLineReason.PARTIAL_CAP, resp.recommendations().get(1).ruleLines().get(0).reason());
    }

    @Test
    void pointsConversion_configVsDefault() {
        Account cardConfigured = createCardAccount("Card Configured", new BigDecimal("0.50"));
        Account cardDefault = createCardAccount("Card Default", null);

        when(accountRepository.findByUserId(userId)).thenReturn(List.of(cardConfigured, cardDefault));
        when(accountRepository.findById(cardConfigured.getId())).thenReturn(Optional.of(cardConfigured));
        when(accountRepository.findById(cardDefault.getId())).thenReturn(Optional.of(cardDefault));

        RewardRule pointsRuleConfig = new RewardRule();
        pointsRuleConfig.setId(UUID.randomUUID());
        pointsRuleConfig.setAccount(cardConfigured);
        pointsRuleConfig.setName("Points Rule Configured");
        pointsRuleConfig.setAccrualType(AccrualType.SLAB);
        pointsRuleConfig.setSlabSize(new BigDecimal("100"));
        pointsRuleConfig.setPointsPerSlab(new BigDecimal("10")); // 10 pts per 100
        pointsRuleConfig.setRewardType(RewardType.POINTS);
        pointsRuleConfig.setStacking(RuleStacking.EXCLUSIVE);
        pointsRuleConfig.setPriority(10);

        RewardRule pointsRuleDefault = new RewardRule();
        pointsRuleDefault.setId(UUID.randomUUID());
        pointsRuleDefault.setAccount(cardDefault);
        pointsRuleDefault.setName("Points Rule Default");
        pointsRuleDefault.setAccrualType(AccrualType.SLAB);
        pointsRuleDefault.setSlabSize(new BigDecimal("100"));
        pointsRuleDefault.setPointsPerSlab(new BigDecimal("10")); // 10 pts per 100
        pointsRuleDefault.setRewardType(RewardType.POINTS);
        pointsRuleDefault.setStacking(RuleStacking.EXCLUSIVE);
        pointsRuleDefault.setPriority(10);

        when(rewardRuleRepository.findByAccountIdOrderByPriorityDesc(cardConfigured.getId())).thenReturn(List.of(pointsRuleConfig));
        when(rewardRuleRepository.findByAccountIdOrderByPriorityDesc(cardDefault.getId())).thenReturn(List.of(pointsRuleDefault));

        RewardRecommendationRequest req = new RewardRecommendationRequest(
                new BigDecimal("1000.00"), LocalDate.now(), null, null, null, null, false, false, null);

        RewardRecommendationResponse resp = recommendationService.recommend(req);

        // 1000 spend -> 10 slabs * 10 pts = 100 pts.
        // Card Configured: 100 pts * ₹0.50/pt = ₹50.00 (CONFIG source)
        // Card Default: 100 pts * ₹0.25/pt = ₹25.00 (DEFAULT source)
        RewardCardRecommendationResponse first = resp.recommendations().get(0);
        assertEquals(cardConfigured.getId(), first.accountId());
        assertEquals(new BigDecimal("50.00"), first.totalValueInr());
        assertEquals("CONFIG", first.pointValueSource());
        assertEquals(new BigDecimal("0.50"), first.pointValueInr());

        RewardCardRecommendationResponse second = resp.recommendations().get(1);
        assertEquals(cardDefault.getId(), second.accountId());
        assertEquals(new BigDecimal("25.00"), second.totalValueInr());
        assertEquals("DEFAULT", second.pointValueSource());
        assertEquals(new BigDecimal("0.25"), second.pointValueInr());
    }

    @Test
    void predicateInputs_respected_channelAndEmi() {
        Account card = createCardAccount("Card Channel & EMI", null);

        when(accountRepository.findByUserId(userId)).thenReturn(List.of(card));
        when(accountRepository.findById(card.getId())).thenReturn(Optional.of(card));

        RewardRule onlineOnlyRule = createPercentRule(card, "Online Only 5%", new BigDecimal("5.0"), RuleStacking.EXCLUSIVE, 10);
        onlineOnlyRule.setChannels(Set.of(TransactionChannel.ONLINE));
        onlineOnlyRule.setEmiTreatment(EmiTreatment.EXCLUDE_EMI);

        when(rewardRuleRepository.findByAccountIdOrderByPriorityDesc(card.getId())).thenReturn(List.of(onlineOnlyRule));

        // 1. Channel POS -> no match
        RewardRecommendationRequest reqPos = new RewardRecommendationRequest(
                new BigDecimal("1000.00"), LocalDate.now(), null, null, null, TransactionChannel.POS, false, false, null);
        RewardRecommendationResponse respPos = recommendationService.recommend(reqPos);
        assertEquals(RewardLineReason.NO_RULE, respPos.recommendations().get(0).ruleLines().get(0).reason());

        // 2. Channel ONLINE + EMI true -> excluded by EXCLUDE_EMI
        RewardRecommendationRequest reqEmi = new RewardRecommendationRequest(
                new BigDecimal("1000.00"), LocalDate.now(), null, null, null, TransactionChannel.ONLINE, true, false, null);
        RewardRecommendationResponse respEmi = recommendationService.recommend(reqEmi);
        assertEquals(RewardLineReason.NO_RULE, respEmi.recommendations().get(0).ruleLines().get(0).reason());

        // 3. Channel ONLINE + EMI false -> matches 5%
        RewardRecommendationRequest reqOnline = new RewardRecommendationRequest(
                new BigDecimal("1000.00"), LocalDate.now(), null, null, null, TransactionChannel.ONLINE, false, false, null);
        RewardRecommendationResponse respOnline = recommendationService.recommend(reqOnline);
        assertEquals(RewardLineReason.MATCHED, respOnline.recommendations().get(0).ruleLines().get(0).reason());
        assertEquals(new BigDecimal("50.00"), respOnline.recommendations().get(0).totalValueInr());
    }

    @Test
    void exclusiveFallThrough_plusAdditiveStacking_bothContribute() {
        Account card = createCardAccount("Stacking Card", null);

        when(accountRepository.findByUserId(userId)).thenReturn(List.of(card));
        when(accountRepository.findById(card.getId())).thenReturn(Optional.of(card));

        // Top-priority exclusive is fully capped out and falls through to the 1% base.
        RewardRule exhausted = createPercentRule(card, "5% Exhausted", new BigDecimal("5.0"), RuleStacking.EXCLUSIVE, 20);
        exhausted.setPeriodCap(new BigDecimal("0.00"));
        exhausted.setCapWindow(CapWindow.CALENDAR_MONTH);
        exhausted.setOnCapExhausted(CapExhaustedBehavior.FALL_THROUGH);

        RewardRule base = createPercentRule(card, "1% Base", new BigDecimal("1.0"), RuleStacking.EXCLUSIVE, 10);
        RewardRule bonus = createPercentRule(card, "0.5% Additive Bonus", new BigDecimal("0.5"), RuleStacking.ADDITIVE, 5);

        when(rewardRuleRepository.findByAccountIdOrderByPriorityDesc(card.getId()))
                .thenReturn(List.of(exhausted, base, bonus));

        RewardRecommendationRequest req = new RewardRecommendationRequest(
                new BigDecimal("1000.00"), LocalDate.now(), null, null, null, null, false, false, null);

        RewardCardRecommendationResponse rec = recommendationService.recommend(req).recommendations().get(0);

        // Exclusive winner is the 1% base (₹10) after fall-through, plus the additive 0.5% (₹5).
        assertEquals(new BigDecimal("15.00"), rec.totalValueInr());
        assertEquals(2, rec.ruleLines().size());
        assertEquals(base.getId(), rec.ruleLines().get(0).ruleId());
        assertEquals(RewardLineReason.MATCHED, rec.ruleLines().get(0).reason());
        assertEquals(bonus.getId(), rec.ruleLines().get(1).ruleId());
        assertEquals(RewardLineReason.MATCHED, rec.ruleLines().get(1).reason());
    }

    @Test
    void tieredRule_accruesAtMarginalTrancheRate() {
        Account card = createCardAccount("Tiered Card", null);

        when(accountRepository.findByUserId(userId)).thenReturn(List.of(card));
        when(accountRepository.findById(card.getId())).thenReturn(Optional.of(card));

        // 5% up to ₹10k of matched spend per month, 1% thereafter.
        RewardRule tiered = createPercentRule(card, "Tiered 5%/1%", null, RuleStacking.EXCLUSIVE, 10);
        tiered.setTiers("[{\"upTo\":10000,\"rate\":5.0},{\"upTo\":null,\"rate\":1.0}]");
        tiered.setTierWindow(CapWindow.CALENDAR_MONTH);
        when(rewardRuleService.parseTiers(tiered)).thenReturn(List.of(
                new RewardTier(new BigDecimal("10000"), new BigDecimal("5.0")),
                new RewardTier(null, new BigDecimal("1.0"))));

        when(rewardRuleRepository.findByAccountIdOrderByPriorityDesc(card.getId())).thenReturn(List.of(tiered));

        // From zero window progress, a ₹12k spend splits: ₹10k @ 5% = ₹500, ₹2k @ 1% = ₹20.
        RewardRecommendationRequest req = new RewardRecommendationRequest(
                new BigDecimal("12000.00"), LocalDate.now(), null, null, null, null, false, false, null);

        RewardCardRecommendationResponse rec = recommendationService.recommend(req).recommendations().get(0);

        assertEquals(new BigDecimal("520.00"), rec.totalValueInr());
        assertEquals(RewardLineReason.MATCHED, rec.ruleLines().get(0).reason());
    }

    private RewardMilestone createSpendMilestone(Account account, String name, BigDecimal threshold,
                                                 MilestonePayoutType payoutType, BigDecimal payoutValue) {
        RewardMilestone milestone = new RewardMilestone();
        milestone.setId(UUID.randomUUID());
        milestone.setAccount(account);
        milestone.setName(name);
        milestone.setWindowType(MilestoneWindow.CALENDAR_MONTH);
        milestone.setBasis(MilestoneBasis.SPEND);
        milestone.setThreshold(threshold);
        milestone.setPayoutType(payoutType);
        milestone.setRewardType(RewardType.CASH);
        milestone.setPayoutValue(payoutValue);
        when(rewardMilestoneService.parseEligibility(milestone)).thenReturn(MilestoneEligibility.EMPTY);
        return milestone;
    }

    @Test
    void milestoneCrossing_fullPayout_fractionalProximityOtherwise() {
        Account crossingCard = createCardAccount("Crossing Card", null);
        Account proximityCard = createCardAccount("Proximity Card", null);

        when(accountRepository.findByUserId(userId)).thenReturn(List.of(crossingCard, proximityCard));
        when(accountRepository.findById(crossingCard.getId())).thenReturn(Optional.of(crossingCard));
        when(accountRepository.findById(proximityCard.getId())).thenReturn(Optional.of(proximityCard));

        RewardRule flat1PctA = createPercentRule(crossingCard, "1% Base", new BigDecimal("1.0"), RuleStacking.EXCLUSIVE, 10);
        RewardRule flat1PctB = createPercentRule(proximityCard, "1% Base", new BigDecimal("1.0"), RuleStacking.EXCLUSIVE, 10);
        when(rewardRuleRepository.findByAccountIdOrderByPriorityDesc(crossingCard.getId())).thenReturn(List.of(flat1PctA));
        when(rewardRuleRepository.findByAccountIdOrderByPriorityDesc(proximityCard.getId())).thenReturn(List.of(flat1PctB));

        // Crossing card: ₹10k threshold, ₹500 payout — a ₹12k spend crosses from zero progress.
        RewardMilestone crossable = createSpendMilestone(crossingCard, "Monthly 10k",
                new BigDecimal("10000"), MilestonePayoutType.CASH_VALUE, new BigDecimal("500"));
        // Proximity card: ₹100k threshold, ₹1000 payout — same spend earns 12% fractional credit.
        RewardMilestone farAway = createSpendMilestone(proximityCard, "Monthly 100k",
                new BigDecimal("100000"), MilestonePayoutType.CASH_VALUE, new BigDecimal("1000"));
        when(rewardMilestoneRepository.findByAccountIdOrderByCreatedAtAsc(crossingCard.getId())).thenReturn(List.of(crossable));
        when(rewardMilestoneRepository.findByAccountIdOrderByCreatedAtAsc(proximityCard.getId())).thenReturn(List.of(farAway));

        RewardRecommendationRequest req = new RewardRecommendationRequest(
                new BigDecimal("12000.00"), LocalDate.now(), null, null, null, null, false, false, null);

        RewardRecommendationResponse resp = recommendationService.recommend(req);

        RewardCardRecommendationResponse first = resp.recommendations().get(0);
        assertEquals(crossingCard.getId(), first.accountId());
        // ₹120 guaranteed (1% of 12k) + full ₹500 payout on crossing.
        assertEquals(new BigDecimal("120.00"), first.guaranteedValueInr());
        assertEquals(new BigDecimal("500"), first.milestoneValueInr());
        assertTrue(first.milestones().get(0).crosses());
        assertEquals(new BigDecimal("10000"), first.milestones().get(0).remainingToThreshold());

        RewardCardRecommendationResponse second = resp.recommendations().get(1);
        assertEquals(proximityCard.getId(), second.accountId());
        // ₹120 guaranteed + fractional credit 12000/100000 × ₹1000 = ₹120.
        assertEquals(new BigDecimal("120.00"), second.milestoneValueInr());
        assertFalse(second.milestones().get(0).crosses());
    }

    @Test
    void infoTrackerMilestone_listedButNeverScored() {
        Account card = createCardAccount("Fee Waiver Card", null);

        when(accountRepository.findByUserId(userId)).thenReturn(List.of(card));
        when(accountRepository.findById(card.getId())).thenReturn(Optional.of(card));

        RewardRule flat1Pct = createPercentRule(card, "1% Base", new BigDecimal("1.0"), RuleStacking.EXCLUSIVE, 10);
        when(rewardRuleRepository.findByAccountIdOrderByPriorityDesc(card.getId())).thenReturn(List.of(flat1Pct));

        RewardMilestone feeWaiver = createSpendMilestone(card, "Fee Waiver 50k",
                new BigDecimal("50000"), MilestonePayoutType.INFO_TRACKER, null);
        when(rewardMilestoneRepository.findByAccountIdOrderByCreatedAtAsc(card.getId())).thenReturn(List.of(feeWaiver));

        RewardRecommendationRequest req = new RewardRecommendationRequest(
                new BigDecimal("10000.00"), LocalDate.now(), null, null, null, null, false, false, null);

        RewardRecommendationResponse resp = recommendationService.recommend(req);

        RewardCardRecommendationResponse rec = resp.recommendations().get(0);
        // Listed as decision-relevant info, but contributes nothing to the score.
        assertEquals(1, rec.milestones().size());
        assertNull(rec.milestones().get(0).payoutInr());
        assertEquals(BigDecimal.ZERO, rec.milestones().get(0).scoredValueInr());
        assertEquals(BigDecimal.ZERO, rec.milestoneValueInr());
        assertEquals(new BigDecimal("100.00"), rec.totalValueInr());
    }

    @Test
    void foreignAccountId_throwsValidationException() {
        UUID foreignAccountId = UUID.randomUUID();
        User foreignUser = new User();
        foreignUser.setId(UUID.randomUUID());
        Account foreignAccount = new Account("Foreign Card", AccountType.credit_card);
        foreignAccount.setId(foreignAccountId);
        foreignAccount.setUser(foreignUser);

        when(accountRepository.findById(foreignAccountId)).thenReturn(Optional.of(foreignAccount));

        RewardRecommendationRequest req = new RewardRecommendationRequest(
                new BigDecimal("1000.00"), LocalDate.now(), null, null, null, null, false, false, List.of(foreignAccountId));

        assertThrows(ValidationException.class, () -> recommendationService.recommend(req));
    }
}
