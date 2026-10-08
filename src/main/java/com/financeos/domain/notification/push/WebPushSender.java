package com.financeos.domain.notification.push;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.financeos.core.push.WebPushCrypto;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.security.interfaces.ECPrivateKey;
import java.time.Duration;
import java.time.Instant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * Sends one encrypted Web Push message to one subscription over the JDK HTTP client.
 *
 * <p>Outcomes: {@code ok} (2xx), {@code gone} (404/410 — the browser dropped the subscription,
 * the caller prunes it) or a plain failure (anything else, including network errors), which is
 * logged and otherwise ignored: a push service outage must never break the bill evaluation.
 */
@Component
public class WebPushSender {

    private static final Logger log = LoggerFactory.getLogger(WebPushSender.class);
    private static final Duration VAPID_LIFETIME = Duration.ofHours(12);

    public record SendResult(int status, boolean ok, boolean gone) {
        static SendResult failed() {
            return new SendResult(0, false, false);
        }
    }

    private final PushProperties properties;
    private final ObjectMapper objectMapper;
    private final HttpClient httpClient;
    private volatile ECPrivateKey vapidPrivateKey;
    private volatile String vapidPublicKey;

    @Autowired
    public WebPushSender(PushProperties properties, ObjectMapper objectMapper) {
        this(properties, objectMapper, HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(properties.getTimeoutSeconds()))
                .followRedirects(HttpClient.Redirect.NEVER)
                .build());
    }

    WebPushSender(PushProperties properties, ObjectMapper objectMapper, HttpClient httpClient) {
        this.properties = properties;
        this.objectMapper = objectMapper;
        this.httpClient = httpClient;
    }

    public boolean isConfigured() {
        return properties.isConfigured();
    }

    /** The base64url public key the browser passes as {@code applicationServerKey}; empty when push is off. */
    public String publicKey() {
        return properties.isConfigured() ? properties.getVapidPublicKey().trim() : "";
    }

    public SendResult send(PushSubscription subscription, PushMessage message) {
        if (!isConfigured()) {
            return SendResult.failed();
        }
        try {
            byte[] payload = objectMapper.writeValueAsBytes(message);
            byte[] body = WebPushCrypto.encrypt(payload,
                    WebPushCrypto.base64UrlDecode(subscription.p256dh()),
                    WebPushCrypto.base64UrlDecode(subscription.auth()));
            URI endpoint = URI.create(subscription.endpoint());
            String token = WebPushCrypto.vapidToken(origin(endpoint), properties.getSubject(),
                    Instant.now().plus(VAPID_LIFETIME), privateKey());

            HttpRequest request = HttpRequest.newBuilder(endpoint)
                    .timeout(Duration.ofSeconds(properties.getTimeoutSeconds()))
                    .header("TTL", String.valueOf(properties.getTtlSeconds()))
                    .header("Urgency", "normal")
                    .header("Content-Type", "application/octet-stream")
                    .header("Content-Encoding", "aes128gcm")
                    .header("Authorization", "vapid t=" + token + ", k=" + publicKey())
                    .POST(HttpRequest.BodyPublishers.ofByteArray(body))
                    .build();
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            int status = response.statusCode();
            boolean ok = status >= 200 && status < 300;
            boolean gone = status == 404 || status == 410;
            if (!ok) {
                log.warn("Web Push rejected: status={}, host={}, body={}", status, endpoint.getHost(), truncate(response.body()));
            }
            return new SendResult(status, ok, gone);
        } catch (IOException | RuntimeException e) {
            log.warn("Web Push failed: {}", e.getMessage());
            return SendResult.failed();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return SendResult.failed();
        }
    }

    /** RFC 8292 §2: the {@code aud} claim is the push service origin (scheme + host [+ port]). */
    static String origin(URI endpoint) {
        StringBuilder sb = new StringBuilder(endpoint.getScheme()).append("://").append(endpoint.getHost());
        if (endpoint.getPort() != -1) {
            sb.append(':').append(endpoint.getPort());
        }
        return sb.toString();
    }

    private ECPrivateKey privateKey() {
        ECPrivateKey key = vapidPrivateKey;
        if (key == null || !properties.getVapidPublicKey().trim().equals(vapidPublicKey)) {
            key = WebPushCrypto.decodePrivateKey(WebPushCrypto.base64UrlDecode(properties.getVapidPrivateKey()));
            vapidPrivateKey = key;
            vapidPublicKey = properties.getVapidPublicKey().trim();
        }
        return key;
    }

    private static String truncate(String body) {
        if (body == null) {
            return "";
        }
        return body.length() > 200 ? body.substring(0, 200) + "…" : body;
    }
}
