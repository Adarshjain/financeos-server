package com.financeos.domain.reward;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

import com.financeos.api.reward.dto.RewardLineResponse;
import com.financeos.api.reward.dto.RewardReportResponse;
import com.financeos.core.security.UserContext;
import com.financeos.domain.account.Account;
import com.financeos.domain.account.AccountRepository;
import com.financeos.domain.account.AccountType;
import com.financeos.domain.account.card.Card;
import com.financeos.domain.account.card.Cardholder;
import com.financeos.domain.account.card.CardholderRelationship;
import com.financeos.domain.account.card.CardholderRepository;
import com.financeos.domain.account.card.CardholderRole;
import com.financeos.domain.category.Category;
import com.financeos.domain.reward.RewardCalculationService.CapUsage;
import com.financeos.domain.reward.RewardCalculationService.ReportLine;
import com.financeos.domain.statement.Statement;
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
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Behaviours added for the reward report datasources: NO_RULE suppression when only additive
 * rules match, zero-line units, points valuation in the summary, and the reportLines /
 * milestoneStatuses / capUsage methods.
 */
class RewardReportsEngineTest {

    private static final LocalDate MAR_15 = LocalDate.of(2026, 3, 15);
    private static final LocalDate MAR_1 = LocalDate.of(2026, 3, 1);
    private static final LocalDate MAR_31 = LocalDate.of(2026, 3, 31);

    private RewardRuleRepository rewardRuleRepository;
    private RewardMilestoneRepository rewardMilestoneRepository;
    private RewardMilestoneService rewardMilestoneService;
    private TransactionRepository transactionRepository;
    private TransactionLinkRepository transactionLinkRepository;
    private StatementRepository statementRepository;
    private AccountRepository accountRepository;
    private CardholderRepository cardholderRepository;

    private RewardCalculationService service;
    private User user;
    private Account account;
    private Cardholder primary;
    private Cardholder addon;
    private Card primaryCard;
    private Card addonCard;
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

        primary = holder("Primary Holder", CardholderRole.PRIMARY, CardholderRelationship.SELF);
        addon = holder("Wife", CardholderRole.ADDON, CardholderRelationship.SPOUSE);
        primaryCard = card(primary, "1234");
        addonCard = card(addon, "5678");

        rewardRuleRepository = mock(RewardRuleRepository.class);
        rewardMilestoneRepository = mock(RewardMilestoneRepository.class);
        rewardMilestoneService = mock(RewardMilestoneService.class);
        transactionRepository = mock(TransactionRepository.class);
        transactionLinkRepository = mock(TransactionLinkRepository.class);
        statementRepository = mock(StatementRepository.class);
        accountRepository = mock(AccountRepository.class);
        cardholderRepository = mock(CardholderRepository.class);

        when(accountRepository.findById(account.getId())).thenReturn(Optional.of(account));
        when(cardholderRepository.findByAccountId(account.getId())).thenReturn(List.of(primary, addon));
        when(statementRepository.findByAccountIdOrderByPeriodEndDescNullsLast(any())).thenReturn(List.of());
        when(transactionLinkRepository.findLinkIdsByMemberTransactionIds(any())).thenReturn(List.of());
        when(rewardMilestoneRepository.findByAccountIdOrderByCreatedAtAsc(any())).thenReturn(List.of());
        when(rewardRuleRepository.findByAccountIdOrderByPriorityDesc(any())).thenReturn(List.of());
        when(rewardMilestoneService.parseEligibility(any())).thenReturn(MilestoneEligibility.EMPTY);

        service = new RewardCalculationService(
                rewardRuleRepository, mock(RewardRuleService.class), rewardMilestoneRepository, rewardMilestoneService,
                transactionRepository, transactionLinkRepository, statementRepository, accountRepository,
                cardholderRepository);
    }

    @AfterEach
    void tearDown() {
        UserContext.clear();
    }

    // ---------- fixtures ----------

    private Cardholder holder(String name, CardholderRole role, CardholderRelationship relationship) {
        Cardholder c = new Cardholder();
        c.setId(UUID.randomUUID());
        c.setAccount(account);
        c.setRole(role);
        c.setPersonName(name);
        c.setRelationship(relationship);
        c.setOpenedOn(LocalDate.of(2025, 1, 1));
        return c;
    }

    private Card card(Cardholder holder, String last4) {
        Card c = new Card();
        c.setId(UUID.randomUUID());
        c.setAccount(account);
        c.setCardholder(holder);
        c.setLast4(last4);
        c.setIssuedOn(LocalDate.of(2025, 1, 1));
        holder.getCards().add(c);
        return c;
    }

    private Transaction txn(String amount, Card card, LocalDate date) {
        Transaction t = new Transaction(account, date, new BigDecimal(amount), "Test spend",
                TransactionSource.manual, TransactionType.DEBIT, false, false);
        t.setId(new UUID(0L, ++seq));
        t.setUser(user);
        t.setCard(card);
        return t;
    }

    private RewardRule rule(String name, RuleStacking stacking, String percent) {
        RewardRule r = new RewardRule();
        r.setId(UUID.randomUUID());
        r.setAccount(account);
        r.setName(name);
        r.setPriority(100);
        r.setStacking(stacking);
        r.setRewardType(RewardType.CASH);
        r.setAccrualType(AccrualType.PERCENT);
        r.setPercentRate(new BigDecimal(percent));
        r.setCounterScope(CounterScope.ACCOUNT);
        return r;
    }

    /** 2 points per Rs 100 of basis. */
    private RewardRule pointsRule(String name, RuleStacking stacking) {
        RewardRule r = rule(name, stacking, "1");
        r.setRewardType(RewardType.POINTS);
        r.setAccrualType(AccrualType.SLAB);
        r.setPercentRate(null);
        r.setSlabSize(new BigDecimal("100"));
        r.setPointsPerSlab(new BigDecimal("2"));
        return r;
    }

    private RewardRule cappedRule(String name, String percent, String cap, CapWindow window, CounterScope scope) {
        RewardRule r = rule(name, RuleStacking.EXCLUSIVE, percent);
        r.setPeriodCap(new BigDecimal(cap));
        r.setCapWindow(window);
        r.setCounterScope(scope);
        return r;
    }

    private Category category(String name) {
        Category c = new Category(name, user);
        c.setId(UUID.randomUUID());
        return c;
    }

    private void rules(RewardRule... rules) {
        when(rewardRuleRepository.findByAccountIdOrderByPriorityDesc(account.getId())).thenReturn(List.of(rules));
    }

    private void txns(Transaction... txns) {
        when(transactionRepository.findForRewardEvaluation(eq(account.getId()), any(), any())).thenReturn(List.of(txns));
    }

    private List<RewardLineResponse> lines(LocalDate from, LocalDate to) {
        return service.lines(account.getId(), from, to, null);
    }

    // ---------- NO_RULE suppression ----------

    @Test
    void onlyAdditiveRuleMatches_emitsNoNoRuleLine() {
        rules(rule("Bonus 1%", RuleStacking.ADDITIVE, "1"));
        Transaction t = txn("1000.00", primaryCard, MAR_15);
        txns(t);

        List<RewardLineResponse> lines = lines(MAR_15, MAR_15);

        assertThat(lines).hasSize(1);
        assertThat(lines.get(0).ruleName()).isEqualTo("Bonus 1%");
        assertThat(lines.get(0).reason()).isEqualTo(RewardLineReason.MATCHED);
        assertThat(lines).noneMatch(l -> l.reason() == RewardLineReason.NO_RULE);
    }

    @Test
    void nothingMatches_emitsSingleNoRuleLine() {
        RewardRule addonOnly = rule("Add-on only", RuleStacking.EXCLUSIVE, "2");
        addonOnly.setCardholder(addon);
        rules(addonOnly);
        txns(txn("1000.00", primaryCard, MAR_15));

        List<RewardLineResponse> lines = lines(MAR_15, MAR_15);

        assertThat(lines).hasSize(1);
        assertThat(lines.get(0).reason()).isEqualTo(RewardLineReason.NO_RULE);
        assertThat(lines.get(0).ruleId()).isNull();
    }

    @Test
    void noRulesAtAll_emitsNoRuleLine() {
        txns(txn("1000.00", primaryCard, MAR_15));
        assertThat(lines(MAR_15, MAR_15)).extracting(RewardLineResponse::reason).containsExactly(RewardLineReason.NO_RULE);
    }

    @Test
    void exclusiveAndAdditiveMatch_twoLinesAndNoNoRule() {
        rules(rule("Base 1%", RuleStacking.EXCLUSIVE, "1"), rule("Bonus 2%", RuleStacking.ADDITIVE, "2"));
        txns(txn("1000.00", primaryCard, MAR_15));

        List<RewardLineResponse> lines = lines(MAR_15, MAR_15);

        assertThat(lines).hasSize(2);
        assertThat(lines).extracting(RewardLineResponse::ruleName).containsExactly("Base 1%", "Bonus 2%");
        assertThat(lines).noneMatch(l -> l.reason() == RewardLineReason.NO_RULE);
    }

    @Test
    void exclusiveCapExhaustedWithFallThroughAndAdditive_keepsCapExhaustedLineAndAdditive() {
        RewardRule exclusive = cappedRule("Capped 5%", "5", "10", CapWindow.CALENDAR_MONTH, CounterScope.ACCOUNT);
        rules(exclusive, rule("Bonus 1%", RuleStacking.ADDITIVE, "1"));
        Transaction t1 = txn("1000.00", primaryCard, MAR_15);   // 50 -> clamped to 10
        Transaction t2 = txn("1000.00", primaryCard, MAR_15);   // cap gone
        txns(t1, t2);

        List<RewardLineResponse> forSecond = lines(MAR_15, MAR_15).stream()
                .filter(l -> l.transactionId().equals(t2.getId())).toList();

        assertThat(forSecond).extracting(RewardLineResponse::reason)
                .containsExactly(RewardLineReason.CAP_EXHAUSTED, RewardLineReason.MATCHED);
    }

    // ---------- zero-line unit ----------

    @Test
    void zeroLineUnit_isPointsWhenCardDefaultsToPoints() {
        account.setDefaultRewardType(RewardType.POINTS);
        Transaction excluded = txn("500.00", primaryCard, MAR_15);
        excluded.setTransactionExcluded(true);
        txns(txn("1000.00", primaryCard, MAR_15), excluded);

        List<RewardLineResponse> lines = lines(MAR_15, MAR_15);

        assertThat(lines).extracting(RewardLineResponse::reason)
                .containsExactlyInAnyOrder(RewardLineReason.NO_RULE, RewardLineReason.TXN_EXCLUDED);
        assertThat(lines).allMatch(l -> "POINTS".equals(l.earnedUnit()));
    }

    @Test
    void zeroLineUnit_isRupeesWhenCardDefaultsToCash() {
        account.setDefaultRewardType(RewardType.CASH);
        txns(txn("1000.00", primaryCard, MAR_15));
        assertThat(lines(MAR_15, MAR_15).get(0).earnedUnit()).isEqualTo("RUPEES");
    }

    // ---------- summary ----------

    private RewardMilestone pointsMilestone() {
        RewardMilestone m = new RewardMilestone();
        m.setId(UUID.randomUUID());
        m.setAccount(account);
        m.setName("Bonus points");
        m.setWindowType(MilestoneWindow.CALENDAR_MONTH);
        m.setBasis(MilestoneBasis.SPEND);
        m.setThreshold(new BigDecimal("500"));
        m.setRewardType(RewardType.POINTS);
        m.setPayoutType(MilestonePayoutType.CASH_VALUE);
        m.setPayoutValue(new BigDecimal("100"));
        m.setPayoutTiming(MilestonePayoutTiming.WINDOW_END);
        return m;
    }

    @Test
    void summary_valuesPointsAndIncludesThemInGross() {
        account.setPointValueInr(new BigDecimal("0.50"));
        rules(rule("Cash 1%", RuleStacking.EXCLUSIVE, "1"), pointsRule("Points", RuleStacking.ADDITIVE));
        txns(txn("1000.00", primaryCard, MAR_15));   // 10.00 cash + 20 points

        RewardReportResponse.Summary s = service.report(account.getId(), MAR_1, MAR_31).summary();

        assertThat(s.cashbackInr()).isEqualByComparingTo("10.00");
        assertThat(s.points()).isEqualByComparingTo("20");
        assertThat(s.pointsValueInr()).isEqualByComparingTo("10.00");
        assertThat(s.pointsValueInr().scale()).isEqualTo(2);
        assertThat(s.grossValueInr()).isEqualByComparingTo("20.00");
        assertThat(s.effectiveValueInr()).isEqualByComparingTo("20.00");
        assertThat(s.grossPct()).isEqualByComparingTo("2.00");
        assertThat(s.effectivePct()).isEqualByComparingTo("2.00");
    }

    @Test
    void summary_withoutPointValue_pointsValueIsNullAndGrossIsCashOnly() {
        account.setPointValueInr(null);
        rules(rule("Cash 1%", RuleStacking.EXCLUSIVE, "1"), pointsRule("Points", RuleStacking.ADDITIVE));
        txns(txn("1000.00", primaryCard, MAR_15));

        RewardReportResponse.Summary s = service.report(account.getId(), MAR_1, MAR_31).summary();

        assertThat(s.pointsValueInr()).isNull();
        assertThat(s.points()).isEqualByComparingTo("20");
        assertThat(s.grossValueInr()).isEqualByComparingTo("10.00");
        assertThat(s.effectiveValueInr()).isEqualByComparingTo("10.00");
    }

    @Test
    void summary_valuesMilestonePointsToo() {
        account.setPointValueInr(new BigDecimal("0.50"));
        rules(rule("Cash 1%", RuleStacking.EXCLUSIVE, "1"), pointsRule("Points", RuleStacking.ADDITIVE));
        when(rewardMilestoneRepository.findByAccountIdOrderByCreatedAtAsc(account.getId())).thenReturn(List.of(pointsMilestone()));
        txns(txn("1000.00", primaryCard, MAR_15));

        RewardReportResponse.Summary s = service.report(account.getId(), MAR_1, MAR_31).summary();

        assertThat(s.milestonesPts()).isEqualByComparingTo("100");
        assertThat(s.milestonesInr()).isEqualByComparingTo("0");
        // (20 line points + 100 milestone points) x 0.50
        assertThat(s.pointsValueInr()).isEqualByComparingTo("60.00");
        assertThat(s.grossValueInr()).isEqualByComparingTo("70.00");
    }

    @Test
    void summary_compatConstructorLeavesPointsValueNull() {
        RewardReportResponse.Summary s = new RewardReportResponse.Summary(
                BigDecimal.ONE, 1, 1, BigDecimal.ONE, BigDecimal.ONE, BigDecimal.ONE, BigDecimal.ONE, BigDecimal.ONE,
                BigDecimal.ONE, BigDecimal.ONE, BigDecimal.ONE, BigDecimal.ONE, BigDecimal.ONE);
        assertThat(s.pointsValueInr()).isNull();
    }

    // ---------- reportLines ----------

    private List<ReportLine> reportLines(LocalDate from, LocalDate to) {
        return service.reportLines(account.getId(), from, to);
    }

    @Test
    void reportLines_twoLineTransaction_firstLineIsPrimaryAndBothCarryTransactionFacts() {
        rules(rule("Base 1%", RuleStacking.EXCLUSIVE, "1"), rule("Bonus 2%", RuleStacking.ADDITIVE, "2"));
        Transaction t = txn("1000.00", primaryCard, MAR_15);
        t.setInstantDiscount(new BigDecimal("25.00"));
        t.setConvenienceFee(new BigDecimal("10.00"));
        txns(t);

        List<ReportLine> out = reportLines(MAR_1, MAR_31);

        assertThat(out).hasSize(2);
        assertThat(out.get(0).primary()).isTrue();
        assertThat(out.get(1).primary()).isFalse();
        assertThat(out).allSatisfy(r -> {
            assertThat(r.eligible()).isTrue();
            assertThat(r.spend()).isEqualByComparingTo("1000.00");
            assertThat(r.instantDiscount()).isEqualByComparingTo("25.00");
            assertThat(r.convenienceFee()).isEqualByComparingTo("10.00");
        });
        assertThat(out.get(0).line().ruleName()).isEqualTo("Base 1%");
    }

    @Test
    void reportLines_eachTransactionHasItsOwnPrimary() {
        rules(rule("Base 1%", RuleStacking.EXCLUSIVE, "1"));
        txns(txn("1000.00", primaryCard, MAR_15), txn("500.00", primaryCard, MAR_15));
        assertThat(reportLines(MAR_1, MAR_31)).extracting(ReportLine::primary).containsExactly(true, true);
    }

    @Test
    void reportLines_transactionWithoutDiscountOrFeeHasNulls() {
        rules(rule("Base 1%", RuleStacking.EXCLUSIVE, "1"));
        txns(txn("1000.00", primaryCard, MAR_15));
        ReportLine r = reportLines(MAR_1, MAR_31).get(0);
        assertThat(r.instantDiscount()).isNull();
        assertThat(r.convenienceFee()).isNull();
    }

    @Test
    void reportLines_excludedTransactionIsIneligibleWithZeroSpendAndNullDiscountFee() {
        rules(rule("Base 1%", RuleStacking.EXCLUSIVE, "1"));
        Transaction t = txn("1000.00", primaryCard, MAR_15);
        t.setTransactionExcluded(true);
        t.setInstantDiscount(new BigDecimal("25.00"));
        t.setConvenienceFee(new BigDecimal("10.00"));
        txns(t);

        List<ReportLine> out = reportLines(MAR_1, MAR_31);

        assertThat(out).hasSize(1);
        assertThat(out.get(0).line().reason()).isEqualTo(RewardLineReason.TXN_EXCLUDED);
        assertThat(out.get(0).primary()).isTrue();
        assertThat(out.get(0).eligible()).isFalse();
        assertThat(out.get(0).spend()).isEqualByComparingTo("0");
        assertThat(out.get(0).instantDiscount()).isNull();
        assertThat(out.get(0).convenienceFee()).isNull();
    }

    private void refundLink(LinkType type, Transaction anchorTxn, Transaction credit) {
        TransactionLink link = new TransactionLink();
        link.setId(UUID.randomUUID());
        link.setType(type);
        link.getMembers().add(new TransactionLinkMember(link, anchorTxn, true));
        if (credit != null) {
            link.getMembers().add(new TransactionLinkMember(link, credit, false));
        }
        when(transactionLinkRepository.findLinkIdsByMemberTransactionIds(any())).thenReturn(List.of(link.getId()));
        when(transactionLinkRepository.findWithMembersByIdIn(any())).thenReturn(List.of(link));
    }

    private Transaction credit(String amount) {
        Transaction c = txn(amount, primaryCard, MAR_15);
        c.setType(TransactionType.CREDIT);
        return c;
    }

    @Test
    void reportLines_partialRefundNetsSpend() {
        rules(rule("Base 1%", RuleStacking.EXCLUSIVE, "1"));
        Transaction t = txn("1000.00", primaryCard, MAR_15);
        refundLink(LinkType.REFUND, t, credit("400.00"));
        txns(t);

        ReportLine r = reportLines(MAR_1, MAR_31).get(0);
        assertThat(r.eligible()).isTrue();
        assertThat(r.spend()).isEqualByComparingTo("600.00");
    }

    @Test
    void reportLines_fullyRefundedIsIneligible() {
        rules(rule("Base 1%", RuleStacking.EXCLUSIVE, "1"));
        Transaction t = txn("1000.00", primaryCard, MAR_15);
        refundLink(LinkType.REFUND, t, credit("1000.00"));
        txns(t);

        ReportLine r = reportLines(MAR_1, MAR_31).get(0);
        assertThat(r.line().reason()).isEqualTo(RewardLineReason.FULLY_REFUNDED);
        assertThat(r.eligible()).isFalse();
        assertThat(r.spend()).isEqualByComparingTo("0");
    }

    @Test
    void reportLines_transferLegIsIneligible() {
        rules(rule("Base 1%", RuleStacking.EXCLUSIVE, "1"));
        Transaction t = txn("1000.00", primaryCard, MAR_15);
        refundLink(LinkType.CC_PAYMENT, t, null);
        txns(t);

        ReportLine r = reportLines(MAR_1, MAR_31).get(0);
        assertThat(r.line().reason()).isEqualTo(RewardLineReason.TRANSFER_OR_PAYMENT);
        assertThat(r.eligible()).isFalse();
    }

    @Test
    void reportLines_linesOutsideTheRangeAreExcluded() {
        rules(rule("Base 1%", RuleStacking.EXCLUSIVE, "1"));
        Transaction inRange = txn("1000.00", primaryCard, MAR_15);
        Transaction before = txn("1000.00", primaryCard, LocalDate.of(2026, 2, 28));
        Transaction after = txn("1000.00", primaryCard, LocalDate.of(2026, 4, 1));
        txns(before, inRange, after);

        List<ReportLine> out = reportLines(MAR_1, MAR_31);

        assertThat(out).hasSize(1);
        assertThat(out.get(0).line().transactionId()).isEqualTo(inRange.getId());
    }

    @Test
    void reportLines_rangeBoundsAreInclusive() {
        rules(rule("Base 1%", RuleStacking.EXCLUSIVE, "1"));
        txns(txn("100.00", primaryCard, MAR_1), txn("100.00", primaryCard, MAR_31));
        assertThat(reportLines(MAR_1, MAR_31)).hasSize(2);
    }

    @Test
    void reportLines_categoriesAreDistinctSortedIgnoringCase() {
        rules(rule("Base 1%", RuleStacking.EXCLUSIVE, "1"));
        Transaction t = txn("1000.00", primaryCard, MAR_15);
        t.setCategories(Set.of(category("travel"), category("Dining"), category("Travel-2")));
        txns(t);

        assertThat(reportLines(MAR_1, MAR_31).get(0).categories()).containsExactly("Dining", "travel", "Travel-2");
    }

    @Test
    void reportLines_sameCategoryNameOnTwoCategoryRowsIsDeduplicated() {
        rules(rule("Base 1%", RuleStacking.EXCLUSIVE, "1"));
        Transaction t = txn("1000.00", primaryCard, MAR_15);
        t.setCategories(new HashSet<>(Set.of(category("Dining"), category("Dining"))));
        txns(t);

        assertThat(reportLines(MAR_1, MAR_31).get(0).categories()).containsExactly("Dining");
    }

    @Test
    void reportLines_uncategorisedTransactionHasEmptyCategories() {
        rules(rule("Base 1%", RuleStacking.EXCLUSIVE, "1"));
        txns(txn("1000.00", primaryCard, MAR_15));
        assertThat(reportLines(MAR_1, MAR_31).get(0).categories()).isEmpty();
    }

    @Test
    void reportLines_cycleUsesTheCoveringStatement() {
        Statement covering = new Statement();
        covering.setPeriodStart(LocalDate.of(2026, 2, 16));
        covering.setPeriodEnd(LocalDate.of(2026, 3, 15));
        Statement rejected = new Statement();
        rejected.setPeriodStart(LocalDate.of(2026, 3, 1));
        rejected.setPeriodEnd(LocalDate.of(2026, 3, 31));
        rejected.setVerdict(com.financeos.domain.statement.StatementVerdict.REJECTED);
        when(statementRepository.findByAccountIdOrderByPeriodEndDescNullsLast(account.getId()))
                .thenReturn(List.of(rejected, covering));
        rules(rule("Base 1%", RuleStacking.EXCLUSIVE, "1"));
        txns(txn("1000.00", primaryCard, MAR_15));

        ReportLine r = reportLines(MAR_1, MAR_31).get(0);
        assertThat(r.cycleStart()).isEqualTo(LocalDate.of(2026, 2, 16));
        assertThat(r.cycleEnd()).isEqualTo(LocalDate.of(2026, 3, 15));
    }

    @Test
    void reportLines_cycleFallsBackToCalendarMonthWithoutAStatement() {
        rules(rule("Base 1%", RuleStacking.EXCLUSIVE, "1"));
        txns(txn("1000.00", primaryCard, MAR_15));

        ReportLine r = reportLines(MAR_1, MAR_31).get(0);
        assertThat(r.cycleStart()).isEqualTo(MAR_1);
        assertThat(r.cycleEnd()).isEqualTo(MAR_31);
    }

    @Test
    void reportLines_rewardYearFollowsTheAnniversary() {
        // anniversary 1 Jun: 15 Mar 2026 is in 1 Jun 2025 .. 31 May 2026
        rules(rule("Base 1%", RuleStacking.EXCLUSIVE, "1"));
        txns(txn("1000.00", primaryCard, MAR_15), txn("1000.00", primaryCard, LocalDate.of(2026, 7, 1)));

        List<ReportLine> out = reportLines(MAR_1, LocalDate.of(2026, 7, 31));

        assertThat(out.get(0).rewardYearStart()).isEqualTo(LocalDate.of(2025, 6, 1));
        assertThat(out.get(0).rewardYearEnd()).isEqualTo(LocalDate.of(2026, 5, 31));
        assertThat(out.get(1).rewardYearStart()).isEqualTo(LocalDate.of(2026, 6, 1));
        assertThat(out.get(1).rewardYearEnd()).isEqualTo(LocalDate.of(2027, 5, 31));
    }

    @Test
    void reportLines_rewardYearFallsBackToCalendarYearWithoutAnniversary() {
        account.setRewardAnniversaryDate(null);
        rules(rule("Base 1%", RuleStacking.EXCLUSIVE, "1"));
        txns(txn("1000.00", primaryCard, MAR_15));

        ReportLine r = reportLines(MAR_1, MAR_31).get(0);
        assertThat(r.rewardYearStart()).isEqualTo(LocalDate.of(2026, 1, 1));
        assertThat(r.rewardYearEnd()).isEqualTo(LocalDate.of(2026, 12, 31));
    }

    @Test
    void reportLines_dateUsesSettlementDateWhenPresent() {
        rules(rule("Base 1%", RuleStacking.EXCLUSIVE, "1"));
        Transaction t = txn("1000.00", primaryCard, LocalDate.of(2026, 2, 27));
        t.setSettlementDate(LocalDate.of(2026, 3, 2));
        txns(t);

        assertThat(reportLines(MAR_1, MAR_31)).hasSize(1);
        assertThat(reportLines(LocalDate.of(2026, 2, 1), LocalDate.of(2026, 2, 28))).isEmpty();
    }

    // ---------- milestoneStatuses ----------

    @Test
    void milestoneStatuses_returnsWindowStatusesWithProgress() {
        RewardMilestone m = pointsMilestone();
        m.setRewardType(RewardType.CASH);
        m.setPayoutValue(new BigDecimal("250"));
        when(rewardMilestoneRepository.findByAccountIdOrderByCreatedAtAsc(account.getId())).thenReturn(List.of(m));
        txns(txn("600.00", primaryCard, MAR_15));

        List<RewardReportResponse.MilestoneStatus> out = service.milestoneStatuses(account.getId(), MAR_1, MAR_31);

        assertThat(out).hasSize(1);
        RewardReportResponse.MilestoneStatus s = out.get(0);
        assertThat(s.milestoneId()).isEqualTo(m.getId());
        assertThat(s.windowStart()).isEqualTo(MAR_1);
        assertThat(s.windowEnd()).isEqualTo(MAR_31);
        assertThat(s.progress()).isEqualByComparingTo("600");
        assertThat(s.achieved()).isTrue();
        assertThat(s.payoutDate()).isEqualTo(MAR_31);
    }

    @Test
    void milestoneStatuses_oneStatusPerWindowAcrossTheRange() {
        RewardMilestone m = pointsMilestone();
        when(rewardMilestoneRepository.findByAccountIdOrderByCreatedAtAsc(account.getId())).thenReturn(List.of(m));
        txns(txn("600.00", primaryCard, LocalDate.of(2026, 2, 10)));

        List<RewardReportResponse.MilestoneStatus> out =
                service.milestoneStatuses(account.getId(), LocalDate.of(2026, 2, 1), MAR_31);

        assertThat(out).extracting(RewardReportResponse.MilestoneStatus::windowStart)
                .containsExactly(LocalDate.of(2026, 2, 1), MAR_1);
        assertThat(out).extracting(RewardReportResponse.MilestoneStatus::achieved).containsExactly(true, false);
    }

    @Test
    void milestoneStatuses_cardWithNoTransactionsStillReportsZeroProgress() {
        when(rewardMilestoneRepository.findByAccountIdOrderByCreatedAtAsc(account.getId())).thenReturn(List.of(pointsMilestone()));
        txns();

        List<RewardReportResponse.MilestoneStatus> out = service.milestoneStatuses(account.getId(), MAR_1, MAR_31);

        assertThat(out).hasSize(1);
        assertThat(out.get(0).progress()).isEqualByComparingTo("0");
        assertThat(out.get(0).achieved()).isFalse();
    }

    // ---------- capUsage ----------

    private List<CapUsage> capUsage(LocalDate from, LocalDate to) {
        return service.capUsage(account.getId(), from, to);
    }

    @Test
    void capUsage_ruleCap() {
        RewardRule r = cappedRule("Dining 5%", "5", "100", CapWindow.CALENDAR_MONTH, CounterScope.ACCOUNT);
        rules(r);
        txns(txn("1000.00", primaryCard, MAR_15));

        List<CapUsage> out = capUsage(MAR_1, MAR_31);

        assertThat(out).hasSize(1);
        CapUsage u = out.get(0);
        assertThat(u.ruleId()).isEqualTo(r.getId());
        assertThat(u.bucketName()).isNull();
        assertThat(u.ruleName()).isEqualTo("Dining 5%");
        assertThat(u.window()).isEqualTo(CapWindow.CALENDAR_MONTH);
        assertThat(u.windowStart()).isEqualTo(MAR_1);
        assertThat(u.windowEnd()).isEqualTo(MAR_31);
        assertThat(u.cycleFallback()).isFalse();
        assertThat(u.cardholderId()).isNull();
        assertThat(u.cardholderLabel()).isNull();
        assertThat(u.unit()).isEqualTo("RUPEES");
        assertThat(u.cap()).isEqualByComparingTo("100");
        assertThat(u.used()).isEqualByComparingTo("50.00");
    }

    @Test
    void capUsage_pointsRuleReportsPointsUnit() {
        RewardRule r = pointsRule("Pts", RuleStacking.EXCLUSIVE);
        r.setPeriodCap(new BigDecimal("1000"));
        r.setCapWindow(CapWindow.CALENDAR_MONTH);
        rules(r);
        txns(txn("1000.00", primaryCard, MAR_15));

        CapUsage u = capUsage(MAR_1, MAR_31).get(0);
        assertThat(u.unit()).isEqualTo("POINTS");
        assertThat(u.used()).isEqualByComparingTo("20");
    }

    @Test
    void capUsage_sharedBucketDrainedByTwoRulesIsOneEntry() {
        RewardCapBucket bucket = new RewardCapBucket();
        bucket.setId(UUID.randomUUID());
        bucket.setName("Shared 100");
        bucket.setAccount(account);
        bucket.setUser(user);
        bucket.setCap(new BigDecimal("100.00"));
        bucket.setWindowType(CapWindow.CALENDAR_MONTH);
        bucket.setRewardType(RewardType.CASH);
        bucket.setCounterScope(CounterScope.ACCOUNT);
        RewardRule dining = rule("Dining 5%", RuleStacking.EXCLUSIVE, "5");
        dining.setCapBucket(bucket);
        dining.setCategories(new HashSet<>(Set.of(category("Dining"))));
        RewardRule grocery = rule("Grocery 5%", RuleStacking.EXCLUSIVE, "5");
        grocery.setCapBucket(bucket);
        // dining only matches Dining-categorised spend; grocery catches the rest
        grocery.setPriority(50);
        Category dine = dining.getCategories().iterator().next();
        rules(dining, grocery);
        Transaction t1 = txn("1000.00", primaryCard, MAR_15);
        t1.setCategories(Set.of(dine));
        Transaction t2 = txn("1000.00", primaryCard, MAR_15);
        txns(t1, t2);

        List<CapUsage> out = capUsage(MAR_1, MAR_31);

        assertThat(out).hasSize(1);
        CapUsage u = out.get(0);
        assertThat(u.bucketName()).isEqualTo("Shared 100");
        assertThat(u.ruleId()).isNull();
        assertThat(u.cap()).isEqualByComparingTo("100.00");
        assertThat(u.used()).isEqualByComparingTo("100.00");   // 50 from each rule
        assertThat(u.ruleName()).isEqualTo("Dining 5%");        // first rule that owns the bucket
    }

    @Test
    void capUsage_perCardholderCapGivesOneEntryPerCardholderSortedByLabel() {
        rules(cappedRule("Dining 5%", "5", "100", CapWindow.CALENDAR_MONTH, CounterScope.PER_CARDHOLDER));
        txns(txn("1000.00", addonCard, MAR_15), txn("400.00", primaryCard, MAR_15));

        List<CapUsage> out = capUsage(MAR_1, MAR_31);

        assertThat(out).hasSize(2);
        assertThat(out).extracting(CapUsage::cardholderLabel).containsExactly("Primary Holder", "Wife");
        assertThat(out.get(0).cardholderId()).isEqualTo(primary.getId());
        assertThat(out.get(0).used()).isEqualByComparingTo("20.00");
        assertThat(out.get(1).cardholderId()).isEqualTo(addon.getId());
        assertThat(out.get(1).used()).isEqualByComparingTo("50.00");
    }

    @Test
    void capUsage_windowsEntirelyOutsideTheRangeAreDropped() {
        rules(cappedRule("Dining 5%", "5", "100", CapWindow.CALENDAR_MONTH, CounterScope.ACCOUNT));
        txns(txn("1000.00", primaryCard, LocalDate.of(2026, 1, 10)),
                txn("1000.00", primaryCard, MAR_15),
                txn("1000.00", primaryCard, LocalDate.of(2026, 5, 10)));

        List<CapUsage> out = capUsage(MAR_1, MAR_31);

        assertThat(out).extracting(CapUsage::windowStart).containsExactly(MAR_1);
    }

    @Test
    void capUsage_windowPartiallyOverlappingTheRangeIsKept() {
        rules(cappedRule("Dining 5%", "5", "100", CapWindow.CALENDAR_MONTH, CounterScope.ACCOUNT));
        txns(txn("1000.00", primaryCard, MAR_15));

        assertThat(capUsage(LocalDate.of(2026, 3, 20), LocalDate.of(2026, 4, 10))).hasSize(1);
    }

    @Test
    void capUsage_sortedByWindowStartThenNameThenCardholder() {
        RewardRule zeta = cappedRule("Zeta", "5", "100", CapWindow.CALENDAR_MONTH, CounterScope.ACCOUNT);
        RewardRule alpha = cappedRule("Alpha", "5", "100", CapWindow.CALENDAR_MONTH, CounterScope.ACCOUNT);
        // both match everything; use priority + cap fall-through so each drains its own cap
        zeta.setPriority(200);
        alpha.setPriority(100);
        zeta.setPeriodCap(new BigDecimal("50"));   // exhausted by the first Rs 1000 txn each month
        rules(zeta, alpha);
        Transaction feb1 = txn("1000.00", primaryCard, LocalDate.of(2026, 2, 10));
        Transaction feb2 = txn("1000.00", primaryCard, LocalDate.of(2026, 2, 11));
        Transaction mar1 = txn("1000.00", primaryCard, MAR_15);
        Transaction mar2 = txn("1000.00", primaryCard, MAR_15);
        txns(mar1, feb2, mar2, feb1);

        List<CapUsage> out = capUsage(LocalDate.of(2026, 2, 1), MAR_31);

        assertThat(out).extracting(u -> u.windowStart() + ":" + u.ruleName())
                .containsExactly("2026-02-01:Alpha", "2026-02-01:Zeta", "2026-03-01:Alpha", "2026-03-01:Zeta");
    }

    @Test
    void capUsage_sortsPerCardholderEntriesOfTheSameCapByLabel() {
        rules(cappedRule("Dining", "5", "100", CapWindow.CALENDAR_MONTH, CounterScope.PER_CARDHOLDER));
        txns(txn("100.00", addonCard, MAR_15), txn("100.00", primaryCard, MAR_15));
        assertThat(capUsage(MAR_1, MAR_31)).extracting(CapUsage::cardholderLabel).containsExactly("Primary Holder", "Wife");
    }

    @Test
    void capUsage_statementCycleWithoutStatementFlagsFallback() {
        rules(cappedRule("Dining 5%", "5", "100", CapWindow.STATEMENT_CYCLE, CounterScope.ACCOUNT));
        txns(txn("1000.00", primaryCard, MAR_15));

        CapUsage u = capUsage(MAR_1, MAR_31).get(0);
        assertThat(u.window()).isEqualTo(CapWindow.STATEMENT_CYCLE);
        assertThat(u.cycleFallback()).isTrue();
        assertThat(u.windowStart()).isEqualTo(MAR_1);
    }

    @Test
    void capUsage_statementCycleWithStatementUsesItsPeriod() {
        Statement s = new Statement();
        s.setPeriodStart(LocalDate.of(2026, 2, 16));
        s.setPeriodEnd(MAR_15);
        when(statementRepository.findByAccountIdOrderByPeriodEndDescNullsLast(account.getId())).thenReturn(List.of(s));
        rules(cappedRule("Dining 5%", "5", "100", CapWindow.STATEMENT_CYCLE, CounterScope.ACCOUNT));
        txns(txn("1000.00", primaryCard, MAR_15));

        CapUsage u = capUsage(MAR_1, MAR_31).get(0);
        assertThat(u.cycleFallback()).isFalse();
        assertThat(u.windowStart()).isEqualTo(LocalDate.of(2026, 2, 16));
        assertThat(u.windowEnd()).isEqualTo(MAR_15);
    }

    @Test
    void capUsage_capWithNoSpendHasNoEntryAndUncappedRulesNeverAppear() {
        rules(cappedRule("Dining 5%", "5", "100", CapWindow.CALENDAR_MONTH, CounterScope.ACCOUNT));
        txns();
        assertThat(capUsage(MAR_1, MAR_31)).isEmpty();

        rules(rule("Uncapped", RuleStacking.EXCLUSIVE, "1"));
        txns(txn("1000.00", primaryCard, MAR_15));
        assertThat(capUsage(MAR_1, MAR_31)).isEmpty();
    }
}
