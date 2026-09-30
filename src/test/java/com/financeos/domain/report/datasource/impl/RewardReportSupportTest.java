package com.financeos.domain.report.datasource.impl;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.financeos.domain.account.Account;
import com.financeos.domain.account.AccountRepository;
import com.financeos.domain.report.datasource.impl.RewardReportSupport.DateBounds;
import com.financeos.domain.report.datasource.impl.RewardReportSupport.LabelSource;
import com.financeos.domain.reward.RewardMilestone;
import com.financeos.domain.reward.RewardMilestoneRepository;
import com.financeos.domain.reward.RewardRule;
import com.financeos.domain.reward.RewardRuleRepository;
import com.financeos.domain.transaction.TransactionRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

class RewardReportSupportTest {

    private AccountRepository accountRepository;
    private TransactionRepository transactionRepository;
    private RewardRuleRepository ruleRepository;
    private RewardMilestoneRepository milestoneRepository;
    private RewardReportSupport support;

    @BeforeEach
    void setUp() {
        accountRepository = mock(AccountRepository.class);
        transactionRepository = mock(TransactionRepository.class);
        ruleRepository = mock(RewardRuleRepository.class);
        milestoneRepository = mock(RewardMilestoneRepository.class);
        support = new RewardReportSupport(accountRepository, transactionRepository, ruleRepository, milestoneRepository);
    }

    private static LabelSource src(String name, String card, LocalDate from, LocalDate to) {
        return new LabelSource(UUID.randomUUID(), name, card, from, to);
    }

    private static Account account(String name) {
        Account a = new Account();
        a.setId(UUID.randomUUID());
        a.setName(name);
        return a;
    }

    // ---------- disambiguate ----------

    @Test
    void disambiguate_uniqueNameStaysAsIs() {
        LabelSource a = src("Base 1%", "Card A", null, null);
        LabelSource b = src("Dining 5%", "Card A", null, null);
        Map<UUID, String> labels = RewardReportSupport.disambiguate(List.of(a, b));
        assertEquals("Base 1%", labels.get(a.id()));
        assertEquals("Dining 5%", labels.get(b.id()));
    }

    @Test
    void disambiguate_sharedNameAcrossCardsGetsCardSuffix() {
        LabelSource a = src("Base 1%", "Card A", null, null);
        LabelSource b = src("Base 1%", "Card B", null, null);
        Map<UUID, String> labels = RewardReportSupport.disambiguate(List.of(a, b));
        assertEquals("Base 1% · Card A", labels.get(a.id()));
        assertEquals("Base 1% · Card B", labels.get(b.id()));
    }

    @Test
    void disambiguate_sameNameOnOneCardGetsActiveRange() {
        LabelSource old = src("Base", "Card A", null, LocalDate.of(2026, 4, 1));
        LabelSource current = src("Base", "Card A", LocalDate.of(2026, 4, 1), null);
        Map<UUID, String> labels = RewardReportSupport.disambiguate(List.of(old, current));
        // activeTo is exclusive, so the last active day is the day before it.
        assertEquals("Base (start → 2026-03-31)", labels.get(old.id()));
        assertEquals("Base (2026-04-01 → now)", labels.get(current.id()));
    }

    @Test
    void disambiguate_identicalRangesGetNumberedSuffix() {
        LabelSource a = src("Base", "Card A", null, null);
        LabelSource b = src("Base", "Card A", null, null);
        LabelSource c = src("Base", "Card A", null, null);
        Map<UUID, String> labels = RewardReportSupport.disambiguate(List.of(a, b, c));
        assertEquals("Base (start → now)", labels.get(a.id()));
        assertEquals("Base (start → now) #2", labels.get(b.id()));
        assertEquals("Base (start → now) #3", labels.get(c.id()));
    }

    @Test
    void disambiguate_collidingOnOneCardWhileAlsoSpanningCards_appliesCardThenRange() {
        LabelSource a1 = src("Base", "Card A", null, LocalDate.of(2026, 2, 1));
        LabelSource a2 = src("Base", "Card A", LocalDate.of(2026, 2, 1), null);
        LabelSource b = src("Base", "Card B", null, null);
        Map<UUID, String> labels = RewardReportSupport.disambiguate(List.of(a1, a2, b));
        assertEquals("Base · Card A (start → 2026-01-31)", labels.get(a1.id()));
        assertEquals("Base · Card A (2026-02-01 → now)", labels.get(a2.id()));
        assertEquals("Base · Card B", labels.get(b.id()));
    }

    @Test
    void disambiguate_blankOrNullNameBecomesUnnamed() {
        LabelSource blank = src("   ", "Card A", null, null);
        LabelSource nul = src(null, "Card A", LocalDate.of(2026, 1, 1), null);
        LabelSource other = src("Real", "Card A", null, null);
        Map<UUID, String> labels = RewardReportSupport.disambiguate(List.of(blank, nul, other));
        // blank and null collide as "Unnamed" and are told apart by range
        assertEquals("Unnamed (start → now)", labels.get(blank.id()));
        assertEquals("Unnamed (2026-01-01 → now)", labels.get(nul.id()));
        assertEquals("Real", labels.get(other.id()));
    }

    @Test
    void disambiguate_singleBlankNameIsUnnamed() {
        LabelSource blank = src("", "Card A", null, null);
        assertEquals("Unnamed", RewardReportSupport.disambiguate(List.of(blank)).get(blank.id()));
    }

    @Test
    void disambiguate_comparisonIsCaseInsensitiveAndTrimmed() {
        LabelSource a = src("Base 1%", "Card A", null, null);
        LabelSource b = src("  base 1%  ", "Card B", null, null);
        Map<UUID, String> labels = RewardReportSupport.disambiguate(List.of(a, b));
        assertEquals("Base 1% · Card A", labels.get(a.id()));
        // trimmed but original case kept
        assertEquals("base 1% · Card B", labels.get(b.id()));
    }

    // ---------- valueInr ----------

    @Test
    void valueInr_rupeesRoundedHalfUpToTwoDecimals() {
        assertEquals(new BigDecimal("12.35"), RewardReportSupport.valueInr(new BigDecimal("12.345"), "RUPEES", null));
        assertEquals(new BigDecimal("12.34"), RewardReportSupport.valueInr(new BigDecimal("12.344"), "RUPEES", new BigDecimal("0.5")));
    }

    @Test
    void valueInr_pointsAreValuedAtPointValue() {
        assertEquals(new BigDecimal("100.00"),
                RewardReportSupport.valueInr(new BigDecimal("400"), "POINTS", new BigDecimal("0.25")));
    }

    @Test
    void valueInr_unvaluedPointsAreZero() {
        assertEquals(new BigDecimal("0.00"), RewardReportSupport.valueInr(new BigDecimal("400"), "POINTS", null));
    }

    @Test
    void valueInr_nullAmountIsZero() {
        assertEquals(new BigDecimal("0.00"), RewardReportSupport.valueInr(null, "RUPEES", null));
    }

    @Test
    void valueInr_unknownUnitIsZero() {
        assertEquals(new BigDecimal("0.00"), RewardReportSupport.valueInr(new BigDecimal("5"), "MILES", new BigDecimal("1")));
        assertEquals(new BigDecimal("0.00"), RewardReportSupport.valueInr(new BigDecimal("5"), null, new BigDecimal("1")));
    }

    // ---------- bounds ----------

    @Test
    void bounds_nullWhenNoTransactions() {
        UUID id = UUID.randomUUID();
        when(transactionRepository.findMinEffectiveDateByAccountId(id)).thenReturn(null);
        assertNull(support.bounds(id));
    }

    @Test
    void bounds_pastTransactionsEndToday() {
        UUID id = UUID.randomUUID();
        LocalDate min = LocalDate.now().minusDays(90);
        when(transactionRepository.findMinEffectiveDateByAccountId(id)).thenReturn(min);
        when(transactionRepository.findMaxEffectiveDateByAccountId(id)).thenReturn(LocalDate.now().minusDays(3));
        assertEquals(new DateBounds(min, LocalDate.now()), support.bounds(id));
    }

    @Test
    void bounds_nullMaxEndsToday() {
        UUID id = UUID.randomUUID();
        LocalDate min = LocalDate.now().minusDays(10);
        when(transactionRepository.findMinEffectiveDateByAccountId(id)).thenReturn(min);
        when(transactionRepository.findMaxEffectiveDateByAccountId(id)).thenReturn(null);
        assertEquals(new DateBounds(min, LocalDate.now()), support.bounds(id));
    }

    @Test
    void bounds_futureSettlementExtendsTo() {
        UUID id = UUID.randomUUID();
        LocalDate min = LocalDate.now().minusDays(10);
        LocalDate future = LocalDate.now().plusDays(20);
        when(transactionRepository.findMinEffectiveDateByAccountId(id)).thenReturn(min);
        when(transactionRepository.findMaxEffectiveDateByAccountId(id)).thenReturn(future);
        assertEquals(new DateBounds(min, future), support.bounds(id));
    }

    @Test
    void bounds_minAfterTodayIsClampedToTo() {
        UUID id = UUID.randomUUID();
        LocalDate future = LocalDate.now().plusDays(5);
        when(transactionRepository.findMinEffectiveDateByAccountId(id)).thenReturn(future);
        when(transactionRepository.findMaxEffectiveDateByAccountId(id)).thenReturn(future);
        assertEquals(new DateBounds(future, future), support.bounds(id));
    }

    @Test
    void bounds_onlyFutureMinWithNullMaxClampsToToday() {
        UUID id = UUID.randomUUID();
        when(transactionRepository.findMinEffectiveDateByAccountId(id)).thenReturn(LocalDate.now().plusDays(5));
        when(transactionRepository.findMaxEffectiveDateByAccountId(id)).thenReturn(null);
        assertEquals(new DateBounds(LocalDate.now(), LocalDate.now()), support.bounds(id));
    }

    // ---------- accounts ----------

    @Test
    void ruleAccounts_resolvesIdsAndSkipsMissingAccounts() {
        UUID userId = UUID.randomUUID();
        Account a = account("A");
        UUID missing = UUID.randomUUID();
        when(ruleRepository.findDistinctAccountIdsByUserId(userId)).thenReturn(List.of(a.getId(), missing));
        when(accountRepository.findById(a.getId())).thenReturn(Optional.of(a));
        when(accountRepository.findById(missing)).thenReturn(Optional.empty());
        assertEquals(List.of(a), support.ruleAccounts(userId));
    }

    @Test
    void ruleAccounts_emptyIds() {
        UUID userId = UUID.randomUUID();
        when(ruleRepository.findDistinctAccountIdsByUserId(userId)).thenReturn(List.of());
        assertTrue(support.ruleAccounts(userId).isEmpty());
        verifyNoInteractions(accountRepository);
    }

    @Test
    void ruleAccounts_nullIdsTreatedAsEmpty() {
        UUID userId = UUID.randomUUID();
        when(ruleRepository.findDistinctAccountIdsByUserId(userId)).thenReturn(null);
        assertTrue(support.ruleAccounts(userId).isEmpty());
    }

    @Test
    void milestoneAccounts_usesMilestoneRepository() {
        UUID userId = UUID.randomUUID();
        Account a = account("A");
        when(milestoneRepository.findDistinctAccountIdsByUserId(userId)).thenReturn(List.of(a.getId()));
        when(accountRepository.findById(a.getId())).thenReturn(Optional.of(a));
        assertEquals(List.of(a), support.milestoneAccounts(userId));
        verifyNoInteractions(ruleRepository);
    }

    // ---------- labels from repositories ----------

    @Test
    void ruleLabels_buildsFromEachCardsRules() {
        Account a = account("Card A");
        Account b = account("Card B");
        RewardRule r1 = new RewardRule();
        r1.setId(UUID.randomUUID());
        r1.setName("Base");
        RewardRule r2 = new RewardRule();
        r2.setId(UUID.randomUUID());
        r2.setName("Base");
        when(ruleRepository.findByAccountIdOrderByPriorityDesc(a.getId())).thenReturn(List.of(r1));
        when(ruleRepository.findByAccountIdOrderByPriorityDesc(b.getId())).thenReturn(List.of(r2));

        Map<UUID, String> labels = support.ruleLabels(List.of(a, b));
        assertEquals("Base · Card A", labels.get(r1.getId()));
        assertEquals("Base · Card B", labels.get(r2.getId()));
    }

    @Test
    void milestoneLabels_usesActiveRangeForSameNameOnOneCard() {
        Account a = account("Card A");
        RewardMilestone m1 = new RewardMilestone();
        m1.setId(UUID.randomUUID());
        m1.setName("Spend 1L");
        m1.setActiveTo(LocalDate.of(2026, 1, 1));
        RewardMilestone m2 = new RewardMilestone();
        m2.setId(UUID.randomUUID());
        m2.setName("Spend 1L");
        m2.setActiveFrom(LocalDate.of(2026, 1, 1));
        when(milestoneRepository.findByAccountIdOrderByCreatedAtAsc(a.getId())).thenReturn(List.of(m1, m2));

        Map<UUID, String> labels = support.milestoneLabels(List.of(a));
        assertEquals("Spend 1L (start → 2025-12-31)", labels.get(m1.getId()));
        assertEquals("Spend 1L (2026-01-01 → now)", labels.get(m2.getId()));
    }

    // ---------- small helpers ----------

    @Test
    void periodAndLabelHelpers() {
        assertEquals("2026-01-01 → 2026-01-31", RewardReportSupport.period(LocalDate.of(2026, 1, 1), LocalDate.of(2026, 1, 31)));
        assertEquals("x", RewardReportSupport.label("x", "fallback"));
        assertEquals("fallback", RewardReportSupport.label(null, "fallback"));
    }
}
