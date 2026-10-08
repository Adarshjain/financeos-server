package com.financeos.domain.notification.push;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * VAPID identity for Web Push. Both keys unset (the default) means push is simply off: the
 * settings page says so and the notification tick records markers without sending.
 * Generate a pair with {@link com.financeos.core.push.GenerateVapidKeys}.
 */
@Component
@ConfigurationProperties(prefix = "app.push")
@Getter
@Setter
public class PushProperties {
    /** Raw P-256 public point, base64url (65 bytes decoded). Also served to the browser. */
    private String vapidPublicKey = "";
    /** Raw P-256 private scalar, base64url (32 bytes decoded). */
    private String vapidPrivateKey = "";
    /** RFC 8292 contact for the push service, {@code mailto:} or {@code https:}. */
    private String subject = "mailto:admin@financeos.local";
    /** How long a push service keeps an undelivered message (seconds). */
    private int ttlSeconds = 86400;
    /** Per-request timeout for the push service call (seconds). */
    private int timeoutSeconds = 10;

    public boolean isConfigured() {
        return vapidPublicKey != null && !vapidPublicKey.isBlank()
                && vapidPrivateKey != null && !vapidPrivateKey.isBlank();
    }
}
