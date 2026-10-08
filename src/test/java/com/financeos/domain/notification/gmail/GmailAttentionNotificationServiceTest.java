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
import com.financeos.gmail.domain.GmailProcessedMessage;
import com.financeos.gmail.domain.GmailProcessedMessageRepository;
import com.financeos.gmail.domain.GmailProcessedStatus;
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

class GmailAttentionNotificationServiceTest {

    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");
    private static final LocalDate TODAY = LocalDate.of(2026, 10, 20);

    private final GmailProcessedMessageRepository repository = mock(GmailProcessedMessageRepository.class);
    private final NotificationPrefsLoader prefsLoader = mock(NotificationPrefsLoader.class);
    private final NotificationSettingsService settingsService = mock(NotificationSettingsService.class);
    private final GmailAttentionNotificationService service =
            new GmailAttentionNotificationService(repository, prefsLoader, settingsService);

    private final UUID userId = UUID.randomUUID();
    private final UserNotificationSettings settings = new UserNotificationSettings(userId);
    private final Map<NotificationKind, Boolean> kinds = new EnumMap<>(NotificationSettingsCodec.parseKinds(null));

    @BeforeEach
    void setUp() {
        clockAt(10);
        when(settingsService.deliver(any(), any())).thenReturn(2);
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

    private static GmailProcessedMessage item(GmailProcessedStatus status, String error) {
        GmailProcessedMessage m = new GmailProcessedMessage();
        m.setId(UUID.randomUUID());
        m.setStatus(status);
        m.setError(error);
        return m;
    }

    private void items(GmailProcessedMessage... items) {
        when(repository.findUnnotifiedAttentionItems(userId, GmailAttentionNotificationService.ATTENTION_STATUSES))
                .thenReturn(List.of(items));
    }

    @Test
    void waitsForTheSendHour() {
        clockAt(8);
        assertEquals(NotificationOutcome.NONE, service.evaluate(userId));
        verify(repository, never()).findUnnotifiedAttentionItems(any(), any());
    }

    @Test
    void nothingNewMeansNoDigest() {
        items();
        assertEquals(NotificationOutcome.NONE, service.evaluate(userId));
        verify(settingsService, never()).deliver(any(), any());
    }

    @Test
    void oneDigestCoversEveryNewItemAndStampsThemAll() {
        GmailProcessedMessage a = item(GmailProcessedStatus.UNRESOLVED_ACCOUNT, null);
        GmailProcessedMessage b = item(GmailProcessedStatus.UNRESOLVED_ACCOUNT, null);
        GmailProcessedMessage c = item(GmailProcessedStatus.ACCOUNT_NOT_OPTED_IN, null);
        GmailProcessedMessage d = item(GmailProcessedStatus.FAILED_PERMANENT, "Extraction failed: boom");
        items(a, b, c, d);

        assertEquals(new NotificationOutcome(4, 1, 2), service.evaluate(userId));

        ArgumentCaptor<PushMessage> message = ArgumentCaptor.forClass(PushMessage.class);
        verify(settingsService).deliver(eq(settings), message.capture());
        assertEquals("4 emails need attention", message.getValue().title());
        assertEquals("2 couldn't be matched to an account · 1 from an account not opted in · 1 failed to import",
                message.getValue().body());
        assertEquals("/settings/gmail?focus=attention", message.getValue().url());
        assertEquals("gmail-attention", message.getValue().tag());
        for (GmailProcessedMessage m : List.of(a, b, c, d)) {
            assertNotNull(m.getAttentionNotifiedAt());
        }
        verify(repository).saveAll(List.of(a, b, c, d));
    }

    @Test
    void singularTitleAndPluralOptedInWording() {
        items(item(GmailProcessedStatus.ACCOUNT_NOT_OPTED_IN, null));
        service.evaluate(userId);
        ArgumentCaptor<PushMessage> message = ArgumentCaptor.forClass(PushMessage.class);
        verify(settingsService).deliver(eq(settings), message.capture());
        assertEquals("1 email needs attention", message.getValue().title());
        assertEquals("1 from an account not opted in", message.getValue().body());

        List<GmailProcessedMessage> two = List.of(item(GmailProcessedStatus.ACCOUNT_NOT_OPTED_IN, null),
                item(GmailProcessedStatus.ACCOUNT_NOT_OPTED_IN, null));
        assertEquals("2 from accounts not opted in", GmailMessages.attention(two).body());
    }

    @Test
    void onlyMissingLlmKeyFailuresPointAtTheKeysPage() {
        items(item(GmailProcessedStatus.FAILED_PERMANENT, "needs attention: add an API key in Settings"),
                item(GmailProcessedStatus.FAILED_PERMANENT, "needs attention: add an API key in Settings"));

        service.evaluate(userId);

        ArgumentCaptor<PushMessage> message = ArgumentCaptor.forClass(PushMessage.class);
        verify(settingsService).deliver(eq(settings), message.capture());
        assertEquals("Gmail imports are paused", message.getValue().title());
        assertEquals("Add an LLM API key in Settings to process 2 waiting emails.", message.getValue().body());
        assertEquals("/settings/llm-keys", message.getValue().url());
    }

    @Test
    void mixedFailuresUseTheGeneralDigestEvenWhenSomeAreMissingKeys() {
        PushMessage message = GmailMessages.attention(List.of(
                item(GmailProcessedStatus.FAILED_PERMANENT, "needs attention: add an API key in Settings"),
                item(GmailProcessedStatus.UNRESOLVED_ACCOUNT, null)));
        assertEquals("2 emails need attention", message.title());
        assertEquals("1 couldn't be matched to an account · 1 failed to import", message.body());
        assertEquals("Add an LLM API key in Settings to process 1 waiting email.",
                GmailMessages.attention(List.of(item(GmailProcessedStatus.FAILED_PERMANENT, "needs attention: add an API key in Settings"))).body());
    }

    @Test
    void stampsItemsWithoutPushingWhenPushIsOff() {
        prefs(false);
        GmailProcessedMessage a = item(GmailProcessedStatus.UNRESOLVED_ACCOUNT, null);
        items(a);

        assertEquals(new NotificationOutcome(1, 1, 0), service.evaluate(userId));
        assertNotNull(a.getAttentionNotifiedAt());
        verify(settingsService, never()).deliver(any(), any());
    }

    @Test
    void stampsItemsWithoutPushingWhenTheKindIsOff() {
        kinds.put(NotificationKind.GMAIL_ATTENTION, false);
        prefs(true);
        GmailProcessedMessage a = item(GmailProcessedStatus.FAILED_PERMANENT, "x");
        assertNull(a.getAttentionNotifiedAt());
        items(a);

        assertEquals(new NotificationOutcome(1, 1, 0), service.evaluate(userId));
        assertNotNull(a.getAttentionNotifiedAt());
        verify(settingsService, never()).deliver(any(), any());
    }
}
