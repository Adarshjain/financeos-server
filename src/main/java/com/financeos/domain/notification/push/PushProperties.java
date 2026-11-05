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

    /** The app's public client origins, as configured elsewhere (comma separated lists), used when {@link #subject} is unusable. */
    @org.springframework.beans.factory.annotation.Value("${app.cors.allowed-origins:}")
    private String corsOrigins = "";
    @org.springframework.beans.factory.annotation.Value("${app.app-lock.origins:}")
    private String appLockOrigins = "";
    @org.springframework.beans.factory.annotation.Value("${app.ui-path:}")
    private String uiPath = "";

    /**
     * The VAPID subject to send: {@link #subject} when it is a valid mailto:/https: URL on a real host, else the first
     * public https client origin (CORS origins, app-lock origins, UI path), else {@link #subject} as it is.
     */
    public String effectiveSubject() {
        return PushSubject.resolve(subject, java.util.List.of(corsOrigins, appLockOrigins, uiPath));
    }

    @jakarta.annotation.PostConstruct
    void logSubject() {
        String used = effectiveSubject();
        if (PushSubject.isValid(used)) {
            org.slf4j.LoggerFactory.getLogger(PushProperties.class).info("Web Push VAPID subject: {}{}", used,
                    used.equals(subject) ? "" : " (PUSH_VAPID_SUBJECT is unset or not accepted by Apple; using the client origin)");
        } else {
            org.slf4j.LoggerFactory.getLogger(PushProperties.class).warn(
                    "Web Push VAPID subject {} is a local/placeholder address and no public https client origin is configured; "
                            + "Apple will answer 403 BadJwtToken. Set PUSH_VAPID_SUBJECT to a mailto: or https: URL.", used);
        }
    }

    public boolean isConfigured() {
        return vapidPublicKey != null && !vapidPublicKey.isBlank()
                && vapidPrivateKey != null && !vapidPrivateKey.isBlank();
    }
}
