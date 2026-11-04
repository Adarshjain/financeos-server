package com.financeos.domain.notification.feed;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyIterable;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.financeos.core.time.AppTime;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.transaction.PlatformTransactionManager;

/** upsert / resolve of the in-place reminder rows. */
class NotificationFeedServiceReminderTest {

    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");
    private static final Instant NOW = LocalDateTime.of(2026, 11, 4, 10, 0).atZone(IST).toInstant();

    private final AppNotificationRepository repository = mock(AppNotificationRepository.class);
    private final NotificationFeedService service = new NotificationFeedService(repository, mock(PlatformTransactionManager.class));
    private final UUID userId = UUID.randomUUID();
    private final UUID accountId = UUID.randomUUID();
    private final String key = "bill:" + UUID.randomUUID();

    @BeforeEach
    void setUp() {
        AppTime.useClock(Clock.fixed(NOW, IST));
    }

    @AfterEach
    void tearDown() {
        AppTime.reset();
    }

    private AppNotification existing(String stage, String title) {
        AppNotification n = new AppNotification();
        n.setId(UUID.randomUUID());
        n.setUserId(userId);
        n.setKind(AppNotificationKind.BILL_REMINDER);
        n.setDedupeKey(key);
        n.setTitle(title);
        n.setBody("₹100 due 6 Nov");
        n.setStage(stage);
        n.setCreatedAt(NOW.minusSeconds(86400));
        n.setAccountId(accountId);
        return n;
    }

    private void upsert(String stage, String title) {
        service.upsert(userId, key, AppNotificationKind.BILL_REMINDER, title, "₹100 due 6 Nov", AppNotificationRefs.ofAccount(accountId), stage);
    }

    @SuppressWarnings("unchecked")
    private AppNotification saved() {
        ArgumentCaptor<List<AppNotification>> captor = ArgumentCaptor.forClass(List.class);
        verify(repository).saveAll(captor.capture());
        assertEquals(1, captor.getValue().size());
        return captor.getValue().get(0);
    }

    @Test
    void firstUpsertInsertsTheRowWithItsRefsAndStage() {
        when(repository.findByUserIdAndDedupeKeyIn(any(), any())).thenReturn(List.of());

        upsert("D3", "HDFC bill due in 3 days");

        AppNotification n = saved();
        assertEquals(userId, n.getUserId());
        assertEquals("bill_reminder", n.getKind());
        assertEquals(key, n.getDedupeKey());
        assertEquals("D3", n.getStage());
        assertEquals(accountId, n.getAccountId());
        assertEquals(NOW, n.getCreatedAt());
        assertEquals(NOW, n.getUpdatedAt());
        assertNull(n.getReadAt());
    }

    @Test
    void sameStageNewTextUpdatesInPlaceAndKeepsTheReadState() {
        AppNotification row = existing("OVERDUE", "HDFC bill overdue by 1 day");
        row.setReadAt(NOW.minusSeconds(3600));
        when(repository.findByUserIdAndDedupeKeyIn(any(), any())).thenReturn(List.of(row));

        upsert("OVERDUE", "HDFC bill overdue by 2 days");

        AppNotification n = saved();
        assertEquals("HDFC bill overdue by 2 days", n.getTitle());
        assertEquals(NOW, n.getUpdatedAt());
        assertEquals(NOW.minusSeconds(3600), n.getReadAt(), "no stage change: it stays read");
        assertEquals(NOW.minusSeconds(86400), n.getCreatedAt());
    }

    @Test
    void stageChangeMarksTheRowUnreadAgain() {
        AppNotification row = existing("D3", "HDFC bill due in 3 days");
        row.setReadAt(NOW.minusSeconds(3600));
        when(repository.findByUserIdAndDedupeKeyIn(any(), any())).thenReturn(List.of(row));

        upsert("D2", "HDFC bill due in 2 days");

        AppNotification n = saved();
        assertEquals("D2", n.getStage());
        assertEquals("HDFC bill due in 2 days", n.getTitle());
        assertNull(n.getReadAt());
    }

    @Test
    void aClearedRowStaysClearedWhenTheStageMovesToAnotherDueStage() {
        AppNotification row = existing("D3", "HDFC bill due in 3 days");
        row.setClearedAt(NOW.minusSeconds(600));
        when(repository.findByUserIdAndDedupeKeyIn(any(), any())).thenReturn(List.of(row));

        upsert("D1", "HDFC bill due tomorrow");

        assertNotNull(saved().getClearedAt());
    }

    @Test
    void aClearedRowComesBackWhenTheBillTurnsOverdue() {
        AppNotification row = existing("D0", "HDFC bill due today");
        row.setClearedAt(NOW.minusSeconds(600));
        row.setReadAt(NOW.minusSeconds(900));
        when(repository.findByUserIdAndDedupeKeyIn(any(), any())).thenReturn(List.of(row));

        upsert("OVERDUE", "HDFC bill overdue by 1 day");

        AppNotification n = saved();
        assertNull(n.getClearedAt());
        assertNull(n.getReadAt());
        assertEquals("OVERDUE", n.getStage());
    }

    @Test
    void anUnchangedReminderWritesNothing() {
        when(repository.findByUserIdAndDedupeKeyIn(any(), any())).thenReturn(List.of(existing("D1", "HDFC bill due tomorrow")));

        upsert("D1", "HDFC bill due tomorrow");

        verify(repository, never()).saveAll(anyIterable());
    }

    @Test
    void upsertIsScopedToTheUser() {
        when(repository.findByUserIdAndDedupeKeyIn(any(), any())).thenReturn(List.of());

        upsert("D1", "x");

        verify(repository).findByUserIdAndDedupeKeyIn(userId, List.of(key));
    }

    @Test
    void resolveClearsTheRowOfThatUserAndKey() {
        AppNotification row = existing("D1", "HDFC bill due tomorrow");
        when(repository.findByUserIdAndDedupeKey(userId, key)).thenReturn(Optional.of(row));

        service.resolve(userId, key);

        ArgumentCaptor<AppNotification> saved = ArgumentCaptor.forClass(AppNotification.class);
        verify(repository).save(saved.capture());
        assertEquals(NOW, saved.getValue().getClearedAt());
    }

    @Test
    void resolveOfAnUnknownOrAlreadyClearedRowDoesNothing() {
        when(repository.findByUserIdAndDedupeKey(userId, key)).thenReturn(Optional.empty());
        service.resolve(userId, key);
        AppNotification cleared = existing("D1", "x");
        cleared.setClearedAt(NOW.minusSeconds(5));
        when(repository.findByUserIdAndDedupeKey(userId, "other")).thenReturn(Optional.of(cleared));
        service.resolve(userId, "other");

        verify(repository, never()).save(any());
    }

    @Test
    void resolveAllExceptClearsOnlyRowsThatAreNoLongerWanted() {
        AppNotification kept = existing("D1", "kept");
        AppNotification gone = existing("D1", "gone");
        gone.setDedupeKey("bill:gone");
        when(repository.findByUserIdAndKindInAndClearedAtIsNull(userId, List.of("bill_reminder", "emi_reminder")))
                .thenReturn(List.of(kept, gone));

        int resolved = service.resolveAllExcept(userId, List.of("bill_reminder", "emi_reminder"), Set.of(key));

        assertEquals(1, resolved);
        assertNull(kept.getClearedAt());
        assertEquals(NOW, gone.getClearedAt());
    }
}
