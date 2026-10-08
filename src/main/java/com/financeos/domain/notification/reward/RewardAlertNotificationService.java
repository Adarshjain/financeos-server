package com.financeos.domain.notification.reward;

import com.financeos.api.reward.dto.RewardReportResponse;
import com.financeos.core.time.AppTime;
import com.financeos.domain.account.Account;
import com.financeos.domain.account.AccountRepository;
import com.financeos.domain.account.AccountType;
import com.financeos.domain.notification.NotificationKind;
import com.financeos.domain.notification.NotificationOutcome;
import com.financeos.domain.notification.NotificationPrefs;
import com.financeos.domain.notification.NotificationPrefsLoader;
import com.financeos.domain.notification.NotificationProducer;
import com.financeos.domain.notification.NotificationSettingsService;
import com.financeos.domain.reward.RewardCalculationService;
import com.financeos.domain.reward.RewardCapBucket;
import com.financeos.domain.reward.RewardCapBucketRepository;
import com.financeos.domain.reward.RewardMilestone;
import com.financeos.domain.reward.RewardMilestoneRepository;
import com.financeos.domain.reward.RewardRule;
import com.financeos.domain.reward.RewardRuleRepository;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Rewards alerts, once per card per day (the engine evaluation is the app's heaviest call, so
 * never hourly): a milestone whose window closes within {@value #CLOSING_WINDOW_DAYS} days with
 * at least half the threshold reached ("₹X more to go"), a milestone achieved, and a period cap
 * exhausted for the current window. Markers: the milestone row (window start + kind), the rule or
 * bucket row (window start), the date each marker was recorded (the inbox's recency test), and {@code accounts.reward_alerts_checked_on} for the daily gate.
 */
@Service
public class RewardAlertNotificationService implements NotificationProducer {

    private static final Logger log = LoggerFactory.getLogger(RewardAlertNotificationService.class);
    static final int CLOSING_WINDOW_DAYS = 7;
    static final BigDecimal CLOSING_PROGRESS_RATIO = new BigDecimal("0.5");
    static final String KIND_CLOSING = "CLOSING";
    public static final String KIND_ACHIEVED = "ACHIEVED";

    private final AccountRepository accountRepository;
    private final RewardRuleRepository ruleRepository;
    private final RewardMilestoneRepository milestoneRepository;
    private final RewardCapBucketRepository bucketRepository;
    private final RewardCalculationService calculationService;
    private final NotificationPrefsLoader prefsLoader;
    private final NotificationSettingsService settingsService;

    public RewardAlertNotificationService(AccountRepository accountRepository,
                                          RewardRuleRepository ruleRepository,
                                          RewardMilestoneRepository milestoneRepository,
                                          RewardCapBucketRepository bucketRepository,
                                          RewardCalculationService calculationService,
                                          NotificationPrefsLoader prefsLoader,
                                          NotificationSettingsService settingsService) {
        this.accountRepository = accountRepository;
        this.ruleRepository = ruleRepository;
        this.milestoneRepository = milestoneRepository;
        this.bucketRepository = bucketRepository;
        this.calculationService = calculationService;
        this.prefsLoader = prefsLoader;
        this.settingsService = settingsService;
    }

    @Override
    public String name() {
        return "rewards";
    }

    @Override
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public NotificationOutcome evaluate(UUID userId) {
        NotificationPrefs prefs = prefsLoader.load(userId);
        LocalDate today = AppTime.today();
        if (!prefs.pastSendHour(AppTime.now())) {
            return NotificationOutcome.NONE;
        }
        int evaluated = 0;
        int recorded = 0;
        int sent = 0;
        for (Account card : accountRepository.findByUserIdAndType(userId, AccountType.credit_card)) {
            if (card.isClosed(today) || today.equals(card.getRewardAlertsCheckedOn())) {
                continue;
            }
            List<RewardRule> rules = ruleRepository.findByAccountIdOrderByPriorityDesc(card.getId());
            List<RewardMilestone> milestones = milestoneRepository.findByAccountIdOrderByCreatedAtAsc(card.getId());
            if (rules.isEmpty() && milestones.isEmpty()) {
                continue;
            }
            evaluated++;
            RewardCalculationService.AlertSnapshot snapshot = calculationService.alertSnapshot(card.getId(), today);

            Map<UUID, RewardMilestone> milestoneById = milestones.stream().collect(Collectors.toMap(RewardMilestone::getId, Function.identity(), (a, b) -> a));
            for (RewardReportResponse.MilestoneStatus status : snapshot.milestones()) {
                RewardMilestone milestone = milestoneById.get(status.milestoneId());
                if (milestone == null) {
                    continue;
                }
                String kind = milestoneKind(status, today, milestone.getNotifiedWindowStart(), milestone.getNotifiedKind());
                if (kind == null) {
                    continue;
                }
                if (prefs.deliverable(NotificationKind.REWARD_MILESTONE)) {
                    sent += settingsService.deliver(prefs.settings(), KIND_ACHIEVED.equals(kind)
                            ? RewardMessages.milestoneAchieved(card, status) : RewardMessages.milestoneClosing(card, status));
                    log.info("Reward milestone notification: kind={}, milestoneId={}", kind, milestone.getId());
                }
                milestone.setNotifiedWindowStart(status.windowStart());
                milestone.setNotifiedKind(kind);
                milestone.setNotifiedOn(today);
                milestoneRepository.save(milestone);
                recorded++;
            }

            Map<UUID, RewardRule> ruleById = rules.stream().collect(Collectors.toMap(RewardRule::getId, Function.identity(), (a, b) -> a));
            Map<UUID, RewardCapBucket> bucketById = bucketRepository.findByAccountIdOrderByCreatedAtAsc(card.getId()).stream()
                    .collect(Collectors.toMap(RewardCapBucket::getId, Function.identity(), (a, b) -> a));
            for (RewardCalculationService.CapUsage cap : snapshot.caps()) {
                if (!capExhaustedNow(cap, today)) {
                    continue;
                }
                RewardCapBucket bucket = cap.bucketId() != null ? bucketById.get(cap.bucketId()) : null;
                RewardRule rule = cap.bucketId() == null && cap.ruleId() != null ? ruleById.get(cap.ruleId()) : null;
                LocalDate already = bucket != null ? bucket.getCapNotifiedWindowStart() : rule != null ? rule.getCapNotifiedWindowStart() : null;
                if ((bucket == null && rule == null) || Objects.equals(already, cap.windowStart())) {
                    continue;
                }
                if (prefs.deliverable(NotificationKind.REWARD_CAP)) {
                    sent += settingsService.deliver(prefs.settings(), RewardMessages.capReached(card, cap));
                    log.info("Reward cap notification: owner={}, window={}", bucket != null ? bucket.getId() : rule.getId(), cap.windowStart());
                }
                if (bucket != null) {
                    bucket.setCapNotifiedWindowStart(cap.windowStart());
                    bucket.setCapNotifiedOn(today);
                    bucketRepository.save(bucket);
                } else {
                    rule.setCapNotifiedWindowStart(cap.windowStart());
                    rule.setCapNotifiedOn(today);
                    ruleRepository.save(rule);
                }
                recorded++;
            }

            card.setRewardAlertsCheckedOn(today);
            accountRepository.save(card);
        }
        return new NotificationOutcome(evaluated, recorded, sent);
    }

    // ---------------------------------------------------------------- pure (package-private for tests)

    /**
     * ACHIEVED when the milestone is met and not yet announced for this window; CLOSING when the
     * window ends within {@value #CLOSING_WINDOW_DAYS} days, at least half the threshold is reached
     * and nothing was announced for this window yet; otherwise null.
     */
    static String milestoneKind(RewardReportResponse.MilestoneStatus status, LocalDate today, LocalDate notifiedWindowStart, String notifiedKind) {
        boolean sameWindow = status.windowStart() != null && status.windowStart().equals(notifiedWindowStart);
        String last = sameWindow ? notifiedKind : null;
        if (status.achieved()) {
            return KIND_ACHIEVED.equals(last) ? null : KIND_ACHIEVED;
        }
        if (last != null || status.windowEnd() == null || status.threshold() == null || status.threshold().signum() <= 0) {
            return null;
        }
        long daysLeft = ChronoUnit.DAYS.between(today, status.windowEnd());
        if (daysLeft < 0 || daysLeft > CLOSING_WINDOW_DAYS) {
            return null;
        }
        BigDecimal progress = status.progress() == null ? BigDecimal.ZERO : status.progress();
        // Compare against the scaled threshold rather than a rounded ratio: 49,999 of 1,00,000 is not half.
        return progress.compareTo(status.threshold().multiply(CLOSING_PROGRESS_RATIO)) >= 0 ? KIND_CLOSING : null;
    }

    /** The cap is used up and today sits inside the window it was used up in. */
    static boolean capExhaustedNow(RewardCalculationService.CapUsage cap, LocalDate today) {
        if (cap.cap() == null || cap.used() == null || cap.used().compareTo(cap.cap()) < 0) {
            return false;
        }
        return cap.windowStart() != null && cap.windowEnd() != null
                && !today.isBefore(cap.windowStart()) && !today.isAfter(cap.windowEnd());
    }
}
