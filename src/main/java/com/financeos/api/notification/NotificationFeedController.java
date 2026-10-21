package com.financeos.api.notification;

import com.financeos.api.notification.dto.AppNotificationResponse;
import com.financeos.api.notification.dto.AppNotificationSummaryResponse;
import com.financeos.api.notification.dto.ClearNotificationsResponse;
import com.financeos.api.notification.dto.MarkNotificationsReadRequest;
import com.financeos.core.security.UserContext;
import com.financeos.domain.notification.feed.NotificationFeedService;
import java.util.UUID;
import org.springdoc.core.annotations.ParameterObject;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/** The in-app notifications feed (bell + page). Separate from push settings, which never gate it. */
@RestController
@RequestMapping("/api/v1/notifications/feed")
public class NotificationFeedController {

    private final NotificationFeedService feedService;

    public NotificationFeedController(NotificationFeedService feedService) {
        this.feedService = feedService;
    }

    private UUID requireCurrentUserId() {
        UUID userId = UserContext.getCurrentUserId();
        if (userId == null) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "User is not authenticated");
        }
        return userId;
    }

    /** Newest first; cleared rows are not listed. */
    @GetMapping
    public ResponseEntity<Page<AppNotificationResponse>> listFeed(@ParameterObject @PageableDefault(size = 20) Pageable pageable) {
        UUID userId = requireCurrentUserId();
        return ResponseEntity.ok(feedService.list(userId, pageable).map(AppNotificationResponse::from));
    }

    @GetMapping("/summary")
    public ResponseEntity<AppNotificationSummaryResponse> feedSummary() {
        UUID userId = requireCurrentUserId();
        return ResponseEntity.ok(new AppNotificationSummaryResponse(feedService.unreadCount(userId)));
    }

    @PostMapping("/read")
    public ResponseEntity<Void> markFeedRead(@RequestBody(required = false) MarkNotificationsReadRequest request) {
        UUID userId = requireCurrentUserId();
        feedService.markRead(userId, request == null ? null : request.before());
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/{id}/clear")
    public ResponseEntity<Void> clearFeedItem(@PathVariable UUID id) {
        UUID userId = requireCurrentUserId();
        feedService.clear(userId, id);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/{id}/restore")
    public ResponseEntity<Void> restoreFeedItem(@PathVariable UUID id) {
        UUID userId = requireCurrentUserId();
        feedService.restore(userId, id);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/clear")
    public ResponseEntity<ClearNotificationsResponse> clearFeed() {
        UUID userId = requireCurrentUserId();
        return ResponseEntity.ok(new ClearNotificationsResponse(feedService.clearAll(userId)));
    }
}
