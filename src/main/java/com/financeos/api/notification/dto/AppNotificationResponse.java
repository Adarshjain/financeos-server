package com.financeos.api.notification.dto;

import com.financeos.domain.notification.feed.AppNotification;
import java.time.Instant;
import java.util.UUID;
import org.springframework.lang.Nullable;

/** One row of the in-app notifications feed. */
public record AppNotificationResponse(UUID id, String kind, String title, @Nullable String body, Refs refs, Instant createdAt, boolean read) {

    public record Refs(@Nullable UUID jobId, @Nullable String jobType, @Nullable UUID accountId, @Nullable UUID connectionId) {
    }

    public static AppNotificationResponse from(AppNotification n) {
        return new AppNotificationResponse(n.getId(), n.getKind(), n.getTitle(), n.getBody(),
                new Refs(n.getJobId(), n.getJobType(), n.getAccountId(), n.getConnectionId()),
                n.getCreatedAt(), n.getReadAt() != null);
    }
}
