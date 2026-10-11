package com.financeos.core.oauth;

import com.financeos.core.oauth.OAuthStateService.Purpose;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A state passes only for the purpose and user it was issued for, while fresh, and only if this server
 * (this key) signed it; every part of it is covered by the signature.
 */
class OAuthStateServiceTest {

    private static final Instant NOW = Instant.parse("2026-10-11T06:00:00Z");
    private final MutableClock clock = new MutableClock(NOW);
    private final OAuthStateService service = new OAuthStateService(clock);
    private final UUID alice = UUID.randomUUID();
    private final UUID bob = UUID.randomUUID();

    @Test
    void aStateBoundToAUserPassesForThatUser() {
        String state = service.issue(Purpose.GMAIL_CONNECT, "abcd1234", alice);

        assertTrue(service.isValid(Purpose.GMAIL_CONNECT, state, alice));
    }

    @Test
    void anUnboundStatePassesWithoutAUser() {
        String state = service.issue(Purpose.GOOGLE_SSO, "abcd1234", null);

        assertTrue(service.isValid(Purpose.GOOGLE_SSO, state, null));
    }

    @Test
    void aStateNeverPassesForAnotherUser() {
        String state = service.issue(Purpose.GMAIL_CONNECT, "abcd1234", alice);

        assertFalse(service.isValid(Purpose.GMAIL_CONNECT, state, bob));
    }

    @Test
    void boundAndUnboundStatesDoNotStandInForEachOther() {
        String bound = service.issue(Purpose.GMAIL_CONNECT, "abcd1234", alice);
        String unbound = service.issue(Purpose.GMAIL_CONNECT, "abcd1234", null);

        assertFalse(service.isValid(Purpose.GMAIL_CONNECT, bound, null));
        assertFalse(service.isValid(Purpose.GMAIL_CONNECT, unbound, alice));
    }

    @Test
    void aStateNeverPassesForTheOtherPurpose() {
        String sso = service.issue(Purpose.GOOGLE_SSO, "abcd1234", null);
        String gmail = service.issue(Purpose.GMAIL_CONNECT, "abcd1234", null);

        assertFalse(service.isValid(Purpose.GMAIL_CONNECT, sso, null));
        assertFalse(service.isValid(Purpose.GOOGLE_SSO, gmail, null));
    }

    @Test
    void aStatePassesUntilItsExpiryAndNotAfter() {
        String state = service.issue(Purpose.GOOGLE_SSO, "abcd1234", null);

        clock.set(NOW.plus(OAuthStateService.TTL));
        assertTrue(service.isValid(Purpose.GOOGLE_SSO, state, null), "valid through the last second");

        clock.set(NOW.plus(OAuthStateService.TTL).plusSeconds(1));
        assertFalse(service.isValid(Purpose.GOOGLE_SSO, state, null), "expired");
    }

    @Test
    void anotherServersStateIsRejected() {
        // A restart (or another instance) holds a different key.
        String state = new OAuthStateService(clock).issue(Purpose.GOOGLE_SSO, "abcd1234", null);

        assertFalse(service.isValid(Purpose.GOOGLE_SSO, state, null));
    }

    @Test
    void changingAnyPartBreaksTheSignature() {
        String state = service.issue(Purpose.GOOGLE_SSO, "abcd1234", null);
        String[] parts = state.split("\\.");

        assertFalse(service.isValid(Purpose.GOOGLE_SSO, join("zzzz9999", parts[1], parts[2], parts[3]), null), "flow id");
        assertFalse(service.isValid(Purpose.GOOGLE_SSO,
                join(parts[0], String.valueOf(Long.parseLong(parts[1]) + 3600), parts[2], parts[3]), null), "expiry pushed out");
        assertFalse(service.isValid(Purpose.GOOGLE_SSO, join(parts[0], parts[1], parts[2] + "x", parts[3]), null), "nonce");
        assertFalse(service.isValid(Purpose.GOOGLE_SSO, join(parts[0], parts[1], parts[2], parts[3] + "x"), null), "signature");
    }

    @Test
    void malformedStatesAreRejected() {
        assertFalse(service.isValid(Purpose.GOOGLE_SSO, null, null));
        assertFalse(service.isValid(Purpose.GOOGLE_SSO, "", null));
        assertFalse(service.isValid(Purpose.GOOGLE_SSO, "abcd1234." + UUID.randomUUID(), null), "the old flowId.uuid shape");
        assertFalse(service.isValid(Purpose.GMAIL_CONNECT, alice.toString(), alice), "the old bare user id");
        assertFalse(service.isValid(Purpose.GOOGLE_SSO, "a.notanumber.n.s", null));
        assertFalse(service.isValid(Purpose.GOOGLE_SSO, ".1.n.s", null), "empty flow id");
        assertFalse(service.isValid(Purpose.GOOGLE_SSO, "a.1..s", null), "empty nonce");
        assertFalse(service.isValid(Purpose.GOOGLE_SSO, "a.1.n.s.extra", null), "too many parts");
    }

    @Test
    void eachStateIsUniqueUrlSafeAndKeepsTheFlowIdFirst() {
        String first = service.issue(Purpose.GOOGLE_SSO, "abcd1234", null);
        String second = service.issue(Purpose.GOOGLE_SSO, "abcd1234", null);

        assertNotEquals(first, second);
        assertTrue(first.matches("[A-Za-z0-9._-]+"), first);
        assertEquals("abcd1234", first.substring(0, first.indexOf('.')));
    }

    private static String join(String... parts) {
        return String.join(".", parts);
    }

    private static final class MutableClock extends Clock {
        private Instant now;

        MutableClock(Instant now) {
            this.now = now;
        }

        void set(Instant now) {
            this.now = now;
        }

        @Override
        public Instant instant() {
            return now;
        }

        @Override
        public java.time.ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(java.time.ZoneId zone) {
            return this;
        }
    }
}
