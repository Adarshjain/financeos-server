package com.financeos.domain.notification.push;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.time.Instant;

/**
 * One browser's push subscription (the {@code PushSubscription.toJSON()} fields plus a little
 * bookkeeping). Stored as a JSON array on {@code user_notification_settings.push_subscriptions}.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record PushSubscription(String endpoint, String p256dh, String auth, String userAgent, Instant addedAt) {
}
