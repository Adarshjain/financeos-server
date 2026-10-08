package com.financeos.domain.notification;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import org.springframework.lang.Nullable;

/**
 * One user's notification preferences as every producer needs them: whether a push can reach
 * them at all, their send hour, reminder offsets and per-kind switches. {@code settings} is
 * null when the user has no row yet (nothing can be sent; markers still advance).
 */
public record NotificationPrefs(@Nullable UserNotificationSettings settings, boolean canSend, int sendHour,
                                List<Integer> offsets, Map<NotificationKind, Boolean> kinds) {

    public boolean allows(@Nullable NotificationKind kind) {
        return kind != null && Boolean.TRUE.equals(kinds.get(kind));
    }

    /** Scheduled (non-event) sends wait for the user's send hour in the business zone. */
    public boolean pastSendHour(LocalDateTime now) {
        return now.getHour() >= sendHour;
    }

    /** A push goes out only when it can be delivered and the user wants this kind. */
    public boolean deliverable(@Nullable NotificationKind kind) {
        return canSend && allows(kind);
    }
}
