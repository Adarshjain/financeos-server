package com.financeos.domain.notification.gmail;

import com.financeos.core.time.AppTime;
import com.financeos.domain.notification.NotificationKind;
import com.financeos.domain.notification.NotificationOutcome;
import com.financeos.domain.notification.NotificationPrefs;
import com.financeos.domain.notification.NotificationPrefsLoader;
import com.financeos.domain.notification.NotificationProducer;
import com.financeos.domain.notification.NotificationSettingsService;
import com.financeos.domain.notification.feed.AppNotificationKind;
import com.financeos.domain.notification.feed.AppNotificationRefs;
import com.financeos.domain.notification.feed.NotificationFeedService;
import com.financeos.gmail.domain.GmailConnection;
import com.financeos.gmail.domain.GmailConnectionRepository;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * "Reconnect Gmail": a mailbox whose refresh token Google rejected. The sync job reports the
 * failure through {@link #onAuthFailure}, which stamps {@code auth_failed_at} and pushes once,
 * immediately. While the mailbox stays dead the hourly tick re-nags weekly, after the send
 * hour. A reconnect clears both columns (see {@code GmailOAuthService}). The marker is
 * {@code reconnect_notified_at}; it advances even when nothing can be sent.
 */
@Service
public class GmailReconnectNotificationService implements NotificationProducer {

    private static final Logger log = LoggerFactory.getLogger(GmailReconnectNotificationService.class);
    static final int RENAG_DAYS = 7;

    private final GmailConnectionRepository connectionRepository;
    private final NotificationPrefsLoader prefsLoader;
    private final NotificationSettingsService settingsService;
    private final NotificationFeedService feedService;

    public GmailReconnectNotificationService(GmailConnectionRepository connectionRepository,
                                             NotificationPrefsLoader prefsLoader,
                                             NotificationSettingsService settingsService,
                                             NotificationFeedService feedService) {
        this.connectionRepository = connectionRepository;
        this.prefsLoader = prefsLoader;
        this.settingsService = settingsService;
        this.feedService = feedService;
    }

    @Override
    public String name() {
        return "gmail-reconnect";
    }

    /**
     * Google rejected the token during a sync. The first time: stamp the failure and tell the user
     * right away (any hour — the alerts have stopped). Later failures of the same dead mailbox
     * (a manual retry) change nothing; the weekly re-nag is the tick's job.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void onAuthFailure(UUID connectionId) {
        GmailConnection connection = connectionRepository.findById(connectionId).orElse(null);
        if (connection == null || connection.getAuthFailedAt() != null) {
            return;
        }
        Instant failedAt = nowInstant();
        connection.setAuthFailedAt(failedAt);
        connectionRepository.save(connection);
        log.warn("Gmail connection {} ({}) needs reconnect: token rejected", connectionId, connection.getEmail());
        if (Boolean.TRUE.equals(connection.getIsConnected())) {
            // The in-app feed hears about it once per detection, whatever the push settings are.
            feedService.record(connection.getUser().getId(), AppNotificationKind.GMAIL_DISCONNECTED, "Gmail disconnected",
                    connection.getEmail() + " · reconnect to keep syncing", AppNotificationRefs.ofConnection(connection.getId()),
                    "gmail-reconnect:" + connection.getId() + ":" + failedAt.toEpochMilli());
            notify(connection, prefsLoader.load(connection.getUser().getId()));
        }
    }

    @Override
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public NotificationOutcome evaluate(UUID userId) {
        List<GmailConnection> dead = connectionRepository.findByUserIdAndIsConnectedTrueAndAuthFailedAtIsNotNull(userId);
        if (dead.isEmpty()) {
            return NotificationOutcome.NONE;
        }
        NotificationPrefs prefs = prefsLoader.load(userId);
        LocalDateTime now = AppTime.now();
        int recorded = 0;
        int sent = 0;
        for (GmailConnection connection : dead) {
            boolean first = connection.getReconnectNotifiedAt() == null;
            if (!first) {
                if (!prefs.pastSendHour(now)) {
                    continue;
                }
                LocalDateTime last = LocalDateTime.ofInstant(connection.getReconnectNotifiedAt(), AppTime.zone());
                if (last.isAfter(now.minusDays(RENAG_DAYS))) {
                    continue;
                }
            }
            sent += notify(connection, prefs);
            recorded++;
        }
        return new NotificationOutcome(dead.size(), recorded, sent);
    }

    private int notify(GmailConnection connection, NotificationPrefs prefs) {
        int sent = 0;
        if (prefs.deliverable(NotificationKind.GMAIL_RECONNECT)) {
            sent = settingsService.deliver(prefs.settings(), GmailMessages.reconnect(connection));
            log.info("Gmail reconnect notification: connectionId={}, devices={}", connection.getId(), sent);
        }
        connection.setReconnectNotifiedAt(nowInstant());
        connectionRepository.save(connection);
        return sent;
    }

    private static Instant nowInstant() {
        return AppTime.now().atZone(AppTime.zone()).toInstant();
    }
}
