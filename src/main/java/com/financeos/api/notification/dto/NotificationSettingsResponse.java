package com.financeos.api.notification.dto;

import com.financeos.domain.notification.NotificationSettingsService;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.lang.Nullable;

public record NotificationSettingsResponse(
        boolean pushEnabled,
        int sendHour,
        List<Integer> reminderOffsets,
        Map<String, Boolean> kinds,
        List<PushDeviceResponse> devices,
        List<UUID> mutedAccountIds,
        List<UUID> mutedLoanIds,
        /** False when the server has no VAPID keys: the UI explains instead of offering to subscribe. */
        boolean pushConfigured
) {
    public record PushDeviceResponse(String endpoint, @Nullable String userAgent, @Nullable Instant addedAt) {
    }

    public static NotificationSettingsResponse from(NotificationSettingsService.View view) {
        Map<String, Boolean> kinds = new LinkedHashMap<>();
        view.kinds().forEach((k, v) -> kinds.put(k.name(), v));
        return new NotificationSettingsResponse(
                view.pushEnabled(),
                view.sendHour(),
                view.reminderOffsets(),
                kinds,
                view.subscriptions().stream()
                        .map(s -> new PushDeviceResponse(s.endpoint(), s.userAgent(), s.addedAt()))
                        .toList(),
                view.mutedAccountIds(),
                view.mutedLoanIds(),
                view.pushConfigured());
    }
}
