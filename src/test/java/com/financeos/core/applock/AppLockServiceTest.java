package com.financeos.core.applock;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.financeos.support.MutableClock;
import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.mock.web.MockHttpSession;

/** The lock state machine over session attributes, with a hand-moved clock. */
class AppLockServiceTest {

    private MutableClock clock;
    private AppLockService service;
    private MockHttpSession session;

    @BeforeEach
    void setUp() {
        clock = new MutableClock(Instant.parse("2026-10-23T10:00:00Z"));
        service = new AppLockService(new AppLockProperties(), clock);
        session = new MockHttpSession();
    }

    private boolean locked() {
        return service.isLocked(session);
    }

    @Test
    void unarmedSessionIsNotArmedAndGetsNoAttributes() {
        assertFalse(service.isArmed(session));
        assertTrue(session.getAttributeNames().asIterator().hasNext() == false);
    }

    @ParameterizedTest
    @ValueSource(ints = {60, 300, 900})
    void locksOnlyOnceTheLimitHasPassedWithoutAHeartbeat(int limit) {
        service.arm(session, "device-1", limit);
        clock.advance(Duration.ofSeconds(limit));
        assertFalse(locked(), "exactly at the limit is still open");
        clock.advance(Duration.ofMillis(1));
        assertTrue(locked());
    }

    @Test
    void immediatelyUsesTheBackstop() {
        service.arm(session, "device-1", 0);
        clock.advance(Duration.ofSeconds(30));
        assertFalse(locked());
        clock.advance(Duration.ofSeconds(1));
        assertTrue(locked());
    }

    @Test
    void heartbeatKeepsTheSessionOpen() {
        service.arm(session, "device-1", 60);
        for (int i = 0; i < 10; i++) {
            clock.advance(Duration.ofSeconds(50));
            service.heartbeat(session);
            assertFalse(locked());
        }
    }

    @Test
    void anOrdinaryRequestCheckDoesNotExtendTheTime() {
        service.arm(session, "device-1", 60);
        clock.advance(Duration.ofSeconds(40));
        assertFalse(locked());
        clock.advance(Duration.ofSeconds(30));
        assertTrue(locked(), "70s after the last heartbeat, despite a request at 40s");
    }

    @Test
    void lockIsStickyAndAHeartbeatAfterTheLimitDoesNotUndoIt() {
        service.arm(session, "device-1", 60);
        clock.advance(Duration.ofSeconds(61));
        assertTrue(locked());
        service.heartbeat(session);
        assertTrue(locked());
        assertEquals(Boolean.TRUE, session.getAttribute(AppLockSession.LOCKED));
    }

    @Test
    void unlockResetsEverything() {
        service.arm(session, "device-1", 60);
        service.recordFailure(session);
        service.recordFailure(session);
        clock.advance(Duration.ofSeconds(61));
        assertTrue(locked());

        service.unlock(session);

        assertFalse(locked());
        assertEquals(0, session.getAttribute(AppLockSession.FAILS));
        assertEquals(clock.millis(), session.getAttribute(AppLockSession.LAST_ACTIVE_MS));
    }

    @Test
    void lockNowIsIdempotentAndIgnoredWhenUnarmed() {
        service.lock(session);
        assertFalse(service.isArmed(session));
        assertNull(session.getAttribute(AppLockSession.LOCKED));

        service.arm(session, "device-1", 900);
        clock.advance(Duration.ofSeconds(2));
        service.lock(session);
        service.lock(session);
        assertTrue(locked());
    }

    @Test
    void lockIsIgnoredForTwoSecondsAfterAnUnlock() {
        service.arm(session, "device-1", 900);
        clock.advance(Duration.ofMillis(1999));
        service.lock(session);
        assertFalse(locked());
        clock.advance(Duration.ofMillis(1));
        service.lock(session);
        assertTrue(locked());
    }

    @Test
    void failuresCountUpToTheConfiguredLimit() {
        service.arm(session, "device-1", 60);
        for (int i = 1; i < 5; i++) {
            assertFalse(service.failureLimitReached(service.recordFailure(session)));
        }
        assertTrue(service.failureLimitReached(service.recordFailure(session)));
    }

    @Test
    void disarmClearsEveryAttribute() {
        service.arm(session, "device-1", 60);
        service.putPending(session, AppLockSession.PENDING_REG, "x");
        service.disarm(session);
        assertFalse(service.isArmed(session));
        assertFalse(session.getAttributeNames().asIterator().hasNext());
    }

    @Test
    void pendingCeremonyIsSingleUseAndExpiresAfterFiveMinutes() {
        service.putPending(session, AppLockSession.PENDING_ASSERT, "{\"a\":\"b|c\"}");
        assertEquals("{\"a\":\"b|c\"}", service.takePending(session, AppLockSession.PENDING_ASSERT));
        assertNull(service.takePending(session, AppLockSession.PENDING_ASSERT), "single use");

        service.putPending(session, AppLockSession.PENDING_ASSERT, "p");
        clock.advance(Duration.ofMinutes(5));
        assertEquals("p", service.takePending(session, AppLockSession.PENDING_ASSERT));

        service.putPending(session, AppLockSession.PENDING_ASSERT, "p");
        clock.advance(Duration.ofMinutes(5).plusMillis(1));
        assertNull(service.takePending(session, AppLockSession.PENDING_ASSERT));
        assertNull(session.getAttribute(AppLockSession.PENDING_ASSERT), "expired payload is removed too");
    }

    @Test
    void allowedLockAfterValuesAreFixed() {
        AppLockProperties props = new AppLockProperties();
        for (int ok : new int[] {0, 60, 300, 900}) {
            assertTrue(props.isAllowedLockAfter(ok));
        }
        assertFalse(props.isAllowedLockAfter(30));
        assertFalse(props.isAllowedLockAfter(null));
        assertEquals(60, props.getDefaultLockAfterSeconds());
    }
}
