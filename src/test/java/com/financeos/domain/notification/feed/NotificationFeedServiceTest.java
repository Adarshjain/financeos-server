package com.financeos.domain.notification.feed;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.financeos.core.exception.ResourceNotFoundException;
import com.financeos.core.time.AppTime;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.transaction.PlatformTransactionManager;

class NotificationFeedServiceTest {

    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");
    private static final Instant NOW = LocalDateTime.of(2026, 10, 20, 10, 0).atZone(IST).toInstant();

    private final AppNotificationRepository repository = mock(AppNotificationRepository.class);
    private final NotificationFeedService service = new NotificationFeedService(repository, mock(PlatformTransactionManager.class));
    private final UUID userId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        AppTime.useClock(Clock.fixed(NOW, IST));
    }

    @AfterEach
    void tearDown() {
        AppTime.reset();
    }

    private AppNotification row(UUID id, UUID owner) {
        AppNotification n = new AppNotification();
        n.setId(id);
        n.setUserId(owner);
        return n;
    }

    // ---------------------------------------------------------------- record

    @Test
    void recordInsertsARowWithItsRefs() {
        UUID jobId = UUID.randomUUID();
        service.record(userId, AppNotificationKind.JOB_FAILED, "Title", "Body", AppNotificationRefs.ofJob(jobId, "GMAIL_SYNC"), "job:" + jobId);

        ArgumentCaptor<AppNotification> saved = ArgumentCaptor.forClass(AppNotification.class);
        verify(repository).saveAndFlush(saved.capture());
        AppNotification n = saved.getValue();
        assertEquals(userId, n.getUserId());
        assertEquals("job_failed", n.getKind());
        assertEquals("Title", n.getTitle());
        assertEquals("Body", n.getBody());
        assertEquals(jobId, n.getJobId());
        assertEquals("GMAIL_SYNC", n.getJobType());
        assertNull(n.getAccountId());
        assertEquals("job:" + jobId, n.getDedupeKey());
        assertEquals(NOW, n.getCreatedAt());
        assertNull(n.getReadAt());
        assertNull(n.getClearedAt());
    }

    @Test
    void duplicateDedupeKeyIsANoOp() {
        when(repository.existsByUserIdAndDedupeKey(userId, "k")).thenReturn(true);

        service.record(userId, "job_failed", "T", null, AppNotificationRefs.NONE, "k");

        verify(repository, never()).saveAndFlush(any());
    }

    @Test
    void failureIsSwallowedAndLongTextClipped() {
        when(repository.saveAndFlush(any())).thenThrow(new IllegalStateException("db down"));

        service.record(userId, "job_failed", "T".repeat(300), "B".repeat(600), null, "k");

        ArgumentCaptor<AppNotification> saved = ArgumentCaptor.forClass(AppNotification.class);
        verify(repository).saveAndFlush(saved.capture());
        assertEquals(200, saved.getValue().getTitle().length());
        assertEquals(500, saved.getValue().getBody().length());
    }

    // ---------------------------------------------------------------- list / unread

    @Test
    void listIsNewestFirstScopedToTheUserAndKeepsPaging() {
        Page<AppNotification> page = new PageImpl<>(List.of(row(UUID.randomUUID(), userId)));
        when(repository.findByUserIdAndClearedAtIsNull(any(), any())).thenReturn(page);

        Page<AppNotification> result = service.list(userId, PageRequest.of(2, 15, Sort.by("title")));

        assertEquals(page, result);
        ArgumentCaptor<Pageable> pageable = ArgumentCaptor.forClass(Pageable.class);
        verify(repository).findByUserIdAndClearedAtIsNull(any(), pageable.capture());
        assertEquals(2, pageable.getValue().getPageNumber());
        assertEquals(15, pageable.getValue().getPageSize());
        assertEquals(Sort.by(Sort.Direction.DESC, "createdAt").and(Sort.by(Sort.Direction.DESC, "id")), pageable.getValue().getSort());
        verify(repository).findByUserIdAndClearedAtIsNull(userId, pageable.getValue());
    }

    @Test
    void unreadCountIsTheRepositoryCountForTheUser() {
        when(repository.countByUserIdAndClearedAtIsNullAndReadAtIsNull(userId)).thenReturn(4L);
        assertEquals(4L, service.unreadCount(userId));
    }

    // ---------------------------------------------------------------- read / clear / restore

    @Test
    void markReadUsesTheGivenBeforeAndDefaultsToNow() {
        Instant before = NOW.minusSeconds(60);
        when(repository.markRead(userId, before, NOW)).thenReturn(3);
        when(repository.markRead(userId, NOW, NOW)).thenReturn(5);

        assertEquals(3, service.markRead(userId, before));
        assertEquals(5, service.markRead(userId, null));
    }

    @Test
    void clearAndRestoreSetAndNullTheClearedAt() {
        UUID id = UUID.randomUUID();
        AppNotification n = row(id, userId);
        when(repository.findByIdAndUserId(id, userId)).thenReturn(Optional.of(n));

        service.clear(userId, id);
        assertEquals(NOW, n.getClearedAt());

        service.restore(userId, id);
        assertNull(n.getClearedAt());
        verify(repository, org.mockito.Mockito.times(2)).save(n);
    }

    @Test
    void clearAllReturnsTheCount() {
        when(repository.clearAll(userId, NOW)).thenReturn(7);
        assertEquals(7, service.clearAll(userId));
    }

    @Test
    void anotherUsersOrUnknownIdIs404() {
        UUID id = UUID.randomUUID();
        when(repository.findByIdAndUserId(id, userId)).thenReturn(Optional.empty());

        assertThrows(ResourceNotFoundException.class, () -> service.clear(userId, id));
        assertThrows(ResourceNotFoundException.class, () -> service.restore(userId, id));
        verify(repository, never()).save(any());
    }

    // ---------------------------------------------------------------- purge

    @Test
    void purgeDropsRowsOlderThan90DaysAndRowsClearedMoreThanADayAgo() {
        when(repository.purge(any(), any())).thenReturn(2);

        assertEquals(2, service.purge());

        verify(repository).purge(NOW.minus(Duration.ofDays(90)), NOW.minus(Duration.ofDays(1)));
        assertTrue(NOW.minus(Duration.ofDays(90)).isBefore(NOW.minus(Duration.ofDays(1))));
    }
}
