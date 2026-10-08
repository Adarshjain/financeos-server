package com.financeos.domain.inbox.collect;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.financeos.api.inbox.dto.InboxActionResponse;
import com.financeos.api.inbox.dto.InboxItemResponse;
import com.financeos.api.inbox.dto.InboxRefsResponse;
import com.financeos.domain.account.Account;
import com.financeos.domain.account.AccountRepository;
import com.financeos.domain.account.AccountType;
import com.financeos.domain.account.card.Card;
import com.financeos.domain.account.card.Cardholder;
import com.financeos.domain.account.card.CardholderRole;
import com.financeos.domain.reward.RewardCapBucket;
import com.financeos.domain.reward.RewardCapBucketRepository;
import com.financeos.domain.reward.RewardMilestone;
import com.financeos.domain.reward.RewardMilestoneRepository;
import com.financeos.domain.reward.RewardRule;
import com.financeos.domain.reward.RewardRuleRepository;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class RewardInboxCollectorTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 10, 20);
    private static final LocalDate WINDOW_START = LocalDate.of(2026, 10, 1);

    private final AccountRepository accountRepository = mock(AccountRepository.class);
    private final RewardMilestoneRepository milestoneRepository = mock(RewardMilestoneRepository.class);
    private final RewardCapBucketRepository bucketRepository = mock(RewardCapBucketRepository.class);
    private final RewardRuleRepository ruleRepository = mock(RewardRuleRepository.class);
    private final RewardInboxCollector collector =
            new RewardInboxCollector(accountRepository, milestoneRepository, bucketRepository, ruleRepository);
    private final UUID userId = UUID.randomUUID();
    private final List<RewardMilestone> milestones = new ArrayList<>();
    private final List<RewardCapBucket> buckets = new ArrayList<>();
    private final List<RewardRule> rules = new ArrayList<>();
    private Account card;

    @BeforeEach
    void setUp() {
        card = new Account();
        card.setId(UUID.randomUUID());
        card.setName("Infinia");
        card.setType(AccountType.credit_card);
        Cardholder holder = new Cardholder();
        holder.setId(UUID.randomUUID());
        holder.setAccount(card);
        holder.setRole(CardholderRole.PRIMARY);
        Card plastic = new Card();
        plastic.setId(UUID.randomUUID());
        plastic.setAccount(card);
        plastic.setCardholder(holder);
        plastic.setLast4("1234");
        holder.getCards().add(plastic);
        card.getCardholders().add(holder);
        when(accountRepository.findByUserIdAndType(userId, AccountType.credit_card)).thenReturn(List.of(card));
        when(milestoneRepository.findByAccountIdOrderByCreatedAtAsc(card.getId())).thenReturn(milestones);
        when(bucketRepository.findByAccountIdOrderByCreatedAtAsc(card.getId())).thenReturn(buckets);
        when(ruleRepository.findByAccountIdOrderByPriorityDesc(card.getId())).thenReturn(rules);
    }

    private RewardMilestone milestone(String kind, LocalDate windowStart, LocalDate notifiedOn) {
        RewardMilestone m = new RewardMilestone();
        m.setId(UUID.randomUUID());
        m.setName("Q4 spend bonus");
        m.setNotifiedKind(kind);
        m.setNotifiedWindowStart(windowStart);
        m.setNotifiedOn(notifiedOn);
        milestones.add(m);
        return m;
    }

    private RewardCapBucket bucket(LocalDate windowStart, LocalDate notifiedOn) {
        RewardCapBucket b = new RewardCapBucket();
        b.setId(UUID.randomUUID());
        b.setName("Shared monthly");
        b.setCapNotifiedWindowStart(windowStart);
        b.setCapNotifiedOn(notifiedOn);
        buckets.add(b);
        return b;
    }

    private RewardRule rule(LocalDate windowStart, LocalDate notifiedOn) {
        RewardRule r = new RewardRule();
        r.setId(UUID.randomUUID());
        r.setName("Dining 5%");
        r.setCapNotifiedWindowStart(windowStart);
        r.setCapNotifiedOn(notifiedOn);
        rules.add(r);
        return r;
    }

    private List<String> keys() {
        return collector.collect(userId, TODAY).stream().map(InboxItemResponse::key).toList();
    }

    // ---------------------------------------------------------------- milestones

    @Test
    void anAchievedMilestoneIsAnInfoRowKeyedByItsWindow() {
        RewardMilestone m = milestone("ACHIEVED", WINDOW_START, TODAY.minusDays(2));

        List<InboxItemResponse> rows = collector.collect(userId, TODAY);

        assertEquals(1, rows.size());
        InboxItemResponse row = rows.get(0);
        String href = "/rewards?account=" + card.getId();
        assertEquals("reward-milestone:" + m.getId() + ":2026-10-01", row.key());
        assertEquals("reward_milestone", row.kind());
        assertEquals(InboxItemResponse.ROW_ITEM, row.rowType());
        assertEquals("info", row.severity());
        assertEquals("info", row.section());
        assertEquals("Q4 spend bonus unlocked", row.title());
        assertEquals("Infinia ••1234 · window from 1 Oct", row.subtitle());
        assertEquals(href, row.href());
        assertEquals(null, row.amount());
        assertEquals(WINDOW_START, row.date());
        assertEquals(List.of(InboxActionResponse.navigate("open", "Open", href), InboxRows.dismiss()), row.actions());
        assertEquals(InboxRefsResponse.ofAccount(card.getId()), row.refs());
    }

    @Test
    void closingNudgesAndUnannouncedMilestonesAreNotInboxNews() {
        milestone("CLOSING", WINDOW_START, TODAY);
        milestone(null, null, null);
        assertEquals(List.of(), keys());
    }

    @Test
    void recencyFollowsTheDateTheMarkerWasRecordedNotTheWindowStart() {
        // A quarterly window that opened in July but was only achieved last week is still news.
        RewardMilestone lateInLongWindow = milestone("ACHIEVED", LocalDate.of(2026, 7, 1), TODAY.minusDays(5));
        RewardMilestone onTheEdge = milestone("ACHIEVED", LocalDate.of(2026, 9, 1), TODAY.minusDays(30));
        milestone("ACHIEVED", LocalDate.of(2026, 9, 1), TODAY.minusDays(31));

        assertEquals(List.of("reward-milestone:" + lateInLongWindow.getId() + ":2026-07-01",
                "reward-milestone:" + onTheEdge.getId() + ":2026-09-01"), keys());
    }

    @Test
    void markersRecordedBeforeNotifiedOnExistedFallBackToTheWindowStart() {
        RewardMilestone recentWindow = milestone("ACHIEVED", TODAY.minusDays(30), null);
        milestone("ACHIEVED", TODAY.minusDays(31), null);

        assertEquals(List.of("reward-milestone:" + recentWindow.getId() + ":" + TODAY.minusDays(30)), keys());
    }

    @Test
    void aMarkerWithoutAWindowStartIsNeverRecent() {
        milestone("ACHIEVED", null, TODAY);
        bucket(null, TODAY);
        rule(null, TODAY);
        assertEquals(List.of(), keys());
    }

    // ---------------------------------------------------------------- caps

    @Test
    void exhaustedBucketAndRuleCapsAreInfoRowsKeyedByOwnerAndWindow() {
        RewardCapBucket b = bucket(WINDOW_START, TODAY.minusDays(1));
        RewardRule r = rule(WINDOW_START, TODAY);

        List<InboxItemResponse> rows = collector.collect(userId, TODAY);

        assertEquals(2, rows.size());
        InboxItemResponse bucketRow = rows.get(0);
        assertEquals("reward-cap:" + b.getId() + ":2026-10-01", bucketRow.key());
        assertEquals("reward_cap", bucketRow.kind());
        assertEquals("info", bucketRow.severity());
        assertEquals("info", bucketRow.section());
        assertEquals("Infinia ••1234: Shared monthly cap reached", bucketRow.title());
        assertEquals("More spend here earns nothing for the rest of the window · from 1 Oct", bucketRow.subtitle());
        assertEquals("/rewards?account=" + card.getId(), bucketRow.href());
        assertEquals(WINDOW_START, bucketRow.date());
        assertEquals(List.of("open", "dismiss"), bucketRow.actions().stream().map(InboxActionResponse::type).toList());
        assertEquals(InboxRefsResponse.ofAccount(card.getId()), bucketRow.refs());

        InboxItemResponse ruleRow = rows.get(1);
        assertEquals("reward-cap:" + r.getId() + ":2026-10-01", ruleRow.key());
        assertEquals("Infinia ••1234: Dining 5% cap reached", ruleRow.title());
    }

    @Test
    void capRecencyUsesTheRecordedDateWithTheWindowStartAsFallback() {
        RewardCapBucket recentBucket = bucket(LocalDate.of(2026, 7, 1), TODAY.minusDays(30));
        bucket(WINDOW_START, TODAY.minusDays(31));
        RewardRule legacyRule = rule(WINDOW_START, null);
        rule(LocalDate.of(2026, 9, 1), null);

        assertEquals(List.of("reward-cap:" + recentBucket.getId() + ":2026-07-01", "reward-cap:" + legacyRule.getId() + ":2026-10-01"), keys());
    }

    // ---------------------------------------------------------------- cards

    @Test
    void closedCardsAreSkippedWithoutReadingTheirMarkers() {
        card.setClosedOn(TODAY);
        milestone("ACHIEVED", WINDOW_START, TODAY);

        assertEquals(List.of(), keys());
        verify(milestoneRepository, never()).findByAccountIdOrderByCreatedAtAsc(any());
        verify(bucketRepository, never()).findByAccountIdOrderByCreatedAtAsc(any());
        verify(ruleRepository, never()).findByAccountIdOrderByPriorityDesc(any());
    }

    @Test
    void aCardWithoutAnOpenPlasticIsLabelledByNameOnly() {
        card.getCardholders().clear();
        bucket(WINDOW_START, TODAY);

        assertEquals("Infinia: Shared monthly cap reached", collector.collect(userId, TODAY).get(0).title());
    }
}
