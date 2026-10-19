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

/**
 * totals() reads every card's inputs with one batch query per kind (not per card) and still equals
 * each card's own report summary, including cards without rules/milestones and with only a milestone.
 */
@ExtendWith(MockitoExtension.class)
class RewardTotalsBatchTest {

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
        r.setPercentRate(new BigDecimal("2.00"));
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

    /** Stubs a card's data for both the batch reads (totals) and the per-card reads (report). */
    private void data(Account card, List<RewardRule> rules, List<RewardMilestone> milestones, List<Transaction> txns) {
        lenient().when(rewardRuleRepository.findByAccountIdOrderByPriorityDesc(card.getId())).thenReturn(rules);
        lenient().when(rewardMilestoneRepository.findByAccountIdOrderByCreatedAtAsc(card.getId())).thenReturn(milestones);
        lenient().when(transactionRepository.findForRewardEvaluation(eq(card.getId()), any(), any())).thenReturn(txns);
    }

    private void batch(List<RewardRule> rules, List<RewardMilestone> milestones, List<Transaction> txns) {
        when(rewardRuleRepository.findByAccountIdInOrderByPriorityDesc(any())).thenReturn(rules);
        when(rewardMilestoneRepository.findByAccountIdInOrderByCreatedAtAsc(any())).thenReturn(milestones);
        when(transactionRepository.findForRewardEvaluationIn(any(), any(), any())).thenReturn(txns);
    }

    @Test
    @SuppressWarnings("unchecked")
    void multiCardTotals_equalEachCardsReportSummary_withBatchedReads() {
        Account ruled = card("Ruled");
        Account bare = card("Bare");            // no rules, no milestones
        Account milestoneOnly = card("Milestone");
        RewardRule rule = twoPercent(ruled);
        RewardMilestone milestone = cashMilestone(milestoneOnly);
        Transaction t1 = spend(ruled, LocalDate.of(2026, 3, 10));
        Transaction t2 = spend(bare, LocalDate.of(2026, 3, 11));
        Transaction t3 = spend(milestoneOnly, LocalDate.of(2026, 3, 12));
        data(ruled, List.of(rule), List.of(), List.of(t1));
        data(bare, List.of(), List.of(), List.of(t2));
        data(milestoneOnly, List.of(), List.of(milestone), List.of(t3));
        batch(List.of(rule), List.of(milestone), List.of(t1, t2, t3));
        when(accountRepository.findByUserIdAndType(user.getId(), AccountType.credit_card))
                .thenReturn(List.of(ruled, bare, milestoneOnly));

        List<RewardTotalResponse> totals = service.totals();

        // One read of each kind for all three cards, and never the per-card reads.
        verify(rewardRuleRepository, times(1)).findByAccountIdInOrderByPriorityDesc(any());
        verify(rewardMilestoneRepository, times(1)).findByAccountIdInOrderByCreatedAtAsc(any());
        verify(cardholderRepository, times(1)).findByAccountIdIn(any());
        verify(statementRepository, times(1)).findByAccountIdInOrderByPeriodEndDescNullsLast(any());
        verify(transactionRepository, times(1)).findForRewardEvaluationIn(any(), any(), any());
        verify(rewardRuleRepository, never()).findByAccountIdOrderByPriorityDesc(any());
        verify(rewardMilestoneRepository, never()).findByAccountIdOrderByCreatedAtAsc(any());
        verify(cardholderRepository, never()).findByAccountId(any());
        verify(statementRepository, never()).findByAccountIdOrderByPeriodEndDescNullsLast(any());
        verify(transactionRepository, never()).findForRewardEvaluation(any(), any(), any());
        // The card with neither rules nor milestones earns nothing, so its transactions are not requested.
        ArgumentCaptor<Collection<UUID>> ids = ArgumentCaptor.forClass(Collection.class);
        verify(transactionRepository).findForRewardEvaluationIn(ids.capture(), any(), any());
        assertThat(Set.copyOf(ids.getValue())).containsExactlyInAnyOrder(ruled.getId(), milestoneOnly.getId());

        assertThat(totals).extracting(RewardTotalResponse::accountId)
                .containsExactly(ruled.getId(), bare.getId(), milestoneOnly.getId());
        assertThat(totals.get(0).grossValueInr()).isEqualByComparingTo("20.00");
        assertThat(totals.get(1).grossValueInr()).isEqualByComparingTo("0");
        assertThat(totals.get(1).points()).isEqualByComparingTo("0");
        assertThat(totals.get(2).grossValueInr()).isEqualByComparingTo("100");
        for (RewardTotalResponse t : totals) {
            RewardReportResponse.Summary s = service.report(t.accountId(), t.from(), t.to()).summary();
            assertThat(t.points()).isEqualByComparingTo(s.points());
            assertThat(t.grossValueInr()).isEqualByComparingTo(s.grossValueInr());
        }
    }

    @Test
    void allCardsWithoutRulesOrMilestones_loadNoTransactions() {
        Account a = card("A");
        Account b = card("B");
        data(a, List.of(), List.of(), List.of(spend(a, LocalDate.of(2026, 3, 10))));
        data(b, List.of(), List.of(), List.of());
        when(rewardRuleRepository.findByAccountIdInOrderByPriorityDesc(any())).thenReturn(List.of());
        when(rewardMilestoneRepository.findByAccountIdInOrderByCreatedAtAsc(any())).thenReturn(List.of());
        when(accountRepository.findByUserIdAndType(user.getId(), AccountType.credit_card)).thenReturn(List.of(a, b));

        List<RewardTotalResponse> totals = service.totals();

        assertThat(totals).hasSize(2);
        assertThat(totals).allSatisfy(t -> assertThat(t.grossValueInr()).isEqualByComparingTo("0"));
        verify(transactionRepository, never()).findForRewardEvaluationIn(any(), any(), any());
    }

    @Test
    void refundLinkWhoseMembersSitOnOppositeSidesOfTheInChunkBoundary_isCountedOnce() {
        Account c = card("Big");
        RewardRule rule = twoPercent(c);
        List<Transaction> txns = new java.util.ArrayList<>();
        Transaction anchor = spend(c, LocalDate.of(2026, 1, 1));
        txns.add(anchor);
        for (int i = 0; i < 999; i++) {
            txns.add(spend(c, LocalDate.of(2026, 1, 2).plusDays(i % 300)));
        }
        Transaction credit = spend(c, LocalDate.of(2026, 12, 30));
        credit.setType(TransactionType.CREDIT);
        credit.setAmount(new BigDecimal("400.00"));
        txns.add(credit); // sorts last: position 1000, the anchor is position 0 (different 900-chunks)

        TransactionLink link = new TransactionLink();
        link.setId(UUID.randomUUID());
        link.setType(LinkType.REFUND);
        link.getMembers().add(new TransactionLinkMember(link, anchor, true));
        link.getMembers().add(new TransactionLinkMember(link, credit, false));
        when(transactionLinkRepository.findLinkIdsByMemberTransactionIds(any())).thenAnswer(inv -> {
            Collection<UUID> chunk = inv.getArgument(0);
            return chunk.contains(anchor.getId()) || chunk.contains(credit.getId()) ? List.of(link.getId()) : List.of();
        });
        when(transactionLinkRepository.findWithMembersByIdIn(any())).thenReturn(List.of(link));
        data(c, List.of(rule), List.of(), txns);
        batch(List.of(rule), List.of(), txns);
        when(accountRepository.findByUserIdAndType(user.getId(), AccountType.credit_card)).thenReturn(List.of(c));

        RewardTotalResponse t = service.totals().get(0);

        // 1000 debits x 1000 x 2% = 20000, less the 400 refund once (8) = 19992; twice would be 19984.
        assertThat(t.grossValueInr()).isEqualByComparingTo("19992.00");
        verify(transactionLinkRepository, times(1)).findWithMembersByIdIn(any());
        assertThat(service.report(t.accountId(), t.from(), t.to()).summary().grossValueInr())
                .isEqualByComparingTo(t.grossValueInr());
    }
}
