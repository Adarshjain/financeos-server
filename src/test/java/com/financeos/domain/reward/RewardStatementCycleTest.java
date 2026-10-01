package com.financeos.domain.reward;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.financeos.api.reward.dto.RewardLineResponse;
import com.financeos.core.security.UserContext;
import com.financeos.domain.account.Account;
import com.financeos.domain.account.AccountRepository;
import com.financeos.domain.account.AccountType;
import com.financeos.domain.account.card.Card;
import com.financeos.domain.account.card.Cardholder;
import com.financeos.domain.account.card.CardholderRelationship;
import com.financeos.domain.account.card.CardholderRepository;
import com.financeos.domain.account.card.CardholderRole;
import com.financeos.domain.reward.RewardCalculationService.CapUsage;
import com.financeos.domain.reward.RewardCalculationService.ReportLine;
import com.financeos.domain.statement.Statement;
import com.financeos.domain.statement.StatementRepository;
import com.financeos.domain.statement.StatementVerdict;
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
 * STATEMENT_CYCLE windows come from BillingCycles: a real statement's period, else a cycle
 * projected from the nearest statement, else the calendar month (only with no statements).
 */
class RewardStatementCycleTest {

    private RewardRuleRepository rewardRuleRepository;
    private TransactionRepository transactionRepository;
    private StatementRepository statementRepository;

    private RewardCalculationService service;
    private User user;
    private Account account;
    private Card card;
    private int seq = 0;

    @BeforeEach
    void setUp() {
        user = new User();
        user.setId(UUID.randomUUID());
        UserContext.setCurrentUserId(user.getId());

        account = new Account("Infinia", AccountType.credit_card);
        account.setId(UUID.randomUUID());
        account.setUser(user);
        account.setRewardAnniversaryDate(LocalDate.of(2025, 6, 1));

        Cardholder holder = new Cardholder();
        holder.setId(UUID.randomUUID());
        holder.setAccount(account);
        holder.setRole(CardholderRole.PRIMARY);
        holder.setPersonName("Primary Holder");
        holder.setRelationship(CardholderRelationship.SELF);
        holder.setOpenedOn(LocalDate.of(2025, 1, 1));
        card = new Card();
        card.setId(UUID.randomUUID());
        card.setAccount(account);
        card.setCardholder(holder);
        card.setLast4("1234");
        card.setIssuedOn(LocalDate.of(2025, 1, 1));
        holder.getCards().add(card);

        rewardRuleRepository = mock(RewardRuleRepository.class);
        transactionRepository = mock(TransactionRepository.class);
        statementRepository = mock(StatementRepository.class);
        AccountRepository accountRepository = mock(AccountRepository.class);
        CardholderRepository cardholderRepository = mock(CardholderRepository.class);
        RewardMilestoneRepository milestoneRepository = mock(RewardMilestoneRepository.class);
        RewardMilestoneService milestoneService = mock(RewardMilestoneService.class);
        TransactionLinkRepository linkRepository = mock(TransactionLinkRepository.class);

        when(accountRepository.findById(account.getId())).thenReturn(Optional.of(account));
        when(cardholderRepository.findByAccountId(account.getId())).thenReturn(List.of(holder));
        when(statementRepository.findByAccountIdOrderByPeriodEndDescNullsLast(any())).thenReturn(List.of());
        when(linkRepository.findLinkIdsByMemberTransactionIds(any())).thenReturn(List.of());
        when(milestoneRepository.findByAccountIdOrderByCreatedAtAsc(any())).thenReturn(List.of());
        when(milestoneService.parseEligibility(any())).thenReturn(MilestoneEligibility.EMPTY);

        service = new RewardCalculationService(rewardRuleRepository, mock(RewardRuleService.class), milestoneRepository,
                milestoneService, transactionRepository, linkRepository, statementRepository, accountRepository,
                cardholderRepository);
    }

    @AfterEach
    void tearDown() {
        UserContext.clear();
    }

    private static LocalDate d(int y, int m, int day) {
        return LocalDate.of(y, m, day);
    }

    private Statement statement(LocalDate start, LocalDate end) {
        Statement s = new Statement();
        s.setPeriodStart(start);
        s.setPeriodEnd(end);
        s.setVerdict(StatementVerdict.AUTO_INGEST);
        return s;
    }

    private void statements(Statement... s) {
        when(statementRepository.findByAccountIdOrderByPeriodEndDescNullsLast(account.getId())).thenReturn(List.of(s));
    }

    /** 5% with a cap of 100 in the STATEMENT_CYCLE window. */
    private void cappedRule() {
        RewardRule r = new RewardRule();
        r.setId(UUID.randomUUID());
        r.setAccount(account);
        r.setName("Dining 5%");
        r.setPriority(100);
        r.setStacking(RuleStacking.EXCLUSIVE);
        r.setRewardType(RewardType.CASH);
        r.setAccrualType(AccrualType.PERCENT);
        r.setPercentRate(new BigDecimal("5"));
        r.setCounterScope(CounterScope.ACCOUNT);
        r.setPeriodCap(new BigDecimal("100"));
        r.setCapWindow(CapWindow.STATEMENT_CYCLE);
        when(rewardRuleRepository.findByAccountIdOrderByPriorityDesc(account.getId())).thenReturn(List.of(r));
    }

    private Transaction spend(String amount, LocalDate date) {
        Transaction t = new Transaction(account, date, new BigDecimal(amount), "Spend",
                TransactionSource.manual, TransactionType.DEBIT, false, false);
        t.setId(new UUID(0L, ++seq));
        t.setUser(user);
        t.setCard(card);
        return t;
    }

    private void txns(Transaction... t) {
        when(transactionRepository.findForRewardEvaluation(eq(account.getId()), any(), any())).thenReturn(List.of(t));
    }

    private RewardLineResponse lineOf(List<RewardLineResponse> lines, Transaction t) {
        return lines.stream().filter(l -> l.transactionId().equals(t.getId())).findFirst().orElseThrow();
    }

    // ---------- projected window mid-cycle ----------

    @Test
    void capDoesNotResetOnTheFirstWhenTheCycleIsProjectedFromTheLastStatement() {
        statements(statement(d(2026, 2, 16), d(2026, 3, 15)));   // closes on the 15th
        cappedRule();
        Transaction a = spend("1000.00", d(2026, 3, 20));        // projected cycle Mar 16 - Apr 15: 50
        Transaction b = spend("1000.00", d(2026, 3, 31));        // 50 -> cap (100) reached
        Transaction c = spend("1000.00", d(2026, 4, 1));         // calendar-month logic would reset here; must not
        txns(a, b, c);

        List<RewardLineResponse> lines = service.lines(account.getId(), d(2026, 3, 16), d(2026, 4, 1), null);

        assertThat(lineOf(lines, a).earned()).isEqualByComparingTo("50");
        assertThat(lineOf(lines, b).earned()).isEqualByComparingTo("50");
        assertThat(lineOf(lines, c).earned()).isEqualByComparingTo("0");
        assertThat(lineOf(lines, c).reason()).isEqualTo(RewardLineReason.CAP_EXHAUSTED);
    }

    @Test
    void capResetsOnTheDayAfterTheProjectedClosingDay() {
        statements(statement(d(2026, 2, 16), d(2026, 3, 15)));
        cappedRule();
        Transaction a = spend("2000.00", d(2026, 4, 15));        // last day of Mar 16 - Apr 15: 100 (cap)
        Transaction b = spend("1000.00", d(2026, 4, 16));        // new projected window Apr 16 - May 15: 50
        txns(a, b);

        List<RewardLineResponse> lines = service.lines(account.getId(), d(2026, 4, 15), d(2026, 4, 16), null);

        assertThat(lineOf(lines, a).earned()).isEqualByComparingTo("100");
        assertThat(lineOf(lines, b).earned()).isEqualByComparingTo("50");
    }

    @Test
    void capUsageReportsTheProjectedWindowWithoutFallback() {
        statements(statement(d(2026, 2, 16), d(2026, 3, 15)));
        cappedRule();
        txns(spend("1000.00", d(2026, 3, 20)), spend("1000.00", d(2026, 3, 31)), spend("1000.00", d(2026, 4, 1)));

        List<CapUsage> usage = service.capUsage(account.getId(), d(2026, 3, 16), d(2026, 4, 1));

        assertThat(usage).hasSize(1);
        CapUsage u = usage.get(0);
        assertThat(u.window()).isEqualTo(CapWindow.STATEMENT_CYCLE);
        assertThat(u.windowStart()).isEqualTo(d(2026, 3, 16));
        assertThat(u.windowEnd()).isEqualTo(d(2026, 4, 15));
        assertThat(u.cycleFallback()).isFalse();
        assertThat(u.used()).isEqualByComparingTo("100");
    }

    @Test
    void reportLineCycleBoundsAreTheProjectedWindow() {
        statements(statement(d(2026, 2, 16), d(2026, 3, 15)));
        cappedRule();
        txns(spend("1000.00", d(2026, 3, 20)));

        ReportLine line = service.reportLines(account.getId(), d(2026, 3, 16), d(2026, 4, 15)).get(0);

        assertThat(line.cycleStart()).isEqualTo(d(2026, 3, 16));
        assertThat(line.cycleEnd()).isEqualTo(d(2026, 4, 15));
    }

    @Test
    void spendInsideARealStatementUsesThatStatementNotAProjection() {
        statements(statement(d(2026, 2, 16), d(2026, 3, 15)));
        cappedRule();
        Transaction in = spend("1000.00", d(2026, 3, 1));
        txns(in);

        List<CapUsage> usage = service.capUsage(account.getId(), d(2026, 2, 16), d(2026, 3, 15));

        assertThat(usage.get(0).windowStart()).isEqualTo(d(2026, 2, 16));
        assertThat(usage.get(0).windowEnd()).isEqualTo(d(2026, 3, 15));
        assertThat(usage.get(0).cycleFallback()).isFalse();
    }

    @Test
    void rejectedStatementsDoNotAnchorTheProjection() {
        Statement rejected = statement(d(2026, 2, 16), d(2026, 3, 15));
        rejected.setVerdict(StatementVerdict.REJECTED);
        statements(rejected);
        cappedRule();
        txns(spend("1000.00", d(2026, 3, 20)));

        CapUsage u = service.capUsage(account.getId(), d(2026, 3, 1), d(2026, 3, 31)).get(0);

        assertThat(u.cycleFallback()).isTrue();
        assertThat(u.windowStart()).isEqualTo(d(2026, 3, 1));
    }

    // ---------- fallback flag ----------

    @Test
    void fallbackIsFlaggedOnlyWhenTheCardHasNoStatements() {
        cappedRule();
        txns(spend("1000.00", d(2026, 3, 20)));

        CapUsage u = service.capUsage(account.getId(), d(2026, 3, 1), d(2026, 3, 31)).get(0);

        assertThat(u.cycleFallback()).isTrue();
        assertThat(u.windowStart()).isEqualTo(d(2026, 3, 1));
        assertThat(u.windowEnd()).isEqualTo(d(2026, 3, 31));
    }

    @Test
    void noStatementsCapResetsOnTheFirstOfTheMonth() {
        cappedRule();
        Transaction a = spend("2000.00", d(2026, 3, 31));        // 100 (cap)
        Transaction b = spend("1000.00", d(2026, 4, 1));         // new calendar month: 50
        txns(a, b);

        List<RewardLineResponse> lines = service.lines(account.getId(), d(2026, 3, 31), d(2026, 4, 1), null);

        assertThat(lineOf(lines, a).earned()).isEqualByComparingTo("100");
        assertThat(lineOf(lines, b).earned()).isEqualByComparingTo("50");
    }

    @Test
    void projectedCycleBeforeTheFirstStatementIsNotAFallback() {
        statements(statement(d(2026, 3, 16), d(2026, 4, 15)));
        cappedRule();
        txns(spend("1000.00", d(2026, 3, 1)));

        CapUsage u = service.capUsage(account.getId(), d(2026, 3, 1), d(2026, 3, 15)).get(0);

        assertThat(u.cycleFallback()).isFalse();
        assertThat(u.windowStart()).isEqualTo(d(2026, 2, 16));
        assertThat(u.windowEnd()).isEqualTo(d(2026, 3, 15));
    }

    // ---------- gap between statements ----------

    @Test
    void projectedWindowInAGapIsCappedBeforeTheNextStatement() {
        // Jan 16 - Feb 15 and Mar 1 - Mar 31 imported; Feb 16 - Feb 28 is the gap.
        statements(statement(d(2026, 1, 16), d(2026, 2, 15)), statement(d(2026, 3, 1), d(2026, 3, 31)));
        cappedRule();
        Transaction gapA = spend("2000.00", d(2026, 2, 20));     // gap window: 100 (cap)
        Transaction gapB = spend("1000.00", d(2026, 2, 28));     // same window: cap exhausted
        Transaction next = spend("1000.00", d(2026, 3, 1));      // next statement window: fresh cap, 50
        txns(gapA, gapB, next);

        List<RewardLineResponse> lines = service.lines(account.getId(), d(2026, 2, 16), d(2026, 3, 1), null);

        assertThat(lineOf(lines, gapA).earned()).isEqualByComparingTo("100");
        assertThat(lineOf(lines, gapB).earned()).isEqualByComparingTo("0");
        assertThat(lineOf(lines, next).earned()).isEqualByComparingTo("50");
    }

    @Test
    void gapCycleBoundsInReportLinesNeverOverlapTheNextStatement() {
        statements(statement(d(2026, 1, 16), d(2026, 2, 15)), statement(d(2026, 3, 1), d(2026, 3, 31)));
        cappedRule();
        txns(spend("1000.00", d(2026, 2, 20)));

        ReportLine line = service.reportLines(account.getId(), d(2026, 2, 16), d(2026, 2, 28)).get(0);

        assertThat(line.cycleStart()).isEqualTo(d(2026, 2, 16));
        assertThat(line.cycleEnd()).isEqualTo(d(2026, 2, 28));
    }
}
