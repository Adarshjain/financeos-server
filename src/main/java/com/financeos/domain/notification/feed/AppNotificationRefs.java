package com.financeos.domain.notification.feed;

import java.util.UUID;

/** What a feed row points at; every part is optional. */
public record AppNotificationRefs(UUID jobId, String jobType, UUID accountId, UUID connectionId) {

    public static final AppNotificationRefs NONE = new AppNotificationRefs(null, null, null, null);

    public static AppNotificationRefs ofJob(UUID jobId, String jobType) {
        return new AppNotificationRefs(jobId, jobType, null, null);
    }

    public static AppNotificationRefs ofAccount(UUID accountId) {
        return new AppNotificationRefs(null, null, accountId, null);
    }

    public static AppNotificationRefs ofConnection(UUID connectionId) {
        return new AppNotificationRefs(null, null, null, connectionId);
    }
}
