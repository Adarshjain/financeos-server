package com.financeos.domain.report.datasource.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.financeos.core.security.UserContext;
import com.financeos.core.time.AppTime;
import com.financeos.domain.account.Account;
import com.financeos.domain.account.AccountRepository;
import com.financeos.domain.report.datasource.impl.RewardReportSupport.DateBounds;
import com.financeos.domain.reward.RewardCalculationService;
import com.financeos.domain.reward.RewardMilestone;
import com.financeos.domain.reward.RewardMilestoneRepository;
import com.financeos.domain.reward.RewardRule;
import com.financeos.domain.reward.RewardRuleRepository;
import com.financeos.domain.transaction.TransactionRepository;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Card lookups, rule/milestone labels and date bounds for several cards run as one query each, and the
 * batched bounds are what the per-card {@code bounds(UUID)} gives.
 */
class RewardReportSupportBatchTest {

    private final AccountRepository accountRepository = mock(AccountRepository.class);
    private final TransactionRepository transactionRepository = mock(TransactionRepository.class);
    private final RewardRuleRepository ruleRepository = mock(RewardRuleRepository.class);
    private final RewardMilestoneRepository milestoneRepository = mock(RewardMilestoneRepository.class);
    private final RewardReportSupport support = new RewardReportSupport(accountRepository, transactionRepository,
            ruleRepository, milestoneRepository);

    @BeforeEach
    void pinClock() {
        AppTime.useClock(java.time.Clock.fixed(LocalDate.of(2026, 6, 15).atStartOfDay(java.time.ZoneId.of("Asia/Kolkata")).toInstant(),
                java.time.ZoneId.of("Asia/Kolkata")));
    }

    @AfterEach
    void tearDown() {
        AppTime.reset();
        UserContext.clear();
    }

    private static Account account(String name) {
        Account a = new Account();
        a.setId(UUID.randomUUID());
        a.setName(name);
        return a;
    }

    private static List<Account> cards(int n) {
        List<Account> out = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            out.add(account("Card " + i));
        }
        return out;
    }

    @Test
    void accountsAreLoadedInOneQueryKeepingTheIdOrderAndSkippingMissing() {
        UUID userId = UUID.randomUUID();
        List<Account> cards = cards(5);
        UUID missing = UUID.randomUUID();
        List<UUID> ids = new ArrayList<>(cards.stream().map(Account::getId).toList());
        ids.add(2, missing);
        when(ruleRepository.findDistinctAccountIdsByUserId(userId)).thenReturn(ids);
        // returned out of order on purpose
        when(accountRepository.findAllById(ids)).thenReturn(List.of(cards.get(4), cards.get(0), cards.get(2), cards.get(1), cards.get(3)));

        assertEquals(cards, support.ruleAccounts(userId));
        verify(accountRepository, times(1)).findAllById(any());
        verify(accountRepository, never()).findById(any());
    }

    @Test
    void ruleLabelsAreReadInOneQueryForAllCards() {
        List<Account> cards = cards(4);
        List<RewardRule> rules = new ArrayList<>();
        for (Account a : cards) {
            RewardRule r = new RewardRule();
            r.setId(UUID.randomUUID());
            r.setName("Base");
            r.setAccount(a);
            rules.add(r);
        }
        when(ruleRepository.findByAccountIdInOrderByPriorityDesc(anyCollection())).thenReturn(rules);

        Map<UUID, String> labels = support.ruleLabels(cards);

        for (int i = 0; i < cards.size(); i++) {
            assertEquals("Base · Card " + i, labels.get(rules.get(i).getId()));
        }
        verify(ruleRepository, times(1)).findByAccountIdInOrderByPriorityDesc(anyCollection());
        verify(ruleRepository, never()).findByAccountIdOrderByPriorityDesc(any());
    }

    @Test
    void milestoneLabelsAreReadInOneQueryForAllCards() {
        List<Account> cards = cards(4);
        List<RewardMilestone> milestones = new ArrayList<>();
        for (Account a : cards) {
            RewardMilestone m = new RewardMilestone();
            m.setId(UUID.randomUUID());
            m.setName("Spend 1L");
            m.setAccount(a);
            milestones.add(m);
        }
        when(milestoneRepository.findByAccountIdInOrderByCreatedAtAsc(anyCollection())).thenReturn(milestones);

        Map<UUID, String> labels = support.milestoneLabels(cards);

        for (int i = 0; i < cards.size(); i++) {
            assertEquals("Spend 1L · Card " + i, labels.get(milestones.get(i).getId()));
        }
        verify(milestoneRepository, times(1)).findByAccountIdInOrderByCreatedAtAsc(anyCollection());
        verify(milestoneRepository, never()).findByAccountIdOrderByCreatedAtAsc(any());
    }

    @Test
    void labelsOfNoCardsRunNoQuery() {
        assertTrue(support.ruleLabels(List.of()).isEmpty());
        assertTrue(support.milestoneLabels(List.of()).isEmpty());
        assertTrue(support.bounds(List.<UUID>of()).isEmpty());
        verify(ruleRepository, never()).findByAccountIdInOrderByPriorityDesc(any());
        verify(milestoneRepository, never()).findByAccountIdInOrderByCreatedAtAsc(any());
        verify(transactionRepository, never()).findEffectiveDateRangesByAccountIds(any());
    }

    @Test
    void batchedBoundsEqualThePerCardBounds() {
        LocalDate today = AppTime.today();
        UUID past = UUID.randomUUID();
        UUID future = UUID.randomUUID();
        UUID onlyFuture = UUID.randomUUID();
        UUID none = UUID.randomUUID();
        LocalDate earliest = today.minusDays(90);
        LocalDate later = today.plusDays(10);
        when(transactionRepository.findEffectiveDateRangesByAccountIds(anyCollection())).thenReturn(List.of(
                new Object[] {past, earliest, today.minusDays(3)},
                new Object[] {future, earliest, later},
                new Object[] {onlyFuture, later, later}));
        when(transactionRepository.effectiveDateSpan(past, today))
                .thenReturn(new TransactionRepository.EffectiveDateSpan(earliest, today));
        when(transactionRepository.effectiveDateSpan(future, today))
                .thenReturn(new TransactionRepository.EffectiveDateSpan(earliest, later));
        when(transactionRepository.effectiveDateSpan(onlyFuture, today))
                .thenReturn(new TransactionRepository.EffectiveDateSpan(later, later));

        Map<UUID, DateBounds> batch = support.bounds(List.of(past, future, onlyFuture, none));

        assertEquals(support.bounds(past), batch.get(past));
        assertEquals(support.bounds(future), batch.get(future));
        assertEquals(support.bounds(onlyFuture), batch.get(onlyFuture));
        assertEquals(new DateBounds(earliest, today), batch.get(past));
        assertEquals(new DateBounds(later, later), batch.get(onlyFuture));
        assertFalse(batch.containsKey(none));
        verify(transactionRepository, times(1)).findEffectiveDateRangesByAccountIds(anyCollection());
    }

    @Test
    void capsDatasourceAsksForAllBoundsOnce() {
        UUID userId = UUID.randomUUID();
        UserContext.setCurrentUserId(userId);
        RewardReportSupport mockSupport = mock(RewardReportSupport.class);
        RewardCalculationService calc = mock(RewardCalculationService.class);
        List<Account> cards = cards(6);
        when(mockSupport.ruleAccounts(userId)).thenReturn(cards);
        when(mockSupport.ruleLabels(cards)).thenReturn(Map.of());
        DateBounds b = new DateBounds(LocalDate.of(2026, 1, 1), LocalDate.of(2026, 3, 31));
        when(mockSupport.bounds(cards.stream().map(Account::getId).toList())).thenReturn(Map.of(
                cards.get(0).getId(), b, cards.get(3).getId(), b));
        when(calc.capUsage(any(), any(), any())).thenReturn(List.of());

        new RewardCapsDatasource(calc, mockSupport).rows();

        verify(mockSupport, times(1)).bounds(anyCollection());
        verify(mockSupport, never()).bounds(any(UUID.class));
        verify(calc, times(1)).capUsage(eq(cards.get(0).getId()), eq(b.from()), eq(b.to()));
        verify(calc, times(1)).capUsage(eq(cards.get(3).getId()), eq(b.from()), eq(b.to()));
        verify(calc, times(2)).capUsage(any(), any(), any());
    }

    @Test
    void milestonesDatasourceAsksForAllBoundsOnceAndKeepsPerCardWindows() {
        UUID userId = UUID.randomUUID();
        UserContext.setCurrentUserId(userId);
        RewardReportSupport mockSupport = mock(RewardReportSupport.class);
        RewardCalculationService calc = mock(RewardCalculationService.class);
        List<Account> cards = cards(5);
        when(mockSupport.milestoneAccounts(userId)).thenReturn(cards);
        when(mockSupport.milestoneLabels(cards)).thenReturn(Map.of());
        DateBounds b = new DateBounds(LocalDate.of(2026, 1, 1), LocalDate.of(2026, 3, 31));
        when(mockSupport.bounds(cards.stream().map(Account::getId).toList())).thenReturn(Map.of(
                cards.get(1).getId(), b));
        when(calc.milestoneStatuses(any(), any(), any())).thenReturn(List.of());

        new RewardMilestonesDatasource(calc, mockSupport).rows();

        verify(mockSupport, times(1)).bounds(anyCollection());
        verify(mockSupport, never()).bounds(any(UUID.class));
        // the card with spend uses its own bounds; the cards without fall back to today
        verify(calc, times(1)).milestoneStatuses(cards.get(1).getId(), b.from(), b.to());
        verify(calc, times(4)).milestoneStatuses(any(), eq(AppTime.today()), eq(AppTime.today()));
    }
}
