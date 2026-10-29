package com.financeos.domain.inbox.collect;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.financeos.api.inbox.dto.InboxItemResponse;
import com.financeos.domain.account.Account;
import com.financeos.domain.account.AccountRepository;
import com.financeos.domain.account.AccountType;
import com.financeos.domain.reward.RewardCapBucket;
import com.financeos.domain.reward.RewardCapBucketRepository;
import com.financeos.domain.reward.RewardMilestone;
import com.financeos.domain.reward.RewardMilestoneRepository;
import com.financeos.domain.reward.RewardRule;
import com.financeos.domain.reward.RewardRuleRepository;
import com.financeos.domain.statement.Statement;
import com.financeos.domain.statement.StatementRepository;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * The reward and statement-expected collectors read every card with IN queries; with several cards the rows
 * must equal what running the collector one card at a time (the old per-card reads) produces.
 */
class BatchedCardCollectorsTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 10, 20);
    private final UUID userId = UUID.randomUUID();

    private Account card(String name, LocalDate closedOn) {
        Account a = new Account();
        a.setId(UUID.randomUUID());
        a.setName(name);
        a.setType(AccountType.credit_card);
        a.setClosedOn(closedOn);
        return a;
    }

    private AccountRepository accounts(Account... cards) {
        AccountRepository repo = mock(AccountRepository.class);
        when(repo.findByUserIdAndType(userId, AccountType.credit_card)).thenReturn(List.of(cards));
        return repo;
    }

    // ---------------------------------------------------------------- rewards

    private final List<RewardMilestone> milestones = new ArrayList<>();
    private final List<RewardCapBucket> buckets = new ArrayList<>();
    private final List<RewardRule> rules = new ArrayList<>();

    private RewardInboxCollector rewards(AccountRepository accounts, RewardMilestoneRepository mr, RewardCapBucketRepository br, RewardRuleRepository rr) {
        when(mr.findByAccountIdInOrderByCreatedAtAsc(any())).thenAnswer(inv -> forAccounts(milestones, inv.getArgument(0), m -> m.getAccount().getId()));
        when(br.findByAccountIdInOrderByCreatedAtAsc(any())).thenAnswer(inv -> forAccounts(buckets, inv.getArgument(0), b -> b.getAccount().getId()));
        when(rr.findByAccountIdInOrderByPriorityDesc(any())).thenAnswer(inv -> forAccounts(rules, inv.getArgument(0), r -> r.getAccount().getId()));
        return new RewardInboxCollector(accounts, mr, br, rr);
    }

    private static <T> List<T> forAccounts(List<T> all, Collection<UUID> ids, java.util.function.Function<T, UUID> accountOf) {
        return all.stream().filter(x -> ids.contains(accountOf.apply(x))).toList();
    }

    @Test
    void rewardRowsForSeveralCardsEqualTheOneCardAtATimeRowsAndCostThreeQueries() {
        Account a = card("Alpha", null);
        Account b = card("Beta", null);
        Account closed = card("Old", LocalDate.of(2026, 1, 1));
        for (Account c : List.of(a, b, closed)) {
            RewardMilestone m = new RewardMilestone();
            m.setId(UUID.randomUUID());
            m.setAccount(c);
            m.setName("Bonus " + c.getName());
            m.setNotifiedKind("ACHIEVED");
            m.setNotifiedWindowStart(LocalDate.of(2026, 10, 1));
            m.setNotifiedOn(TODAY.minusDays(1));
            milestones.add(m);
            RewardCapBucket k = new RewardCapBucket();
            k.setId(UUID.randomUUID());
            k.setAccount(c);
            k.setName("Bucket " + c.getName());
            k.setCapNotifiedWindowStart(LocalDate.of(2026, 10, 1));
            k.setCapNotifiedOn(TODAY.minusDays(3));
            buckets.add(k);
            for (int i = 0; i < 2; i++) {
                RewardRule r = new RewardRule();
                r.setId(UUID.randomUUID());
                r.setAccount(c);
                r.setName("Rule " + i + " " + c.getName());
                r.setCapNotifiedWindowStart(LocalDate.of(2026, 10, 1));
                r.setCapNotifiedOn(TODAY.minusDays(i));
                rules.add(r);
            }
        }
        RewardMilestoneRepository mr = mock(RewardMilestoneRepository.class);
        RewardCapBucketRepository br = mock(RewardCapBucketRepository.class);
        RewardRuleRepository rr = mock(RewardRuleRepository.class);

        List<InboxItemResponse> batched = rewards(accounts(a, b, closed), mr, br, rr).collect(userId, TODAY);

        List<InboxItemResponse> oneByOne = new ArrayList<>();
        for (Account c : List.of(a, b, closed)) {
            oneByOne.addAll(rewards(accounts(c), mock(RewardMilestoneRepository.class), mock(RewardCapBucketRepository.class),
                    mock(RewardRuleRepository.class)).collect(userId, TODAY));
        }
        assertEquals(oneByOne, batched);
        assertEquals(8, batched.size()); // (1 milestone + 1 bucket + 2 rules) x 2 open cards; the closed card adds none
        verify(mr, times(1)).findByAccountIdInOrderByCreatedAtAsc(any());
        verify(br, times(1)).findByAccountIdInOrderByCreatedAtAsc(any());
        verify(rr, times(1)).findByAccountIdInOrderByPriorityDesc(any());
    }

    // ---------------------------------------------------------------- statements expected

    private Statement statement(Account c, LocalDate end) {
        Statement s = new Statement();
        s.setId(UUID.randomUUID());
        s.setAccount(c);
        s.setPeriodEnd(end);
        s.setPeriodStart(end.minusMonths(1).plusDays(1));
        s.setStatementType("credit_card");
        return s;
    }

    private StatementExpectedInboxCollector expected(AccountRepository accounts, StatementRepository sr, List<Statement> all) {
        when(sr.findQualifyingCreditCardStatementsIn(any())).thenAnswer(inv -> forAccounts(all, inv.getArgument(0), s -> s.getAccount().getId()));
        return new StatementExpectedInboxCollector(accounts, sr);
    }

    @Test
    void missingStatementRowsForSeveralCardsEqualTheOneCardAtATimeRowsAndCostOneQuery() {
        Account lapsed = card("Lapsed", null);
        Account current = card("Current", null);
        Account never = card("Never", null);
        Account closed = card("Closed", LocalDate.of(2026, 1, 1));
        List<Statement> all = new ArrayList<>();
        for (int i = 2; i >= 0; i--) {
            all.add(statement(lapsed, LocalDate.of(2026, 7, 15).minusMonths(i)));
            all.add(statement(current, LocalDate.of(2026, 9, 15).minusMonths(i)));
            all.add(statement(closed, LocalDate.of(2026, 7, 15).minusMonths(i)));
        }
        StatementRepository sr = mock(StatementRepository.class);

        List<InboxItemResponse> batched = expected(accounts(lapsed, current, never, closed), sr, all).collect(userId, TODAY);

        List<InboxItemResponse> oneByOne = new ArrayList<>();
        for (Account c : List.of(lapsed, current, never, closed)) {
            oneByOne.addAll(expected(accounts(c), mock(StatementRepository.class), all).collect(userId, TODAY));
        }
        assertEquals(oneByOne, batched);
        assertFalse(batched.isEmpty());
        verify(sr, times(1)).findQualifyingCreditCardStatementsIn(any());
    }

    // ---------------------------------------------------------------- shared per build

    @Test
    void bothCollectorsShareOneCreditCardLoadWithinABuild() {
        Account a = card("Alpha", null);
        AccountRepository accounts = accounts(a);
        RewardInboxCollector r = rewards(accounts, mock(RewardMilestoneRepository.class), mock(RewardCapBucketRepository.class), mock(RewardRuleRepository.class));
        StatementExpectedInboxCollector s = expected(accounts, mock(StatementRepository.class), List.of());
        InboxContext context = new InboxContext();

        r.collect(userId, TODAY, context);
        s.collect(userId, TODAY, context);

        verify(accounts, times(1)).findByUserIdAndType(userId, AccountType.credit_card);
    }
}
