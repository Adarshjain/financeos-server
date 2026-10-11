package com.financeos.core.oauth;

import org.springframework.lang.Nullable;
import org.springframework.stereotype.Component;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.util.Base64;
import java.util.UUID;

/**
 * Issues and checks the OAuth {@code state} parameter, so a callback is only accepted for a flow this
 * server started — and, for Gmail connect, only for the user who started it.
 *
 * <p>A state is {@code flowId.expiresAt.nonce.signature}: an HMAC-SHA256 over the purpose, flow id,
 * expiry, nonce and bound user, keyed with a random secret held in memory for this server's lifetime.
 * Nothing is stored per flow. The flow id stays the first dot-separated part, which the callback logs
 * already use for correlation.
 *
 * <ul>
 *   <li>Gmail connect binds the state to the signed-in user: a link carrying another user's state (an
 *   attacker starting the flow for their own mailbox and sending the callback URL to a victim) fails
 *   the check, so the attacker's mailbox is never attached to the victim's account.</li>
 *   <li>Google sign-in has no user yet, so the state is unbound here; the client ties it to the browser
 *   that started the flow with a short-lived cookie and compares the two before calling the API.</li>
 * </ul>
 *
 * A restart drops the key, so a flow in flight across a restart fails and the user starts again.
 */
@Component
public class OAuthStateService {

    /** What a state was issued for; a state never passes for the other purpose. */
    public enum Purpose { GOOGLE_SSO, GMAIL_CONNECT }

    static final Duration TTL = Duration.ofMinutes(10);
    private static final String ALGORITHM = "HmacSHA256";
    private static final Base64.Encoder B64 = Base64.getUrlEncoder().withoutPadding();

    private final byte[] key;
    private final Clock clock;
    private final SecureRandom random = new SecureRandom();

    public OAuthStateService() {
        this(Clock.systemUTC());
    }

    OAuthStateService(Clock clock) {
        this.clock = clock;
        this.key = new byte[32];
        random.nextBytes(key);
    }

    /** A new state for {@code purpose}, bound to {@code userId} when one is given. */
    public String issue(Purpose purpose, String flowId, @Nullable UUID userId) {
        long expiresAt = clock.instant().plus(TTL).getEpochSecond();
        byte[] nonceBytes = new byte[16];
        random.nextBytes(nonceBytes);
        String nonce = B64.encodeToString(nonceBytes);
        return flowId + "." + expiresAt + "." + nonce + "." + sign(purpose, flowId, expiresAt, nonce, userId);
    }

    /**
     * Whether {@code state} was issued by this server for {@code purpose} and {@code userId} (null for
     * an unbound state) and has not expired.
     */
    public boolean isValid(Purpose purpose, @Nullable String state, @Nullable UUID userId) {
        if (state == null) {
            return false;
        }
        String[] parts = state.split("\\.", -1);
        if (parts.length != 4 || parts[0].isEmpty() || parts[2].isEmpty()) {
            return false;
        }
        long expiresAt;
        try {
            expiresAt = Long.parseLong(parts[1]);
        } catch (NumberFormatException e) {
            return false;
        }
        if (clock.instant().getEpochSecond() > expiresAt) {
            return false;
        }
        String expected = sign(purpose, parts[0], expiresAt, parts[2], userId);
        return MessageDigest.isEqual(expected.getBytes(StandardCharsets.US_ASCII),
                parts[3].getBytes(StandardCharsets.US_ASCII));
    }

    private String sign(Purpose purpose, String flowId, long expiresAt, String nonce, @Nullable UUID userId) {
        String payload = purpose.name() + "|" + flowId + "|" + expiresAt + "|" + nonce + "|"
                + (userId != null ? userId : "-");
        try {
            Mac mac = Mac.getInstance(ALGORITHM);
            mac.init(new SecretKeySpec(key, ALGORITHM));
            return B64.encodeToString(mac.doFinal(payload.getBytes(StandardCharsets.UTF_8)));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("HmacSHA256 unavailable", e);
        }
    }
}
