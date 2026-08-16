package com.financeos.domain.reward;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.financeos.api.cardfee.dto.CardFeeScheduleResponse;
import com.financeos.api.cardfee.dto.FeeOccurrenceResponse;
import com.financeos.api.reward.dto.RewardLineResponse;
import com.financeos.api.reward.dto.RewardReportResponse;
import com.financeos.core.security.UserContext;
import com.financeos.domain.account.Account;
import com.financeos.domain.account.AccountRepository;
import com.financeos.domain.account.AccountType;
import com.financeos.domain.cardfee.*;
import com.financeos.domain.statement.StatementRepository;
import com.financeos.domain.transaction.Transaction;
import com.financeos.domain.transaction.TransactionRepository;
import com.financeos.domain.transaction.TransactionType;
import com.financeos.domain.transaction.link.TransactionLinkRepository;
import com.financeos.domain.user.User;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

class RewardCalculationCardFeeTest {

    private AccountRepository accountRepository;
    private RewardRuleRepository rewardRuleRepository;
    private RewardMilestoneRepository rewardMilestoneRepository;
    private RewardMilestoneService rewardMilestoneService;
    private RewardRuleService rewardRuleService;
    private TransactionRepository transactionRepository;
    private TransactionLinkRepository transactionLinkRepository;
    private StatementRepository statementRepository;
    private CardFeeTermRepository cardFeeTermRepository;
    private CardFeeChargeRepository cardFeeChargeRepository;

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
        cardFeeTermRepository = mock(CardFeeTermRepository.class);
        cardFeeChargeRepository = mock(CardFeeChargeRepository.class);

        card = new Account("Test Card", AccountType.credit_card);
        card.setId(UUID.randomUUID());
        card.setUser(user);
        card.setRewardAnniversaryDate(LocalDate.of(2025, 7, 1));

        when(accountRepository.findById(card.getId())).thenReturn(Optional.of(card));
        when(rewardRuleRepository.findByAccountIdOrderByPriorityDesc(any())).thenReturn(List.of());
        when(rewardMilestoneRepository.findByAccountIdOrderByCreatedAtAsc(any())).thenReturn(List.of());
        when(statementRepository.findByAccountIdOrderByPeriodEndDescNullsLast(any())).thenReturn(List.of());
        when(transactionLinkRepository.findLinkIdsByMemberTransactionIds(any())).thenReturn(List.of());
        when(cardFeeChargeRepository.findByAccountId(any())).thenReturn(List.of());

        service = new RewardCalculationService(
                rewardRuleRepository, rewardRuleService, rewardMilestoneRepository, rewardMilestoneService,
                transactionRepository, transactionLinkRepository, statementRepository, accountRepository,
                cardFeeTermRepository, cardFeeChargeRepository);
    }

    @AfterEach
    void tearDown() {
        UserContext.clear();
    }

    private CardFeeTerm createTerm(CardFeeKind kind, BigDecimal amount, LocalDate effectiveFrom, BigDecimal waiverThreshold, FeeWaiverBasis basis) {
        CardFeeTerm term = new CardFeeTerm();
        term.setId(UUID.randomUUID());
        term.setAccount(card);
        term.setUser(user);
        term.setKind(kind);
        term.setAmount(amount);
        term.setGstRate(BigDecimal.valueOf(18));
        term.setEffectiveFrom(effectiveFrom);
        term.setWaiverSpendThreshold(waiverThreshold);
        term.setWaiverBasis(basis);
        return term;
    }

    private Transaction createTxn(BigDecimal amount, LocalDate date, TransactionType type) {
        Transaction t = new Transaction();
        t.setId(UUID.randomUUID());
        t.setAccount(card);
        t.setUser(user);
        t.setAmount(amount);
        t.setDate(date);
        t.setType(type);
        return t;
    }

    // 1. LTF term -> cardFeesInr = 0, netValueInr == effectiveValueInr
    @Test
    void test1_ltfTerm_zeroCardFees() {
        CardFeeTerm ltf = createTerm(CardFeeKind.LTF, BigDecimal.ZERO, LocalDate.of(2025, 7, 1), null, null);
        when(cardFeeTermRepository.findByAccountIdOrderByEffectiveFromAsc(card.getId())).thenReturn(List.of(ltf));
        when(transactionRepository.findByAccountIdAndDateRange(any(), any(), any())).thenReturn(List.of());

        RewardReportResponse report = service.report(card.getId(), LocalDate.of(2026, 1, 1), LocalDate.of(2026, 12, 31));
        assertEquals(BigDecimal.ZERO, report.summary().cardFeesInr());
        assertEquals(report.summary().effectiveValueInr(), report.summary().netValueInr());
    }

    // 2. Flat annual fee over exact fee year -> applied exactly, no rounding drift
    @Test
    void test2_flatAnnualFee_exactFeeYear() {
        CardFeeTerm term = createTerm(CardFeeKind.ANNUAL_FEE, BigDecimal.valueOf(2500), LocalDate.of(2025, 7, 1), null, null);
        when(cardFeeTermRepository.findByAccountIdOrderByEffectiveFromAsc(card.getId())).thenReturn(List.of(term));
        when(transactionRepository.findByAccountIdAndDateRange(any(), any(), any())).thenReturn(List.of());

        RewardReportResponse report = service.report(card.getId(), LocalDate.of(2025, 7, 1), LocalDate.of(2026, 6, 30));
        assertEquals(0, new BigDecimal("2950.00").compareTo(report.summary().cardFeesInr()));
    }

    // 3. One calendar month inside fee year -> daily pro-rata
    @Test
    void test3_oneMonthInsideFeeYear_dailyProrata() {
        CardFeeTerm term = createTerm(CardFeeKind.ANNUAL_FEE, BigDecimal.valueOf(2500), LocalDate.of(2025, 7, 1), null, null);
        when(cardFeeTermRepository.findByAccountIdOrderByEffectiveFromAsc(card.getId())).thenReturn(List.of(term));
        when(transactionRepository.findByAccountIdAndDateRange(any(), any(), any())).thenReturn(List.of());

        RewardReportResponse report = service.report(card.getId(), LocalDate.of(2025, 7, 1), LocalDate.of(2025, 7, 31));
        assertEquals(0, new BigDecimal("250.55").compareTo(report.summary().cardFeesInr()));
    }

    // 4. Mandatory: Straddling range with hard fixtures (363.70 / 379.86)
    @Test
    void test4_straddlingRange_twoFeeYearsDifferentAmounts() {
        CardFeeTerm ltf1 = createTerm(CardFeeKind.LTF, BigDecimal.ZERO, LocalDate.of(2025, 7, 1), null, null);
        CardFeeTerm fee2 = createTerm(CardFeeKind.ANNUAL_FEE, BigDecimal.valueOf(2500), LocalDate.of(2026, 7, 1), null, null);
        when(cardFeeTermRepository.findByAccountIdOrderByEffectiveFromAsc(card.getId())).thenReturn(List.of(ltf1, fee2));
        when(transactionRepository.findByAccountIdAndDateRange(any(), any(), any())).thenReturn(List.of());

        RewardReportResponse reportA = service.report(card.getId(), LocalDate.of(2026, 5, 15), LocalDate.of(2026, 8, 14));
        assertEquals(0, new BigDecimal("363.70").compareTo(reportA.summary().cardFeesInr()));

        CardFeeTerm fee1 = createTerm(CardFeeKind.ANNUAL_FEE, BigDecimal.valueOf(2500), LocalDate.of(2025, 7, 1), null, null);
        CardFeeTerm ltf2 = createTerm(CardFeeKind.LTF, BigDecimal.ZERO, LocalDate.of(2026, 7, 1), null, null);
        when(cardFeeTermRepository.findByAccountIdOrderByEffectiveFromAsc(card.getId())).thenReturn(List.of(fee1, ltf2));

        RewardReportResponse reportB = service.report(card.getId(), LocalDate.of(2026, 5, 15), LocalDate.of(2026, 8, 14));
        assertEquals(0, new BigDecimal("379.86").compareTo(reportB.summary().cardFeesInr()));
    }

    // 5. LTF -> ANNUAL_FEE transition
    @Test
    void test5_ltfToAnnualFeeTransition() {
        CardFeeTerm ltf = createTerm(CardFeeKind.LTF, BigDecimal.ZERO, LocalDate.of(2025, 7, 1), null, null);
        CardFeeTerm fee = createTerm(CardFeeKind.ANNUAL_FEE, BigDecimal.valueOf(2500), LocalDate.of(2026, 7, 1), null, null);
        when(cardFeeTermRepository.findByAccountIdOrderByEffectiveFromAsc(card.getId())).thenReturn(List.of(ltf, fee));

        CardFeeScheduleResponse sched = service.feeSchedule(card.getId(), LocalDate.of(2025, 7, 1), LocalDate.of(2027, 6, 30));
        assertEquals(2, sched.occurrences().size());
        assertEquals(CardFeeKind.LTF, sched.occurrences().get(0).kind());
        assertEquals(CardFeeKind.ANNUAL_FEE, sched.occurrences().get(1).kind());
    }

    // 6. ANNUAL_FEE -> LTF transition
    @Test
    void test6_annualFeeToLtfTransition() {
        CardFeeTerm fee = createTerm(CardFeeKind.ANNUAL_FEE, BigDecimal.valueOf(2500), LocalDate.of(2025, 7, 1), null, null);
        CardFeeTerm ltf = createTerm(CardFeeKind.LTF, BigDecimal.ZERO, LocalDate.of(2026, 7, 1), null, null);
        when(cardFeeTermRepository.findByAccountIdOrderByEffectiveFromAsc(card.getId())).thenReturn(List.of(fee, ltf));

        CardFeeScheduleResponse sched = service.feeSchedule(card.getId(), LocalDate.of(2025, 7, 1), LocalDate.of(2027, 6, 30));
        assertEquals(2, sched.occurrences().size());
        assertEquals(FeeOccurrenceStatus.DUE, sched.occurrences().get(0).status());
        assertEquals(FeeOccurrenceStatus.LIFETIME_FREE, sched.occurrences().get(1).status());
    }

    // 7. Fee revision
    @Test
    void test7_feeRevision() {
        CardFeeTerm fee1 = createTerm(CardFeeKind.ANNUAL_FEE, BigDecimal.valueOf(1000), LocalDate.of(2025, 7, 1), null, null);
        CardFeeTerm fee2 = createTerm(CardFeeKind.ANNUAL_FEE, BigDecimal.valueOf(3000), LocalDate.of(2026, 7, 1), null, null);
        when(cardFeeTermRepository.findByAccountIdOrderByEffectiveFromAsc(card.getId())).thenReturn(List.of(fee1, fee2));

        CardFeeScheduleResponse sched = service.feeSchedule(card.getId(), LocalDate.of(2025, 7, 1), LocalDate.of(2027, 6, 30));
        assertEquals(0, new BigDecimal("1180.00").compareTo(sched.occurrences().get(0).totalAmount()));
        assertEquals(0, new BigDecimal("3540.00").compareTo(sched.occurrences().get(1).totalAmount()));
    }

    // 8. Mid-fee-year term -> first governed fee year is next
    @Test
    void test8_midFeeYearTerm() {
        CardFeeTerm fee = createTerm(CardFeeKind.ANNUAL_FEE, BigDecimal.valueOf(2500), LocalDate.of(2025, 10, 15), null, null);
        when(cardFeeTermRepository.findByAccountIdOrderByEffectiveFromAsc(card.getId())).thenReturn(List.of(fee));

        CardFeeScheduleResponse sched = service.feeSchedule(card.getId(), LocalDate.of(2025, 7, 1), LocalDate.of(2027, 6, 30));
        assertEquals(FeeOccurrenceStatus.NOT_CONFIGURED, sched.occurrences().get(0).status());
        assertEquals(FeeOccurrenceStatus.DUE, sched.occurrences().get(1).status());
    }

    // 9. Mandatory: Closure mid-fee-year -> full amount compressed into [F.start, closedOn]
    @Test
    void test9_closureMidFeeYear() {
        card.setClosedOn(LocalDate.of(2025, 9, 30));
        CardFeeTerm term = createTerm(CardFeeKind.ANNUAL_FEE, BigDecimal.valueOf(2500), LocalDate.of(2025, 7, 1), null, null);
        when(cardFeeTermRepository.findByAccountIdOrderByEffectiveFromAsc(card.getId())).thenReturn(List.of(term));

        RewardReportResponse reportActive = service.report(card.getId(), LocalDate.of(2025, 7, 1), LocalDate.of(2025, 9, 30));
        assertEquals(0, new BigDecimal("2950.00").compareTo(reportActive.summary().cardFeesInr()));

        RewardReportResponse reportPostClose = service.report(card.getId(), LocalDate.of(2025, 10, 1), LocalDate.of(2025, 12, 31));
        assertEquals(BigDecimal.ZERO, reportPostClose.summary().cardFeesInr());
    }

    // 10. Fee year starting after closure -> SUPPRESSED_CLOSED
    @Test
    void test10_feeYearStartingAfterClosure() {
        card.setClosedOn(LocalDate.of(2026, 3, 15));
        CardFeeTerm term = createTerm(CardFeeKind.ANNUAL_FEE, BigDecimal.valueOf(2500), LocalDate.of(2025, 7, 1), null, null);
        when(cardFeeTermRepository.findByAccountIdOrderByEffectiveFromAsc(card.getId())).thenReturn(List.of(term));

        CardFeeScheduleResponse sched = service.feeSchedule(card.getId(), LocalDate.of(2025, 7, 1), LocalDate.of(2027, 6, 30));
        assertEquals(2, sched.occurrences().size());
        assertEquals(FeeOccurrenceStatus.DUE, sched.occurrences().get(0).status());
        assertEquals(FeeOccurrenceStatus.SUPPRESSED_CLOSED, sched.occurrences().get(1).status());
        assertEquals(LocalDate.of(2026, 7, 1), sched.occurrences().get(1).amortiseFrom());
        assertEquals(LocalDate.of(2026, 7, 1), sched.occurrences().get(1).amortiseTo());
    }

    // 11. Auto waiver PRECEDING_FEE_YEAR
    @Test
    void test11_autoWaiverPrecedingFeeYear() {
        CardFeeTerm term = createTerm(CardFeeKind.ANNUAL_FEE, BigDecimal.valueOf(2500), LocalDate.of(2025, 7, 1), BigDecimal.valueOf(100000), FeeWaiverBasis.PRECEDING_FEE_YEAR);
        when(cardFeeTermRepository.findByAccountIdOrderByEffectiveFromAsc(card.getId())).thenReturn(List.of(term));

        Transaction txn = createTxn(BigDecimal.valueOf(150000), LocalDate.of(2025, 8, 1), TransactionType.DEBIT);
        when(transactionRepository.findForRewardEvaluation(eq(card.getId()), any(), any())).thenReturn(List.of(txn));

        CardFeeScheduleResponse sched = service.feeSchedule(card.getId(), LocalDate.of(2026, 7, 1), LocalDate.of(2027, 6, 30));
        assertEquals(FeeOccurrenceStatus.WAIVED_AUTO, sched.occurrences().get(0).status());
        assertEquals(FeeWaiverSource.AUTO_SPEND, sched.occurrences().get(0).waiverSource());
    }

    // 12. SAME_FEE_YEAR with open window -> provisional = true
    @Test
    void test12_sameFeeYearProvisional() {
        CardFeeTerm term = createTerm(CardFeeKind.ANNUAL_FEE, BigDecimal.valueOf(2500), LocalDate.of(2026, 7, 1), BigDecimal.valueOf(100000), FeeWaiverBasis.SAME_FEE_YEAR);
        when(cardFeeTermRepository.findByAccountIdOrderByEffectiveFromAsc(card.getId())).thenReturn(List.of(term));

        CardFeeScheduleResponse sched = service.feeSchedule(card.getId(), LocalDate.of(2026, 7, 1), LocalDate.of(2027, 6, 30));
        assertTrue(sched.occurrences().get(0).provisional());
    }

    // 13. Mandatory: Linked charge beats auto-waive
    @Test
    void test13_linkedChargeBeatsAutoWaive() {
        CardFeeTerm term = createTerm(CardFeeKind.ANNUAL_FEE, BigDecimal.valueOf(2500), LocalDate.of(2025, 7, 1), BigDecimal.valueOf(100000), FeeWaiverBasis.SAME_FEE_YEAR);
        when(cardFeeTermRepository.findByAccountIdOrderByEffectiveFromAsc(card.getId())).thenReturn(List.of(term));

        Transaction spend = createTxn(BigDecimal.valueOf(150000), LocalDate.of(2025, 8, 1), TransactionType.DEBIT);
        Transaction feeTxn = createTxn(BigDecimal.valueOf(2950), LocalDate.of(2025, 7, 5), TransactionType.DEBIT);

        CardFeeCharge charge = new CardFeeCharge();
        charge.setId(UUID.randomUUID());
        charge.setAccount(card);
        charge.setUser(user);
        charge.setKind(CardFeeKind.ANNUAL_FEE);
        charge.setFeeYearStart(LocalDate.of(2025, 7, 1));
        charge.setTransactionIds(Set.of(feeTxn.getId()));

        when(cardFeeChargeRepository.findByAccountId(card.getId())).thenReturn(List.of(charge));
        when(transactionRepository.findAllByIdIn(List.of(feeTxn.getId()))).thenReturn(List.of(feeTxn));
        when(transactionRepository.findForRewardEvaluation(eq(card.getId()), any(), any())).thenReturn(List.of(spend));

        CardFeeScheduleResponse sched = service.feeSchedule(card.getId(), LocalDate.of(2025, 7, 1), LocalDate.of(2026, 6, 30));
        FeeOccurrenceResponse occ = sched.occurrences().get(0);
        assertEquals(FeeOccurrenceStatus.CHARGED_MANUAL, occ.status());
        assertEquals(0, new BigDecimal("2950.00").compareTo(occ.netAmount()));
        assertTrue(occ.waiverContradictsLinkedCharge());
    }

    // 14. Mandatory: Full precedence chain
    @Test
    void test14_fullPrecedenceChain() {
        CardFeeTerm term = createTerm(CardFeeKind.ANNUAL_FEE, BigDecimal.valueOf(2500), LocalDate.of(2025, 7, 1), BigDecimal.valueOf(100000), FeeWaiverBasis.SAME_FEE_YEAR);
        when(cardFeeTermRepository.findByAccountIdOrderByEffectiveFromAsc(card.getId())).thenReturn(List.of(term));

        CardFeeCharge charge = new CardFeeCharge();
        charge.setId(UUID.randomUUID());
        charge.setAccount(card);
        charge.setUser(user);
        charge.setKind(CardFeeKind.ANNUAL_FEE);
        charge.setFeeYearStart(LocalDate.of(2025, 7, 1));
        charge.setOverrideAmount(BigDecimal.valueOf(1500));
        charge.setWaived(true);

        when(cardFeeChargeRepository.findByAccountId(card.getId())).thenReturn(List.of(charge));

        CardFeeScheduleResponse sched = service.feeSchedule(card.getId(), LocalDate.of(2025, 7, 1), LocalDate.of(2026, 6, 30));
        assertEquals(0, new BigDecimal("1500").compareTo(sched.occurrences().get(0).netAmount()));
        assertEquals(FeeWaiverSource.MANUAL, sched.occurrences().get(0).waiverSource());
    }

    // 15. Fee + GST as two linked transactions
    @Test
    void test15_feeAndGstAsTwoLinkedTransactions() {
        CardFeeTerm term = createTerm(CardFeeKind.ANNUAL_FEE, BigDecimal.valueOf(2500), LocalDate.of(2025, 7, 1), null, null);
        when(cardFeeTermRepository.findByAccountIdOrderByEffectiveFromAsc(card.getId())).thenReturn(List.of(term));

        Transaction feeTxn = createTxn(BigDecimal.valueOf(2500), LocalDate.of(2025, 7, 5), TransactionType.DEBIT);
        Transaction gstTxn = createTxn(BigDecimal.valueOf(450), LocalDate.of(2025, 7, 5), TransactionType.DEBIT);

        CardFeeCharge charge = new CardFeeCharge();
        charge.setId(UUID.randomUUID());
        charge.setAccount(card);
        charge.setUser(user);
        charge.setKind(CardFeeKind.ANNUAL_FEE);
        charge.setFeeYearStart(LocalDate.of(2025, 7, 1));
        charge.setTransactionIds(Set.of(feeTxn.getId(), gstTxn.getId()));

        when(cardFeeChargeRepository.findByAccountId(card.getId())).thenReturn(List.of(charge));
        when(transactionRepository.findAllByIdIn(any())).thenReturn(List.of(feeTxn, gstTxn));

        CardFeeScheduleResponse sched = service.feeSchedule(card.getId(), LocalDate.of(2025, 7, 1), LocalDate.of(2026, 6, 30));
        assertEquals(0, new BigDecimal("2950.00").compareTo(sched.occurrences().get(0).netAmount()));
    }

    // 16. waived = false override forces charge
    @Test
    void test16_waivedFalseOverrideForcesCharge() {
        CardFeeTerm term = createTerm(CardFeeKind.ANNUAL_FEE, BigDecimal.valueOf(2500), LocalDate.of(2025, 7, 1), BigDecimal.valueOf(100000), FeeWaiverBasis.SAME_FEE_YEAR);
        when(cardFeeTermRepository.findByAccountIdOrderByEffectiveFromAsc(card.getId())).thenReturn(List.of(term));

        CardFeeCharge charge = new CardFeeCharge();
        charge.setId(UUID.randomUUID());
        charge.setAccount(card);
        charge.setUser(user);
        charge.setKind(CardFeeKind.ANNUAL_FEE);
        charge.setFeeYearStart(LocalDate.of(2025, 7, 1));
        charge.setWaived(false);

        when(cardFeeChargeRepository.findByAccountId(card.getId())).thenReturn(List.of(charge));
        Transaction spend = createTxn(BigDecimal.valueOf(150000), LocalDate.of(2025, 8, 1), TransactionType.DEBIT);
        when(transactionRepository.findForRewardEvaluation(eq(card.getId()), any(), any())).thenReturn(List.of(spend));

        CardFeeScheduleResponse sched = service.feeSchedule(card.getId(), LocalDate.of(2025, 7, 1), LocalDate.of(2026, 6, 30));
        assertEquals(0, new BigDecimal("2950.00").compareTo(sched.occurrences().get(0).netAmount()));
        assertEquals(FeeWaiverSource.MANUAL, sched.occurrences().get(0).waiverSource());
    }

    // 17. Mandatory: Linked fee transaction produces CARD_FEE line, absent from basisSpend
    @Test
    void test17_linkedFeeTransaction_cardFeeLine() {
        CardFeeTerm term = createTerm(CardFeeKind.ANNUAL_FEE, BigDecimal.valueOf(2500), LocalDate.of(2025, 7, 1), null, null);
        when(cardFeeTermRepository.findByAccountIdOrderByEffectiveFromAsc(card.getId())).thenReturn(List.of(term));

        Transaction feeTxn = createTxn(BigDecimal.valueOf(2950), LocalDate.of(2026, 3, 10), TransactionType.DEBIT);
        feeTxn.setDescription("Annual Fee Charge");

        CardFeeCharge charge = new CardFeeCharge();
        charge.setId(UUID.randomUUID());
        charge.setAccount(card);
        charge.setUser(user);
        charge.setKind(CardFeeKind.ANNUAL_FEE);
        charge.setFeeYearStart(LocalDate.of(2025, 7, 1));
        charge.setTransactionIds(Set.of(feeTxn.getId()));

        when(cardFeeChargeRepository.findByAccountId(card.getId())).thenReturn(List.of(charge));
        List<UUID> linkedIds = new ArrayList<>();
        linkedIds.add(feeTxn.getId());
        when(cardFeeChargeRepository.findAllLinkedTransactionIdsByAccountId(card.getId())).thenReturn(linkedIds);
        when(transactionRepository.findForRewardEvaluation(eq(card.getId()), any(), any())).thenReturn(List.of(feeTxn));

        RewardReportResponse report = service.report(card.getId(), LocalDate.of(2026, 3, 1), LocalDate.of(2026, 3, 31));

        assertEquals(BigDecimal.ZERO, report.summary().basisSpend());
        List<RewardLineResponse> lines = service.lines(card.getId(), LocalDate.of(2026, 3, 1), LocalDate.of(2026, 3, 31), null);
        assertEquals(1, lines.size());
        assertEquals(RewardLineReason.CARD_FEE, lines.get(0).reason());
    }

    // 18. Joining fee amortises over its fee year
    @Test
    void test18_joiningFeeAmortisation() {
        CardFeeTerm joining = createTerm(CardFeeKind.JOINING_FEE, BigDecimal.valueOf(1000), LocalDate.of(2025, 7, 1), null, null);
        CardFeeTerm annual = createTerm(CardFeeKind.ANNUAL_FEE, BigDecimal.valueOf(2500), LocalDate.of(2025, 7, 1), null, null);
        when(cardFeeTermRepository.findByAccountIdOrderByEffectiveFromAsc(card.getId())).thenReturn(List.of(joining, annual));

        CardFeeScheduleResponse sched = service.feeSchedule(card.getId(), LocalDate.of(2025, 7, 1), LocalDate.of(2027, 6, 30));
        long joiningOccCount = sched.occurrences().stream().filter(o -> o.kind() == CardFeeKind.JOINING_FEE).count();
        assertEquals(1, joiningOccCount);
    }

    // 19. Anniversary null -> calendar-year fallback
    @Test
    void test19_anniversaryNullFallback() {
        card.setRewardAnniversaryDate(null);
        CardFeeTerm term = createTerm(CardFeeKind.ANNUAL_FEE, BigDecimal.valueOf(2500), LocalDate.of(2025, 1, 1), null, null);
        when(cardFeeTermRepository.findByAccountIdOrderByEffectiveFromAsc(card.getId())).thenReturn(List.of(term));

        CardFeeScheduleResponse sched = service.feeSchedule(card.getId(), LocalDate.of(2025, 1, 1), LocalDate.of(2025, 12, 31));
        assertTrue(sched.unanchoredFees());
        assertEquals(LocalDate.of(2025, 1, 1), sched.occurrences().get(0).feeYearStart());
    }

    // 20. Anniversary edited -> orphan detection
    @Test
    void test20_anniversaryEdited_orphan() {
        card.setRewardAnniversaryDate(LocalDate.of(2025, 8, 1));

        CardFeeCharge newCharge = new CardFeeCharge();
        newCharge.setId(UUID.randomUUID());
        newCharge.setAccount(card);
        newCharge.setUser(user);
        newCharge.setKind(CardFeeKind.ANNUAL_FEE);
        newCharge.setFeeYearStart(LocalDate.of(2025, 8, 1)); // exact match for shifted anniversary

        CardFeeCharge staleCharge = new CardFeeCharge();
        staleCharge.setId(UUID.randomUUID());
        staleCharge.setAccount(card);
        staleCharge.setUser(user);
        staleCharge.setKind(CardFeeKind.ANNUAL_FEE);
        staleCharge.setFeeYearStart(LocalDate.of(2025, 8, 15)); // stale charge inside window

        CardFeeTerm term = createTerm(CardFeeKind.ANNUAL_FEE, BigDecimal.valueOf(2500), LocalDate.of(2025, 8, 1), null, null);
        when(cardFeeTermRepository.findByAccountIdOrderByEffectiveFromAsc(card.getId())).thenReturn(List.of(term));
        when(cardFeeChargeRepository.findByAccountId(card.getId())).thenReturn(List.of(newCharge, staleCharge));

        CardFeeScheduleResponse sched = service.feeSchedule(card.getId(), LocalDate.of(2025, 8, 1), LocalDate.of(2026, 7, 31));
        assertEquals(1, sched.orphanedFeeOverrides().size());
        assertEquals(LocalDate.of(2025, 8, 15), sched.orphanedFeeOverrides().get(0));
    }

    // 21. Feb 29 anniversary non-leap year
    @Test
    void test21_feb29Anniversary() {
        card.setRewardAnniversaryDate(LocalDate.of(2024, 2, 29));
        CardFeeTerm term = createTerm(CardFeeKind.ANNUAL_FEE, BigDecimal.valueOf(2500), LocalDate.of(2024, 2, 29), null, null);
        when(cardFeeTermRepository.findByAccountIdOrderByEffectiveFromAsc(card.getId())).thenReturn(List.of(term));

        CardFeeScheduleResponse sched = service.feeSchedule(card.getId(), LocalDate.of(2025, 2, 1), LocalDate.of(2025, 3, 1));
        assertFalse(sched.occurrences().isEmpty());
    }

    // 22. GST Calculation
    @Test
    void test22_gstCalculation() {
        CardFeeTerm term = createTerm(CardFeeKind.ANNUAL_FEE, BigDecimal.valueOf(2500), LocalDate.of(2025, 7, 1), null, null);
        when(cardFeeTermRepository.findByAccountIdOrderByEffectiveFromAsc(card.getId())).thenReturn(List.of(term));

        CardFeeScheduleResponse sched = service.feeSchedule(card.getId(), LocalDate.of(2025, 7, 1), LocalDate.of(2026, 6, 30));
        FeeOccurrenceResponse occ = sched.occurrences().get(0);
        assertEquals(0, new BigDecimal("2500").compareTo(occ.baseAmount()));
        assertEquals(0, new BigDecimal("450.00").compareTo(occ.gstAmount()));
        assertEquals(0, new BigDecimal("2950.00").compareTo(occ.totalAmount()));
    }

    // 23. waiverSpendIncomplete when dataStart is after waiverStart
    @Test
    void test23_waiverSpendIncomplete() {
        CardFeeTerm term = createTerm(CardFeeKind.ANNUAL_FEE, BigDecimal.valueOf(2500), LocalDate.of(2025, 7, 1), BigDecimal.valueOf(100000), FeeWaiverBasis.SAME_FEE_YEAR);
        when(cardFeeTermRepository.findByAccountIdOrderByEffectiveFromAsc(card.getId())).thenReturn(List.of(term));
        card.setIngestFromDate(LocalDate.of(2025, 9, 1));

        CardFeeScheduleResponse sched = service.feeSchedule(card.getId(), LocalDate.of(2025, 7, 1), LocalDate.of(2026, 6, 30));
        assertTrue(sched.waiverSpendIncomplete());
        assertTrue(sched.occurrences().get(0).waiverSpendIncomplete());
    }

    // 24. NOT_CONFIGURED when terms present but no term in force; NOTHING emitted when 0 terms
    @Test
    void test24_notConfiguredAndNoTerms() {
        CardFeeTerm term = createTerm(CardFeeKind.ANNUAL_FEE, BigDecimal.valueOf(2500), LocalDate.of(2026, 7, 1), null, null);
        when(cardFeeTermRepository.findByAccountIdOrderByEffectiveFromAsc(card.getId())).thenReturn(List.of(term));

        CardFeeScheduleResponse schedA = service.feeSchedule(card.getId(), LocalDate.of(2025, 7, 1), LocalDate.of(2026, 6, 30));
        assertTrue(schedA.notConfiguredFeeYears());
        assertEquals(1, schedA.occurrences().size());
        assertEquals(FeeOccurrenceStatus.NOT_CONFIGURED, schedA.occurrences().get(0).status());

        when(cardFeeTermRepository.findByAccountIdOrderByEffectiveFromAsc(card.getId())).thenReturn(List.of());
        CardFeeScheduleResponse schedB = service.feeSchedule(card.getId(), LocalDate.of(2025, 7, 1), LocalDate.of(2026, 6, 30));
        assertTrue(schedB.occurrences().isEmpty());
    }
}
