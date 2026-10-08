package com.financeos.domain.notification;

import com.financeos.domain.notification.push.WebPushSender;
import java.util.UUID;
import org.springframework.stereotype.Component;

/** Reads a user's settings row into {@link NotificationPrefs}, with defaults when there is none. */
@Component
public class NotificationPrefsLoader {

    private final UserNotificationSettingsRepository settingsRepository;
    private final WebPushSender sender;

    public NotificationPrefsLoader(UserNotificationSettingsRepository settingsRepository, WebPushSender sender) {
        this.settingsRepository = settingsRepository;
        this.sender = sender;
    }

    public NotificationPrefs load(UUID userId) {
        UserNotificationSettings settings = settingsRepository.findById(userId).orElse(null);
        if (settings == null) {
            return new NotificationPrefs(null, false, UserNotificationSettings.DEFAULT_SEND_HOUR,
                    NotificationSettingsCodec.parseOffsets(null), NotificationSettingsCodec.parseKinds(null));
        }
        boolean canSend = !Boolean.FALSE.equals(settings.getPushEnabled())
                && !NotificationSettingsCodec.parseSubscriptions(settings.getPushSubscriptions()).isEmpty()
                && sender.isConfigured();
        return new NotificationPrefs(settings, canSend,
                settings.getSendHour() == null ? UserNotificationSettings.DEFAULT_SEND_HOUR : settings.getSendHour(),
                NotificationSettingsCodec.parseOffsets(settings.getReminderOffsets()),
                NotificationSettingsCodec.parseKinds(settings.getKindsJson()));
    }
}
