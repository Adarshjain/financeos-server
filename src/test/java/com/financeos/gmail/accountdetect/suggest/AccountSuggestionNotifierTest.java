package com.financeos.gmail.accountdetect.suggest;

import com.financeos.domain.notification.NotificationKind;
import com.financeos.domain.notification.NotificationPrefs;
import com.financeos.domain.notification.NotificationPrefsLoader;
import com.financeos.domain.notification.NotificationSettingsService;
import com.financeos.domain.notification.UserNotificationSettings;
import com.financeos.domain.notification.feed.AppNotificationKind;
import com.financeos.domain.notification.feed.AppNotificationRefs;
import com.financeos.domain.notification.feed.NotificationFeedService;
import com.financeos.domain.notification.push.PushMessage;
import com.financeos.gmail.accountdetect.Detection;
import com.financeos.gmail.accountdetect.job.DetectionContext;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Instant;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/** One feed entry + push per run with new suggestions; nothing when there are none. */
class AccountSuggestionNotifierTest {

    private final NotificationFeedService feed = mock(NotificationFeedService.class);
    private final NotificationPrefsLoader prefsLoader = mock(NotificationPrefsLoader.class);
    private final NotificationSettingsService settings = mock(NotificationSettingsService.class);
    private final AccountSuggestionNotifier notifier = new AccountSuggestionNotifier(feed, prefsLoader, settings);
    private final UUID userId = UUID.randomUUID();
    private final Instant ranAt = Instant.parse("2026-11-03T05:00:00Z");

    private NotificationPrefs prefs(boolean canSend, boolean kindOn) {
        Map<NotificationKind, Boolean> kinds = new EnumMap<>(NotificationKind.class);
        for (NotificationKind k : NotificationKind.values()) kinds.put(k, true);
        kinds.put(NotificationKind.ACCOUNTS_FOUND, kindOn);
        return new NotificationPrefs(new UserNotificationSettings(userId), canSend, 9, List.of(), kinds);
    }

    @Test
    void laterRunsSayFoundNAccountsAndCards() {
        when(prefsLoader.load(userId)).thenReturn(prefs(true, true));
        notifier.notifyFound(userId, 3, false, ranAt);
        verify(feed).record(userId, AppNotificationKind.ACCOUNTS_FOUND, "We found 3 accounts", "Add them to see your transactions.",
                AppNotificationRefs.NONE, "account-suggestions:" + ranAt.toEpochMilli());
        ArgumentCaptor<PushMessage> push = ArgumentCaptor.forClass(PushMessage.class);
        verify(settings).deliver(any(), push.capture());
        assertThat(push.getValue().title()).isEqualTo("We found 3 accounts");
        assertThat(push.getValue().body()).isEqualTo("Add them to see your transactions.");
    }

    @Test
    void theFirstScanSaysDone() {
        when(prefsLoader.load(userId)).thenReturn(prefs(true, true));
        notifier.notifyFound(userId, 5, true, ranAt);
        verify(feed).record(eq(userId), eq(AppNotificationKind.ACCOUNTS_FOUND), eq("We found 5 accounts"), any(), any(), any());
        assertThat(AccountSuggestionNotifier.title(1, false)).isEqualTo("We found 1 account");
    }

    @Test
    void theSinkSaysDoneOnlyOnTheRunThatCompletesTheFirstScan() {
        AccountSuggestionService service = mock(AccountSuggestionService.class);
        AccountSuggestionNotifier n = mock(AccountSuggestionNotifier.class);
        Detection d = new Detection();
        when(service.apply(userId, d)).thenReturn(2);
        // a first-scan run that did not finish (partial mails): firstScan true, completed false
        new SuggestionSink(service, n).accept(userId, d, new DetectionContext(UUID.randomUUID(), true, false, 10, 10, 0, ranAt));
        verify(n).notifyFound(userId, 2, false, ranAt);
        new SuggestionSink(service, n).accept(userId, d, new DetectionContext(UUID.randomUUID(), true, true, 10, 10, 0, ranAt));
        verify(n).notifyFound(userId, 2, true, ranAt);
    }

    @Test
    void nothingNewSendsNothing() {
        notifier.notifyFound(userId, 0, false, ranAt);
        verifyNoInteractions(feed, settings, prefsLoader);
    }

    @Test
    void aFirstScanThatFoundNothingStillSaysItIsDone() {
        notifier.notifyFound(userId, 0, true, ranAt);
        verify(feed).record(userId, AppNotificationKind.ACCOUNTS_FOUND, "We didn't find any accounts in your email", null,
                AppNotificationRefs.NONE, "account-suggestions:" + ranAt.toEpochMilli());
    }

    @Test
    void theFeedEntryIsWrittenEvenWhenPushIsOffOrTheKindIsDisabled() {
        when(prefsLoader.load(userId)).thenReturn(prefs(false, true));
        notifier.notifyFound(userId, 2, false, ranAt);
        when(prefsLoader.load(userId)).thenReturn(prefs(true, false));
        notifier.notifyFound(userId, 2, false, ranAt.plusSeconds(60));
        verify(feed, org.mockito.Mockito.times(2)).record(eq(userId), eq(AppNotificationKind.ACCOUNTS_FOUND), any(), any(), any(), any());
        verify(settings, never()).deliver(any(), any());
    }

    @Test
    void theSinkAppliesThenNotifiesWithTheRunsCount() {
        AccountSuggestionService service = mock(AccountSuggestionService.class);
        AccountSuggestionNotifier n = mock(AccountSuggestionNotifier.class);
        Detection d = new Detection();
        when(service.apply(userId, d)).thenReturn(4);
        new SuggestionSink(service, n).accept(userId, d, new DetectionContext(UUID.randomUUID(), true, true, 10, 10, 0, ranAt));
        verify(n).notifyFound(userId, 4, true, ranAt);

        when(service.apply(userId, d)).thenReturn(0);
        new SuggestionSink(service, n).accept(userId, d, new DetectionContext(UUID.randomUUID(), false, false, 10, 0, 0, ranAt));
        verify(n).notifyFound(eq(userId), eq(0), eq(false), any());
        verify(n, org.mockito.Mockito.times(2)).notifyFound(any(), anyInt(), anyBoolean(), any());
    }
}
