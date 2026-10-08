package com.financeos.domain.notification.bill;

import com.financeos.core.security.UserContext;
import com.financeos.domain.notification.UserNotificationSettings;
import com.financeos.domain.notification.UserNotificationSettingsRepository;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Hourly tick (business zone). Idempotent through the per-statement markers, so a tick that
 * runs late or twice sends nothing extra, and a tick missed during a deploy is caught up by the
 * next one. Only users with push on and a registered device are visited.
 */
@Component
public class BillNotificationScheduler {

    private static final Logger log = LoggerFactory.getLogger(BillNotificationScheduler.class);

    public record Summary(int users, int evaluated, int recorded, int sent, int failed) {
    }

    private final UserNotificationSettingsRepository settingsRepository;
    private final BillNotificationService billNotificationService;
    private final boolean enabled;

    public BillNotificationScheduler(UserNotificationSettingsRepository settingsRepository,
                                     BillNotificationService billNotificationService,
                                     @Value("${notifications.enabled:true}") boolean enabled) {
        this.settingsRepository = settingsRepository;
        this.billNotificationService = billNotificationService;
        this.enabled = enabled;
    }

    @Scheduled(cron = "${notifications.tick-cron:0 5 * * * *}", zone = "${app.zone:Asia/Kolkata}")
    public void tick() {
        Summary summary = runTick();
        if (summary != null && (summary.recorded() > 0 || summary.failed() > 0)) {
            log.info("Bill notification tick: users={}, evaluated={}, recorded={}, sent={}, failed={}",
                    summary.users(), summary.evaluated(), summary.recorded(), summary.sent(), summary.failed());
        }
    }

    public Summary runTick() {
        if (!enabled) {
            return null;
        }
        List<UserNotificationSettings> reachable = settingsRepository.findByPushEnabledTrueAndPushSubscriptionsIsNotNull();
        int evaluated = 0;
        int recorded = 0;
        int sent = 0;
        int failed = 0;
        for (UserNotificationSettings settings : reachable) {
            UUID userId = settings.getUserId();
            // Pooled scheduler thread: scope every read to this user and always clear afterwards.
            UserContext.setCurrentUserId(userId);
            MDC.put("userId", userId.toString());
            try {
                BillNotificationService.Outcome outcome = billNotificationService.evaluateUser(userId);
                evaluated += outcome.evaluated();
                recorded += outcome.recorded();
                sent += outcome.sent();
            } catch (Exception e) {
                failed++;
                log.error("Bill notification tick failed for user {}", userId, e);
            } finally {
                UserContext.clear();
                MDC.remove("userId");
            }
        }
        return new Summary(reachable.size(), evaluated, recorded, sent, failed);
    }
}
