package com.financeos.domain.notification.reward;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.financeos.api.reward.dto.RewardReportResponse;
import com.financeos.core.time.AppTime;
import com.financeos.domain.account.Account;
import com.financeos.domain.account.AccountRepository;
import com.financeos.domain.account.AccountType;
import com.financeos.domain.notification.NotificationKind;
import com.financeos.domain.notification.NotificationOutcome;
import com.financeos.domain.notification.NotificationPrefs;
import com.financeos.domain.notification.NotificationPrefsLoader;
import com.financeos.domain.notification.NotificationSettingsCodec;
import com.financeos.domain.notification.NotificationSettingsService;
import com.financeos.domain.notification.UserNotificationSettings;
import com.financeos.domain.notification.push.PushMessage;
import com.financeos.domain.reward.CapWindow;
import com.financeos.domain.reward.MilestoneBasis;
import com.financeos.domain.reward.MilestonePayoutType;
import com.financeos.domain.reward.MilestoneWindow;
import com.financeos.domain.reward.RewardCalculationService;
import com.financeos.domain.reward.RewardCapBucket;
import com.financeos.domain.reward.RewardCapBucketRepository;
import com.financeos.domain.reward.RewardMilestone;
import com.financeos.domain.reward.RewardMilestoneRepository;
import com.financeos.domain.reward.RewardRule;
import com.financeos.domain.reward.RewardRuleRepository;
import com.financeos.domain.reward.RewardType;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class RewardAlertNotificationServiceTest {

    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");
    private static final LocalDate TODAY = LocalDate.of(2026, 10, 26);
    private static final LocalDate WINDOW_START = LocalDate.of(2026, 10, 1);
    private static final LocalDate WINDOW_END = LocalDate.of(2026, 10, 31);

    private final AccountRepository accountRepository = mock(AccountRepository.class);
    private final RewardRuleRepository ruleRepository = mock(RewardRuleRepository.class);
    private final RewardMilestoneRepository milestoneRepository = mock(RewardMilestoneRepository.class);
    private final RewardCapBucketRepository bucketRepository = mock(RewardCapBucketRepository.class);
    private final RewardCalculationService calculationService = mock(RewardCalculationService.class);
    private final NotificationPrefsLoader prefsLoader = mock(NotificationPrefsLoader.class);
    private final NotificationSettingsService settingsService = mock(NotificationSettingsService.class);
    private final RewardAlertNotificationService service = new RewardAlertNotificationService(accountRepository, ruleRepository,
            milestoneRepository, bucketRepository, calculationService, prefsLoader, settingsService);

    private final UUID userId = UUID.randomUUID();
    private final UserNotificationSettings settings = new UserNotificationSettings(userId);
    private final Map<NotificationKind, Boolean> kinds = new EnumMap<>(NotificationSettingsCodec.parseKinds(null));
    private Account card;
    private RewardMilestone milestone;
    private RewardRule rule;
    private RewardCapBucket bucket;

    @BeforeEach
    void setUp() {
        clockAt(10);
        card = new Account();
        card.setId(UUID.randomUUID());
        card.setName("Infinia");
        card.setType(AccountType.credit_card);
        milestone = new RewardMilestone();
        milestone.setId(UUID.randomUUID());
        milestone.setName("Q4 spend bonus");
        rule = new RewardRule();
        rule.setId(UUID.randomUUID());
        rule.setName("Dining 5%");
        bucket = new RewardCapBucket();
        bucket.setId(UUID.randomUUID());
        bucket.setName("Shared monthly cap");
        when(accountRepository.findByUserIdAndType(userId, AccountType.credit_card)).thenReturn(List.of(card));
        when(ruleRepository.findByAccountIdOrderByPriorityDesc(card.getId())).thenReturn(List.of(rule));
        when(milestoneRepository.findByAccountIdOrderByCreatedAtAsc(card.getId())).thenReturn(List.of(milestone));
        when(bucketRepository.findByAccountIdOrderByCreatedAtAsc(card.getId())).thenReturn(List.of(bucket));
        when(settingsService.deliver(any(), any())).thenReturn(1);
        prefs(true);
        snapshot(List.of(), List.of());
    }

    @AfterEach
    void tearDown() {
        AppTime.reset();
    }

    private void clockAt(int hour) {
        AppTime.useClock(Clock.fixed(TODAY.atTime(hour, 5).atZone(IST).toInstant(), IST));
    }

    private void prefs(boolean canSend) {
        when(prefsLoader.load(userId)).thenReturn(new NotificationPrefs(settings, canSend, 9, List.of(7, 3, 1, 0), kinds));
    }

    private void snapshot(List<RewardReportResponse.MilestoneStatus> milestones, List<RewardCalculationService.CapUsage> caps) {
        when(calculationService.alertSnapshot(card.getId(), TODAY)).thenReturn(new RewardCalculationService.AlertSnapshot(milestones, caps));
    }

    private RewardReportResponse.MilestoneStatus status(String progress, boolean achieved, LocalDate windowEnd) {
        return new RewardReportResponse.MilestoneStatus(milestone.getId(), milestone.getName(), MilestoneWindow.CALENDAR_MONTH,
                WINDOW_START, windowEnd, MilestoneBasis.SPEND, new BigDecimal("100000"), null, new BigDecimal(progress), achieved,
                MilestonePayoutType.CASH_VALUE, RewardType.CASH, new BigDecimal("5000"), achieved ? windowEnd : null, achieved);
    }

    private RewardCalculationService.CapUsage cap(UUID ruleId, UUID bucketId, String bucketName, String used) {
        return new RewardCalculationService.CapUsage(ruleId, bucketId, bucketName, "Dining 5%", CapWindow.CALENDAR_MONTH,
                WINDOW_START, WINDOW_END, false, null, null, "RUPEES", new BigDecimal("1000"), new BigDecimal(used));
    }

    private List<PushMessage> delivered(int n) {
        ArgumentCaptor<PushMessage> message = ArgumentCaptor.forClass(PushMessage.class);
        verify(settingsService, times(n)).deliver(eq(settings), message.capture());
        return message.getAllValues();
    }

    // ---------------------------------------------------------------- pure decisions

    @Test
    void milestoneKindDecisions() {
        assertEquals("ACHIEVED", RewardAlertNotificationService.milestoneKind(status("120000", true, WINDOW_END), TODAY, null, null));
        assertNull(RewardAlertNotificationService.milestoneKind(status("120000", true, WINDOW_END), TODAY, WINDOW_START, "ACHIEVED"));
        assertEquals("ACHIEVED", RewardAlertNotificationService.milestoneKind(status("120000", true, WINDOW_END), TODAY, WINDOW_START, "CLOSING"),
                "closing was announced, achieving is still news");
        assertEquals("CLOSING", RewardAlertNotificationService.milestoneKind(status("60000", false, WINDOW_END), TODAY, null, null));
        assertEquals("CLOSING", RewardAlertNotificationService.milestoneKind(status("50000", false, WINDOW_END), TODAY, null, null), "half counts");
        assertNull(RewardAlertNotificationService.milestoneKind(status("49999", false, WINDOW_END), TODAY, null, null), "under half: too far");
        assertNull(RewardAlertNotificationService.milestoneKind(status("60000", false, LocalDate.of(2026, 11, 3)), TODAY, null, null), "8 days left");
        assertEquals("CLOSING", RewardAlertNotificationService.milestoneKind(status("60000", false, LocalDate.of(2026, 11, 2)), TODAY, null, null), "7 days left");
        assertNull(RewardAlertNotificationService.milestoneKind(status("60000", false, TODAY.minusDays(1)), TODAY, null, null), "window already over");
        assertNull(RewardAlertNotificationService.milestoneKind(status("60000", false, WINDOW_END), TODAY, WINDOW_START, "CLOSING"), "once per window");
        assertEquals("CLOSING", RewardAlertNotificationService.milestoneKind(status("60000", false, WINDOW_END), TODAY, WINDOW_START.minusMonths(1), "CLOSING"),
                "a new window starts fresh");
    }

    @Test
    void capExhaustedNowDecisions() {
        assertTrue(RewardAlertNotificationService.capExhaustedNow(cap(rule.getId(), null, null, "1000"), TODAY));
        assertTrue(RewardAlertNotificationService.capExhaustedNow(cap(rule.getId(), null, null, "1500"), TODAY));
        assertFalse(RewardAlertNotificationService.capExhaustedNow(cap(rule.getId(), null, null, "999.99"), TODAY));
        assertFalse(RewardAlertNotificationService.capExhaustedNow(cap(rule.getId(), null, null, "1000"), LocalDate.of(2026, 11, 1)), "a past window");
    }

    // ---------------------------------------------------------------- evaluate

    @Test
    void waitsForTheSendHourAndRunsOncePerCardPerDay() {
        clockAt(8);
        assertEquals(NotificationOutcome.NONE, service.evaluate(userId));
        verify(calculationService, never()).alertSnapshot(any(), any());

        clockAt(10);
        card.setRewardAlertsCheckedOn(TODAY);
        assertEquals(NotificationOutcome.NONE, service.evaluate(userId));
        verify(calculationService, never()).alertSnapshot(any(), any());
    }

    @Test
    void cardsWithoutRewardsSetupAndClosedCardsAreSkipped() {
        when(ruleRepository.findByAccountIdOrderByPriorityDesc(card.getId())).thenReturn(List.of());
        when(milestoneRepository.findByAccountIdOrderByCreatedAtAsc(card.getId())).thenReturn(List.of());
        assertEquals(NotificationOutcome.NONE, service.evaluate(userId));
        assertNull(card.getRewardAlertsCheckedOn());

        when(ruleRepository.findByAccountIdOrderByPriorityDesc(card.getId())).thenReturn(List.of(rule));
        card.setClosedOn(TODAY.minusDays(1));
        assertEquals(NotificationOutcome.NONE, service.evaluate(userId));
        verify(calculationService, never()).alertSnapshot(any(), any());
    }

    @Test
    void closingMilestoneIsAnnouncedWithTheGapAndMarked() {
        snapshot(List.of(status("82000", false, WINDOW_END)), List.of());

        assertEquals(new NotificationOutcome(1, 1, 1), service.evaluate(userId));

        PushMessage message = delivered(1).get(0);
        assertEquals("Q4 spend bonus: ₹18,000 more to go", message.title());
        assertEquals("Spend on Infinia by 31 Oct to unlock ₹5,000.", message.body());
        assertEquals("/rewards?account=" + card.getId(), message.url());
        assertEquals("milestone-" + milestone.getId(), message.tag());
        assertEquals(WINDOW_START, milestone.getNotifiedWindowStart());
        assertEquals("CLOSING", milestone.getNotifiedKind());
        assertEquals(TODAY, card.getRewardAlertsCheckedOn());
        verify(milestoneRepository).save(milestone);
        verify(accountRepository).save(card);
    }

    @Test
    void achievedMilestoneIsAnnouncedAfterAClosingNudge() {
        milestone.setNotifiedWindowStart(WINDOW_START);
        milestone.setNotifiedKind("CLOSING");
        snapshot(List.of(status("101000", true, WINDOW_END)), List.of());

        assertEquals(new NotificationOutcome(1, 1, 1), service.evaluate(userId));
        PushMessage message = delivered(1).get(0);
        assertEquals("Q4 spend bonus unlocked", message.title());
        assertEquals("Infinia crossed ₹1,00,000 this window. ₹5,000 credited around 31 Oct.", message.body());
        assertEquals("ACHIEVED", milestone.getNotifiedKind());
    }

    @Test
    void exhaustedRuleCapAndBucketCapAreEachAnnouncedOncePerWindow() {
        snapshot(List.of(), List.of(cap(rule.getId(), null, null, "1000"), cap(null, bucket.getId(), "Shared monthly cap", "1200")));

        assertEquals(new NotificationOutcome(1, 2, 2), service.evaluate(userId));
        List<PushMessage> messages = delivered(2);
        assertEquals("Infinia: Dining 5% cap reached", messages.get(0).title());
        assertEquals("₹1,000 of ₹1,000 used this month. More spend here earns nothing until 1 Nov · try another card.", messages.get(0).body());
        assertEquals("cap-" + rule.getId(), messages.get(0).tag());
        assertEquals("Infinia: Shared monthly cap cap reached", messages.get(1).title());
        assertEquals("cap-" + bucket.getId(), messages.get(1).tag());
        assertEquals(WINDOW_START, rule.getCapNotifiedWindowStart());
        assertEquals(WINDOW_START, bucket.getCapNotifiedWindowStart());

        // Next day, same window: nothing new.
        card.setRewardAlertsCheckedOn(null);
        assertEquals(new NotificationOutcome(1, 0, 0), service.evaluate(userId));
    }

    @Test
    void capsNotYetExhaustedAreIgnored() {
        snapshot(List.of(), List.of(cap(rule.getId(), null, null, "400")));
        assertEquals(new NotificationOutcome(1, 0, 0), service.evaluate(userId));
        assertNull(rule.getCapNotifiedWindowStart());
        assertEquals(TODAY, card.getRewardAlertsCheckedOn(), "the daily pass still counts as done");
    }

    @Test
    void kindSwitchesAreHonouredPerKindAndMarkersStillAdvance() {
        kinds.put(NotificationKind.REWARD_MILESTONE, false);
        prefs(true);
        snapshot(List.of(status("90000", false, WINDOW_END)), List.of(cap(rule.getId(), null, null, "1000")));

        assertEquals(new NotificationOutcome(1, 2, 1), service.evaluate(userId));
        assertEquals("CLOSING", milestone.getNotifiedKind());
        assertEquals("Infinia: Dining 5% cap reached", delivered(1).get(0).title());
    }

    @Test
    void pointsMilestonesAndTransactionCountMilestonesReadNaturally() {
        RewardReportResponse.MilestoneStatus points = new RewardReportResponse.MilestoneStatus(milestone.getId(), "Welcome bonus",
                MilestoneWindow.ONE_TIME, WINDOW_START, WINDOW_END, MilestoneBasis.TXN_COUNT, new BigDecimal("5"), null,
                new BigDecimal("3"), false, MilestonePayoutType.CASH_VALUE, RewardType.POINTS, new BigDecimal("10000"), null, false);
        PushMessage closing = RewardMessages.milestoneClosing(card, points);
        assertEquals("Welcome bonus: 2 more transactions to go", closing.title());
        assertEquals("Transact on Infinia by 31 Oct to unlock 10000 pts.", closing.body());

        RewardReportResponse.MilestoneStatus tracker = new RewardReportResponse.MilestoneStatus(milestone.getId(), "Fee waiver",
                MilestoneWindow.ANNIVERSARY_YEAR, WINDOW_START, WINDOW_END, MilestoneBasis.SPEND, new BigDecimal("300000"), null,
                new BigDecimal("300000"), true, MilestonePayoutType.INFO_TRACKER, RewardType.CASH, null, WINDOW_END, true);
        assertEquals("Infinia crossed ₹3,00,000 this window. Milestone reached.", RewardMessages.milestoneAchieved(card, tracker).body());
    }

    @Test
    void capWindowLabelsAndPointUnits() {
        RewardCalculationService.CapUsage points = new RewardCalculationService.CapUsage(rule.getId(), null, null, "Fuel", CapWindow.STATEMENT_CYCLE,
                WINDOW_START, WINDOW_END, true, null, null, "POINTS", new BigDecimal("500"), new BigDecimal("500"));
        assertEquals("500 pts of 500 pts used this statement cycle. More spend here earns nothing until 1 Nov · try another card.",
                RewardMessages.capReached(card, points).body());
        assertEquals("this card year", RewardMessages.windowLabel(CapWindow.ANNIVERSARY_YEAR));
        assertEquals("today", RewardMessages.windowLabel(CapWindow.DAY));
        assertEquals("this quarter", RewardMessages.windowLabel(CapWindow.QUARTER));
        assertEquals("this year", RewardMessages.windowLabel(CapWindow.CALENDAR_YEAR));
        assertEquals("this period", RewardMessages.windowLabel(null));
    }

    // ---------------------------------------------------------------- notified-on markers (inbox recency)

    @Test
    void recordingAMilestoneMarkerAlsoRecordsTodayAsItsNotifiedOnDate() {
        snapshot(List.of(status("82000", false, WINDOW_END)), List.of());
        service.evaluate(userId);
        assertEquals("CLOSING", milestone.getNotifiedKind());
        assertEquals(TODAY, milestone.getNotifiedOn());

        // Achieved later in the same window: the date moves with the marker.
        milestone.setNotifiedOn(TODAY.minusDays(5));
        card.setRewardAlertsCheckedOn(null);
        snapshot(List.of(status("101000", true, WINDOW_END)), List.of());
        service.evaluate(userId);
        assertEquals("ACHIEVED", milestone.getNotifiedKind());
        assertEquals(TODAY, milestone.getNotifiedOn());
    }

    @Test
    void aMilestoneWithNothingNewKeepsItsNotifiedOnDate() {
        LocalDate earlier = TODAY.minusDays(4);
        milestone.setNotifiedWindowStart(WINDOW_START);
        milestone.setNotifiedKind("ACHIEVED");
        milestone.setNotifiedOn(earlier);
        snapshot(List.of(status("120000", true, WINDOW_END)), List.of());

        service.evaluate(userId);

        assertEquals(earlier, milestone.getNotifiedOn(), "already announced for this window");
        verify(milestoneRepository, never()).save(any());
    }

    @Test
    void recordingACapMarkerAlsoRecordsTodayOnTheRuleOrBucket() {
        snapshot(List.of(), List.of(cap(rule.getId(), null, null, "1000"), cap(null, bucket.getId(), "Shared monthly cap", "1200")));

        service.evaluate(userId);

        assertEquals(TODAY, rule.getCapNotifiedOn());
        assertEquals(TODAY, bucket.getCapNotifiedOn());
    }

    @Test
    void capsNotRecordedKeepTheirNotifiedOnDate() {
        LocalDate earlier = TODAY.minusDays(3);
        bucket.setCapNotifiedWindowStart(WINDOW_START);
        bucket.setCapNotifiedOn(earlier);
        snapshot(List.of(), List.of(cap(rule.getId(), null, null, "400"), cap(null, bucket.getId(), "Shared monthly cap", "1200")));

        service.evaluate(userId);

        assertNull(rule.getCapNotifiedOn(), "not exhausted: no marker, no date");
        assertEquals(earlier, bucket.getCapNotifiedOn(), "same window already recorded");
    }

    @Test
    void notifiedOnIsRecordedEvenWhenTheKindIsSwitchedOff() {
        kinds.put(NotificationKind.REWARD_MILESTONE, false);
        kinds.put(NotificationKind.REWARD_CAP, false);
        prefs(true);
        snapshot(List.of(status("101000", true, WINDOW_END)), List.of(cap(rule.getId(), null, null, "1000")));

        assertEquals(new NotificationOutcome(1, 2, 0), service.evaluate(userId));
        assertEquals(TODAY, milestone.getNotifiedOn());
        assertEquals(TODAY, rule.getCapNotifiedOn());
    }
}
