package com.financeos.api.notification.dto;

import java.util.List;
import java.util.Map;
import org.springframework.lang.Nullable;

/** Partial update: every field is optional; {@code kinds} merges into the stored map. */
public record UpdateNotificationSettingsRequest(
        @Nullable Boolean pushEnabled,
        @Nullable Integer sendHour,
        @Nullable List<Integer> reminderOffsets,
        @Nullable Map<String, Boolean> kinds) {
}
