package com.financeos.domain.notification;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.financeos.domain.notification.push.WebPushSender;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class NotificationPrefsLoaderTest {

    private static final String DEVICE = "[{\"endpoint\":\"https://push/1\",\"p256dh\":\"k\",\"auth\":\"a\"}]";

    private final UserNotificationSettingsRepository repository = mock(UserNotificationSettingsRepository.class);
    private final WebPushSender sender = mock(WebPushSender.class);
    private final NotificationPrefsLoader loader = new NotificationPrefsLoader(repository, sender);
    private final UUID userId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        when(sender.isConfigured()).thenReturn(true);
    }

    @Test
    void noRowMeansDefaultsAndNothingCanBeSent() {
        when(repository.findById(userId)).thenReturn(Optional.empty());

        NotificationPrefs prefs = loader.load(userId);

        assertNull(prefs.settings());
        assertFalse(prefs.canSend());
        assertEquals(UserNotificationSettings.DEFAULT_SEND_HOUR, prefs.sendHour());
        assertEquals(List.of(7, 3, 1, 0), prefs.offsets());
        assertTrue(prefs.allows(NotificationKind.EMI_DUE_REMINDER), "every kind defaults to on");
        assertFalse(prefs.deliverable(NotificationKind.EMI_DUE_REMINDER), "allowed but not deliverable without a row");
    }

    @Test
    void rowWithDeviceAndPushOnCanSend() {
        UserNotificationSettings settings = new UserNotificationSettings(userId);
        settings.setPushSubscriptions(DEVICE);
        settings.setSendHour(7);
        settings.setReminderOffsets("5,1");
        settings.setKindsJson("{\"GMAIL_ATTENTION\":false}");
        when(repository.findById(userId)).thenReturn(Optional.of(settings));

        NotificationPrefs prefs = loader.load(userId);

        assertTrue(prefs.canSend());
        assertEquals(7, prefs.sendHour());
        assertEquals(List.of(5, 1), prefs.offsets());
        assertFalse(prefs.allows(NotificationKind.GMAIL_ATTENTION));
        assertFalse(prefs.deliverable(NotificationKind.GMAIL_ATTENTION));
        assertTrue(prefs.deliverable(NotificationKind.GMAIL_RECONNECT));
        assertFalse(prefs.allows(null));
    }

    @Test
    void pushOffOrNoDeviceOrUnconfiguredServerCannotSend() {
        UserNotificationSettings off = new UserNotificationSettings(userId);
        off.setPushSubscriptions(DEVICE);
        off.setPushEnabled(false);
        when(repository.findById(userId)).thenReturn(Optional.of(off));
        assertFalse(loader.load(userId).canSend(), "push disabled");

        UserNotificationSettings noDevice = new UserNotificationSettings(userId);
        when(repository.findById(userId)).thenReturn(Optional.of(noDevice));
        assertFalse(loader.load(userId).canSend(), "no device");

        UserNotificationSettings ok = new UserNotificationSettings(userId);
        ok.setPushSubscriptions(DEVICE);
        when(repository.findById(userId)).thenReturn(Optional.of(ok));
        when(sender.isConfigured()).thenReturn(false);
        assertFalse(loader.load(userId).canSend(), "no VAPID keys");
    }

    @Test
    void sendHourGateIsInclusive() {
        NotificationPrefs prefs = new NotificationPrefs(null, false, 9, List.of(0), NotificationSettingsCodec.parseKinds(null));
        assertFalse(prefs.pastSendHour(LocalDateTime.of(2026, 10, 20, 8, 59)));
        assertTrue(prefs.pastSendHour(LocalDateTime.of(2026, 10, 20, 9, 0)));
        assertTrue(prefs.pastSendHour(LocalDateTime.of(2026, 10, 20, 23, 5)));
    }

    @Test
    void outcomesAdd() {
        assertEquals(new NotificationOutcome(3, 2, 1), new NotificationOutcome(1, 1, 1).plus(new NotificationOutcome(2, 1, 0)));
        assertEquals(NotificationOutcome.NONE, NotificationOutcome.NONE.plus(NotificationOutcome.NONE));
    }
}
