package com.financeos.domain.notification.gmail;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.financeos.core.time.AppTime;
import com.financeos.domain.notification.NotificationKind;
import com.financeos.domain.notification.NotificationOutcome;
import com.financeos.domain.notification.NotificationPrefs;
import com.financeos.domain.notification.NotificationPrefsLoader;
import com.financeos.domain.notification.NotificationSettingsCodec;
import com.financeos.domain.notification.NotificationSettingsService;
import com.financeos.domain.notification.UserNotificationSettings;
import com.financeos.domain.notification.push.PushMessage;
import com.financeos.domain.user.User;
import com.financeos.gmail.domain.GmailConnection;
import com.financeos.gmail.domain.GmailConnectionRepository;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class GmailReconnectNotificationServiceTest {

    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");
    private static final LocalDate TODAY = LocalDate.of(2026, 10, 20);

    private final GmailConnectionRepository repository = mock(GmailConnectionRepository.class);
    private final NotificationPrefsLoader prefsLoader = mock(NotificationPrefsLoader.class);
    private final NotificationSettingsService settingsService = mock(NotificationSettingsService.class);
    private final GmailReconnectNotificationService service =
            new GmailReconnectNotificationService(repository, prefsLoader, settingsService);

    private final UUID userId = UUID.randomUUID();
    private GmailConnection connection;
    private UserNotificationSettings settings;
    private Map<NotificationKind, Boolean> kinds;

    @BeforeEach
    void setUp() {
        clockAt(10);
        User user = new User();
        user.setId(userId);
        connection = new GmailConnection();
        connection.setId(UUID.randomUUID());
        connection.setUser(user);
        connection.setEmail("ajay@example.test");
        connection.setIsConnected(true);
        settings = new UserNotificationSettings(userId);
        kinds = new EnumMap<>(NotificationSettingsCodec.parseKinds(null));
        when(repository.findById(connection.getId())).thenReturn(Optional.of(connection));
        when(settingsService.deliver(any(), any())).thenReturn(1);
        prefs(true);
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

    private Instant daysAgo(int days) {
        return TODAY.minusDays(days).atTime(10, 5).atZone(IST).toInstant();
    }

    private void dead(Instant notifiedAt) {
        connection.setAuthFailedAt(daysAgo(10));
        connection.setReconnectNotifiedAt(notifiedAt);
        when(repository.findByUserIdAndIsConnectedTrueAndAuthFailedAtIsNotNull(userId)).thenReturn(List.of(connection));
    }

    // ---------------------------------------------------------------- onAuthFailure (event path)

    @Test
    void firstTokenRejectionStampsTheConnectionAndPushesImmediatelyWhateverTheHour() {
        clockAt(2);

        service.onAuthFailure(connection.getId());

        assertNotNull(connection.getAuthFailedAt());
        assertNotNull(connection.getReconnectNotifiedAt());
        ArgumentCaptor<PushMessage> message = ArgumentCaptor.forClass(PushMessage.class);
        verify(settingsService).deliver(eq(settings), message.capture());
        assertEquals("Gmail disconnected", message.getValue().title());
        assertEquals("Reconnect ajay@example.test to keep importing alerts and statements.", message.getValue().body());
        assertEquals("/inbox?item=gmail-reconnect:" + connection.getId(), message.getValue().url());
        assertEquals("gmail-reconnect-" + connection.getId(), message.getValue().tag());
    }

    @Test
    void repeatedFailuresOfAnAlreadyDeadMailboxChangeNothing() {
        Instant first = daysAgo(2);
        connection.setAuthFailedAt(first);
        connection.setReconnectNotifiedAt(first);

        service.onAuthFailure(connection.getId());

        assertEquals(first, connection.getAuthFailedAt(), "the first failure time is kept");
        assertEquals(first, connection.getReconnectNotifiedAt());
        verify(settingsService, never()).deliver(any(), any());
        verify(repository, never()).save(any());
    }

    @Test
    void markerAdvancesEvenWhenNothingCanBeSent() {
        prefs(false);

        service.onAuthFailure(connection.getId());

        assertNotNull(connection.getReconnectNotifiedAt());
        verify(settingsService, never()).deliver(any(), any());
    }

    @Test
    void kindSwitchedOffStillRecordsButDoesNotPush() {
        kinds.put(NotificationKind.GMAIL_RECONNECT, false);
        prefs(true);

        service.onAuthFailure(connection.getId());

        assertNotNull(connection.getReconnectNotifiedAt());
        verify(settingsService, never()).deliver(any(), any());
    }

    @Test
    void userDisconnectedMailboxIsStampedButNeverNagged() {
        connection.setIsConnected(false);

        service.onAuthFailure(connection.getId());

        assertNotNull(connection.getAuthFailedAt());
        assertNull(connection.getReconnectNotifiedAt());
        verify(settingsService, never()).deliver(any(), any());
    }

    @Test
    void unknownConnectionIsIgnored() {
        UUID other = UUID.randomUUID();
        when(repository.findById(other)).thenReturn(Optional.empty());

        service.onAuthFailure(other);

        verify(settingsService, never()).deliver(any(), any());
    }

    // ---------------------------------------------------------------- evaluate (tick path)

    @Test
    void nothingDeadMeansNoWork() {
        when(repository.findByUserIdAndIsConnectedTrueAndAuthFailedAtIsNotNull(userId)).thenReturn(List.of());
        assertEquals(NotificationOutcome.NONE, service.evaluate(userId));
        verify(prefsLoader, never()).load(any());
    }

    @Test
    void deadMailboxNeverAnnouncedIsAnnouncedByTheTickAtAnyHour() {
        clockAt(3);
        dead(null);

        assertEquals(new NotificationOutcome(1, 1, 1), service.evaluate(userId));
        assertNotNull(connection.getReconnectNotifiedAt());
    }

    @Test
    void reNagsWeeklyAfterTheSendHour() {
        dead(daysAgo(7));

        assertEquals(new NotificationOutcome(1, 1, 1), service.evaluate(userId));
        assertEquals(daysAgo(0), connection.getReconnectNotifiedAt());
    }

    @Test
    void noReNagInsideTheWeek() {
        Instant last = daysAgo(6);
        dead(last);

        assertEquals(new NotificationOutcome(1, 0, 0), service.evaluate(userId));
        assertEquals(last, connection.getReconnectNotifiedAt());
        verify(settingsService, never()).deliver(any(), any());
    }

    @Test
    void reNagWaitsForTheSendHour() {
        clockAt(8);
        Instant last = daysAgo(9);
        dead(last);

        assertEquals(new NotificationOutcome(1, 0, 0), service.evaluate(userId));
        assertEquals(last, connection.getReconnectNotifiedAt());
    }

    @Test
    void weeklyReNagRecordsWithoutPushingWhenPushIsOff() {
        prefs(false);
        dead(daysAgo(8));

        assertEquals(new NotificationOutcome(1, 1, 0), service.evaluate(userId));
        assertEquals(daysAgo(0), connection.getReconnectNotifiedAt());
        verify(settingsService, never()).deliver(any(), any());
    }
}
