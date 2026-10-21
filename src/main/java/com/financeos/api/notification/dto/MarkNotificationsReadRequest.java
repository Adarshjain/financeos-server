package com.financeos.api.notification.dto;

import java.time.Instant;

/** {@code before}: mark unread rows created at or before this instant; omitted = now. */
public record MarkNotificationsReadRequest(Instant before) {
}
