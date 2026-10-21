package com.financeos.domain.notification;

import com.financeos.core.security.UserContext;
import com.financeos.domain.account.AccountRepository;
import com.financeos.domain.account.AccountType;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Hourly tick (business zone) over every {@link NotificationProducer}. Idempotent through the
 * producers' own markers, so a tick that runs late or twice sends nothing extra, and a tick
 * missed during a deploy is caught up by the next one. Users with push on and a registered
 * device get every producer; card-holders without push still get the producers that feed the
 * in-app feed ({@link NotificationProducer#runsWithoutPush}), i.e. rewards; one producer failing for one user never stops the others.
 */
@Component
public class NotificationScheduler {

    private static final Logger log = LoggerFactory.getLogger(NotificationScheduler.class);

    public record Summary(int users, int evaluated, int recorded, int sent, int failed) {
    }

    private final UserNotificationSettingsRepository settingsRepository;
    private final AccountRepository accountRepository;
    private final List<NotificationProducer> producers;
    private final boolean enabled;

    public NotificationScheduler(UserNotificationSettingsRepository settingsRepository,
                                 AccountRepository accountRepository,
                                 List<NotificationProducer> producers,
                                 @Value("${notifications.enabled:true}") boolean enabled) {
        this.settingsRepository = settingsRepository;
        this.accountRepository = accountRepository;
        this.producers = producers;
        this.enabled = enabled;
    }

    @Scheduled(cron = "${notifications.tick-cron:0 5 * * * *}", zone = "${app.zone:Asia/Kolkata}")
    public void tick() {
        Summary summary = runTick();
        if (summary != null && (summary.recorded() > 0 || summary.failed() > 0)) {
            log.info("Notification tick: users={}, evaluated={}, recorded={}, sent={}, failed={}",
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
                Summary one = runProducers(userId);
                evaluated += one.evaluated();
                recorded += one.recorded();
                sent += one.sent();
                failed += one.failed();
            } finally {
                UserContext.clear();
                MDC.remove("userId");
            }
        }
        // Not push-reachable: only the producers that also feed the in-app feed, for card-holders.
        List<NotificationProducer> feedProducers = producers.stream().filter(NotificationProducer::runsWithoutPush).toList();
        int users = reachable.size();
        if (!feedProducers.isEmpty()) {
            Set<UUID> reachableIds = new HashSet<>();
            reachable.forEach(s -> reachableIds.add(s.getUserId()));
            List<UUID> cardHolders = List.of();
            try {
                cardHolders = accountRepository.findDistinctUserIdsByType(AccountType.credit_card);
            } catch (Exception e) {
                failed++;
                log.error("Notification tick: could not list card-holders for the feed producers", e);
            }
            for (UUID userId : cardHolders) {
                if (reachableIds.contains(userId)) {
                    continue;
                }
                UserContext.setCurrentUserId(userId);
                MDC.put("userId", userId.toString());
                try {
                    Summary one = runProducers(userId, feedProducers);
                    users++;
                    evaluated += one.evaluated();
                    recorded += one.recorded();
                    sent += one.sent();
                    failed += one.failed();
                } finally {
                    UserContext.clear();
                    MDC.remove("userId");
                }
            }
        }
        return new Summary(users, evaluated, recorded, sent, failed);
    }

    /**
     * Every producer for one user, each failure isolated and counted. The caller owns
     * {@code UserContext}; the request-scoped endpoint has it set already, the tick sets it.
     */
    public Summary runProducers(UUID userId) {
        return runProducers(userId, producers);
    }

    private Summary runProducers(UUID userId, List<NotificationProducer> toRun) {
        NotificationOutcome total = NotificationOutcome.NONE;
        int failed = 0;
        for (NotificationProducer producer : toRun) {
            try {
                total = total.plus(producer.evaluate(userId));
            } catch (Exception e) {
                failed++;
                log.error("Notification producer {} failed for user {}", producer.name(), userId, e);
            }
        }
        return new Summary(1, total.evaluated(), total.recorded(), total.sent(), failed);
    }
}
