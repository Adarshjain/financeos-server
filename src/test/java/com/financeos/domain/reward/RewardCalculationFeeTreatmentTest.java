package com.financeos.domain.reward;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.financeos.api.reward.dto.RewardLineResponse;
import com.financeos.core.security.UserContext;
import com.financeos.domain.account.Account;
import com.financeos.domain.account.AccountRepository;
import com.financeos.domain.account.AccountType;
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

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Convenience fee is a labeled portion of the charged amount. Issuers that post the
 * surcharge but award nothing on it are modeled by FeeTreatment.EXCLUDE_FEE, which nets
 * the fee out of the earning basis after the rule has already matched.
 */
class RewardCalculationFeeTreatmentTest {

    private static final LocalDate TXN_DATE = LocalDate.of(2026, 3, 10);

    private AccountRepository accountRepository;
    private RewardRuleRepository rewardRuleRepository;
    private RewardMilestoneRepository rewardMilestoneRepository;
    private RewardMilestoneService rewardMilestoneService;
    private RewardRuleService rewardRuleService;
    private TransactionRepository transactionRepository;
    private TransactionLinkRepository transactionLinkRepository;
    private StatementRepository statementRepository;

    private RewardCalculationService service;

    private User user;
    private Account card;

    @BeforeEach
    void setUp() {
        UUID userId = UUID.randomUUID();
        user = new User();
        user.setId(userId);
        UserContext.setCurrentUserId(userId);

        accountRepository = mock(AccountRepository.class);
        rewardRuleRepository = mock(RewardRuleRepository.class);
        rewardMilestoneRepository = mock(RewardMilestoneRepository.class);
        rewardMilestoneService = mock(RewardMilestoneService.class);
        rewardRuleService = mock(RewardRuleService.class);
        transactionRepository = mock(TransactionRepository.class);
        transactionLinkRepository = mock(TransactionLinkRepository.class);
        statementRepository = mock(StatementRepository.class);

        card = new Account("Surcharge Card", AccountType.credit_card);
        card.setId(UUID.randomUUID());
        card.setUser(user);

        when(accountRepository.findById(card.getId())).thenReturn(Optional.of(card));
        when(rewardMilestoneRepository.findByAccountIdOrderByCreatedAtAsc(any())).thenReturn(List.of());
        when(statementRepository.findByAccountIdOrderByPeriodEndDescNullsLast(any())).thenReturn(List.of());
        when(transactionLinkRepository.findLinkIdsByMemberTransactionIds(any())).thenReturn(List.of());

        service = new RewardCalculationService(
                rewardRuleRepository, rewardRuleService, rewardMilestoneRepository, rewardMilestoneService,
                transactionRepository, transactionLinkRepository, statementRepository, accountRepository);
    }

    @AfterEach
    void tearDown() {
        UserContext.clear();
    }

    private RewardRule percentRule(BigDecimal rate, FeeTreatment feeTreatment) {
        RewardRule rule = new RewardRule();
        rule.setId(UUID.randomUUID());
        rule.setAccount(card);
        rule.setName(rate + "% cash");
        rule.setPercentRate(rate);
        rule.setAccrualType(AccrualType.PERCENT);
        rule.setRewardType(RewardType.CASH);
        rule.setStacking(RuleStacking.EXCLUSIVE);
        rule.setPriority(10);
        rule.setFeeTreatment(feeTreatment);
        return rule;
    }

    private Transaction txn(BigDecimal amount, BigDecimal convenienceFee) {
        Transaction t = new Transaction(card, TXN_DATE, amount, "IRCTC ticket",
                TransactionSource.manual, TransactionType.DEBIT, false, false);
        t.setId(UUID.randomUUID());
        t.setUser(user);
        t.setConvenienceFee(convenienceFee);
        return t;
    }

    private List<RewardLineResponse> evaluateLines(RewardRule rule, Transaction transaction) {
        when(rewardRuleRepository.findByAccountIdOrderByPriorityDesc(card.getId())).thenReturn(List.of(rule));
        when(transactionRepository.findForRewardEvaluation(any(), any(), any())).thenReturn(List.of(transaction));
        return service.evaluate(card.getId(), TXN_DATE, TXN_DATE, false).lines;
    }

    @Test
    void excludeFee_netsSurchargeOutOfBasis() {
        // ₹1000 charged, ₹100 of it a surcharge → 2% of ₹900, not of ₹1000.
        List<RewardLineResponse> lines = evaluateLines(
                percentRule(new BigDecimal("2.0"), FeeTreatment.EXCLUDE_FEE),
                txn(new BigDecimal("1000.00"), new BigDecimal("100.00")));

        assertEquals(1, lines.size());
        assertEquals(RewardLineReason.MATCHED, lines.get(0).reason());
        assertEquals(0, new BigDecimal("18.00").compareTo(lines.get(0).earned()));
        assertEquals(0, new BigDecimal("900.00").compareTo(lines.get(0).basis()),
                "the line's basis is the explainability trace — it must show the reduced number");
    }

    @Test
    void includeFee_isTheDefaultAndKeepsPreExistingBehavior() {
        RewardRule rule = percentRule(new BigDecimal("2.0"), FeeTreatment.INCLUDE);
        assertEquals(FeeTreatment.INCLUDE, new RewardRule().getFeeTreatment(), "default must not change history");

        List<RewardLineResponse> lines = evaluateLines(rule, txn(new BigDecimal("1000.00"), new BigDecimal("100.00")));

        assertEquals(1, lines.size());
        assertEquals(0, new BigDecimal("20.00").compareTo(lines.get(0).earned()));
        assertEquals(0, new BigDecimal("1000.00").compareTo(lines.get(0).basis()));
    }

    @Test
    void excludeFee_withNoRecordedFee_leavesBasisUntouched() {
        List<RewardLineResponse> lines = evaluateLines(
                percentRule(new BigDecimal("2.0"), FeeTreatment.EXCLUDE_FEE),
                txn(new BigDecimal("1000.00"), null));

        assertEquals(1, lines.size());
        assertEquals(0, new BigDecimal("20.00").compareTo(lines.get(0).earned()));
    }

    @Test
    void feeEqualToWholeCharge_earnsNothingAndSaysWhy() {
        List<RewardLineResponse> lines = evaluateLines(
                percentRule(new BigDecimal("2.0"), FeeTreatment.EXCLUDE_FEE),
                txn(new BigDecimal("50.00"), new BigDecimal("50.00")));

        assertEquals(1, lines.size());
        assertEquals(RewardLineReason.FEE_ONLY, lines.get(0).reason());
        assertEquals(0, BigDecimal.ZERO.compareTo(lines.get(0).earned()));
    }

    @Test
    void feeLargerThanCharge_floorsAtZeroRatherThanGoingNegative() {
        List<RewardLineResponse> lines = evaluateLines(
                percentRule(new BigDecimal("2.0"), FeeTreatment.EXCLUDE_FEE),
                txn(new BigDecimal("50.00"), new BigDecimal("80.00")));

        assertEquals(1, lines.size());
        assertEquals(RewardLineReason.FEE_ONLY, lines.get(0).reason());
        assertEquals(0, BigDecimal.ZERO.compareTo(lines.get(0).earned()));
        assertEquals(0, BigDecimal.ZERO.compareTo(lines.get(0).basis()));
    }

    @Test
    void excludeFee_doesNotAdvanceTierProgressOnTheFeePortion() {
        // Tier 1: 0–1000 at 1%, tier 2: beyond at 5%. Two ₹600 charges each carrying a
        // ₹100 fee contribute 500 each, so the pair stays inside tier 1 — the fee must
        // not push the running window total across the breakpoint.
        RewardRule rule = percentRule(new BigDecimal("1.0"), FeeTreatment.EXCLUDE_FEE);
        rule.setTierWindow(CapWindow.CALENDAR_MONTH);
        rule.setTiers("[{\"upTo\":1000,\"rate\":1.0},{\"upTo\":null,\"rate\":5.0}]");
        when(rewardRuleService.parseTiers(rule)).thenReturn(List.of(
                new RewardTier(new BigDecimal("1000"), new BigDecimal("1.0")),
                new RewardTier(null, new BigDecimal("5.0"))));

        Transaction first = txn(new BigDecimal("600.00"), new BigDecimal("100.00"));
        Transaction second = txn(new BigDecimal("600.00"), new BigDecimal("100.00"));
        when(rewardRuleRepository.findByAccountIdOrderByPriorityDesc(card.getId())).thenReturn(List.of(rule));
        when(transactionRepository.findForRewardEvaluation(any(), any(), any())).thenReturn(List.of(first, second));

        List<RewardLineResponse> lines = service.evaluate(card.getId(), TXN_DATE, TXN_DATE, false).lines;

        assertEquals(2, lines.size());
        // 500 + 500 = 1000, entirely within tier 1 → 1% each, no 5% tranche.
        assertEquals(0, new BigDecimal("5.00").compareTo(lines.get(0).earned()));
        assertEquals(0, new BigDecimal("5.00").compareTo(lines.get(1).earned()));
    }
}
