package com.financeos.api.notification;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.financeos.api.notification.dto.AppNotificationResponse;
import com.financeos.api.notification.dto.MarkNotificationsReadRequest;
import com.financeos.core.security.UserContext;
import com.financeos.domain.notification.feed.AppNotification;
import com.financeos.domain.notification.feed.NotificationFeedService;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

class NotificationFeedControllerTest {

    private final NotificationFeedService service = mock(NotificationFeedService.class);
    private final NotificationFeedController controller = new NotificationFeedController(service);
    private final UUID userId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        UserContext.setCurrentUserId(userId);
    }

    @AfterEach
    void tearDown() {
        UserContext.clear();
    }

    @Test
    void listMapsRowsWithRefsAndTheReadFlag() {
        UUID jobId = UUID.randomUUID();
        AppNotification unread = new AppNotification();
        unread.setId(UUID.randomUUID());
        unread.setKind("job_failed");
        unread.setTitle("Couldn't import statement");
        unread.setBody("bad file");
        unread.setJobId(jobId);
        unread.setJobType("STATEMENT_INGEST");
        unread.setCreatedAt(Instant.parse("2026-10-20T05:00:00Z"));
        AppNotification read = new AppNotification();
        read.setId(UUID.randomUUID());
        read.setKind("gmail_sync");
        read.setTitle("Gmail sync");
        read.setCreatedAt(Instant.parse("2026-10-19T05:00:00Z"));
        read.setReadAt(Instant.parse("2026-10-19T06:00:00Z"));
        PageRequest pageable = PageRequest.of(0, 20);
        when(service.list(userId, pageable)).thenReturn(new PageImpl<>(List.of(unread, read), pageable, 2));

        List<AppNotificationResponse> body = controller.listFeed(pageable).getBody().getContent();

        assertEquals(2, body.size());
        assertEquals("job_failed", body.get(0).kind());
        assertEquals(jobId, body.get(0).refs().jobId());
        assertEquals("STATEMENT_INGEST", body.get(0).refs().jobType());
        assertEquals(Instant.parse("2026-10-20T05:00:00Z"), body.get(0).createdAt());
        assertFalse(body.get(0).read());
        assertTrue(body.get(1).read());
        assertEquals(null, body.get(1).refs().accountId());
    }

    @Test
    void summaryReturnsTheUnreadCount() {
        when(service.unreadCount(userId)).thenReturn(3L);
        assertEquals(3L, controller.feedSummary().getBody().unread());
    }

    @Test
    void readPassesBeforeOrNullAndReturns204() {
        Instant before = Instant.parse("2026-10-20T05:00:00Z");

        assertEquals(HttpStatus.NO_CONTENT, controller.markFeedRead(new MarkNotificationsReadRequest(before)).getStatusCode());
        assertEquals(HttpStatus.NO_CONTENT, controller.markFeedRead(null).getStatusCode());
        assertEquals(HttpStatus.NO_CONTENT, controller.markFeedRead(new MarkNotificationsReadRequest(null)).getStatusCode());

        verify(service).markRead(userId, before);
        verify(service, org.mockito.Mockito.times(2)).markRead(userId, null);
    }

    @Test
    void clearRestoreAndClearAll() {
        UUID id = UUID.randomUUID();
        when(service.clearAll(userId)).thenReturn(6);

        assertEquals(HttpStatus.NO_CONTENT, controller.clearFeedItem(id).getStatusCode());
        assertEquals(HttpStatus.NO_CONTENT, controller.restoreFeedItem(id).getStatusCode());
        assertEquals(6, controller.clearFeed().getBody().cleared());

        verify(service).clear(userId, id);
        verify(service).restore(userId, id);
    }

    @Test
    void everyEndpointNeedsAUser() {
        UserContext.clear();
        UUID id = UUID.randomUUID();
        for (Runnable call : List.<Runnable>of(() -> controller.listFeed(PageRequest.of(0, 20)), controller::feedSummary,
                () -> controller.markFeedRead(null), () -> controller.clearFeedItem(id), () -> controller.restoreFeedItem(id), controller::clearFeed)) {
            ResponseStatusException e = assertThrows(ResponseStatusException.class, call::run);
            assertEquals(HttpStatus.UNAUTHORIZED, e.getStatusCode());
        }
    }
}
