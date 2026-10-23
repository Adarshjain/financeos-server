package com.financeos.core.applock;

import jakarta.servlet.http.HttpSession;
import java.time.Clock;
import java.time.Duration;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Service;

/**
 * Lock state machine over the session attributes (see {@link AppLockSession}).
 *
 * <p>Ordinary API requests never count as activity: only {@link #heartbeat} moves the last-active
 * time. On every request of an armed session the filter asks {@link #isLocked}: locked when the
 * sticky flag is set or the last heartbeat is older than the limit ("Immediately" uses the backstop,
 * since its real lock comes from the client leaving the app). A passed limit sets the sticky flag.
 *
 * <p>Accepted tiny race: two requests in flight across an unlock may re-lock the session (the
 * session is a snapshot per request); the user just unlocks again.
 */
@Service
public class AppLockService {

    /** How long a started registration/unlock ceremony stays valid. */
    static final Duration PENDING_TTL = Duration.ofMinutes(5);

    private final AppLockProperties props;
    private final Clock clock;

    @Autowired
    public AppLockService(AppLockProperties props, ObjectProvider<Clock> clock) {
        this(props, clock.getIfAvailable(Clock::systemUTC));
    }

    AppLockService(AppLockProperties props, Clock clock) {
        this.props = props;
        this.clock = clock;
    }

    public Clock clock() {
        return clock;
    }

    public long nowMs() {
        return clock.millis();
    }

    public AppLockProperties props() {
        return props;
    }

    public boolean isArmed(HttpSession session) {
        return AppLockSession.deviceId(session) != null;
    }

    /** True when an armed session must be refused now; sets the sticky lock once the limit has passed. */
    public boolean isLocked(HttpSession session) {
        if (AppLockSession.getBool(session, AppLockSession.LOCKED)) {
            return true;
        }
        Long lastActive = AppLockSession.getLong(session, AppLockSession.LAST_ACTIVE_MS);
        int after = AppLockSession.getInt(session, AppLockSession.AFTER_S, props.getDefaultLockAfterSeconds());
        long limitMs = (after == 0 ? props.getImmediateBackstopSeconds() : after) * 1000L;
        if (lastActive == null || nowMs() - lastActive > limitMs) {
            session.setAttribute(AppLockSession.LOCKED, Boolean.TRUE);
            return true;
        }
        return false;
    }

    /** Arms the session for a device, unlocked, with a clean failure count. */
    public void arm(HttpSession session, String deviceId, int lockAfterSeconds) {
        session.setAttribute(AppLockSession.DEVICE_ID, deviceId);
        session.setAttribute(AppLockSession.AFTER_S, lockAfterSeconds);
        markUnlocked(session);
    }

    public void disarm(HttpSession session) {
        AppLockSession.clearAll(session);
    }

    public void setLockAfter(HttpSession session, int lockAfterSeconds) {
        session.setAttribute(AppLockSession.AFTER_S, lockAfterSeconds);
    }

    public void unlock(HttpSession session) {
        markUnlocked(session);
    }

    public void heartbeat(HttpSession session) {
        session.setAttribute(AppLockSession.LAST_ACTIVE_MS, nowMs());
    }

    /**
     * Idempotent; a no-op on an unarmed session, and ignored right after an unlock (the lock page's own
     * page-leave beacon fires just after a successful unlock and must not re-lock it).
     */
    public void lock(HttpSession session) {
        Long unlockedAt = AppLockSession.getLong(session, AppLockSession.UNLOCKED_AT_MS);
        if (unlockedAt != null && nowMs() - unlockedAt < props.getLockIgnoreAfterUnlockSeconds() * 1000L) {
            return;
        }
        if (isArmed(session) && !AppLockSession.getBool(session, AppLockSession.LOCKED)) {
            session.setAttribute(AppLockSession.LOCKED, Boolean.TRUE);
        }
    }

    /** Counts a failed unlock and returns the new total. */
    public int recordFailure(HttpSession session) {
        int fails = AppLockSession.getInt(session, AppLockSession.FAILS, 0) + 1;
        session.setAttribute(AppLockSession.FAILS, fails);
        return fails;
    }

    public boolean failureLimitReached(int fails) {
        return fails >= props.getMaxUnlockFailures();
    }

    /** Stores a ceremony payload with its creation time, replacing any earlier one. */
    public void putPending(HttpSession session, String attribute, String payload) {
        session.setAttribute(attribute, nowMs() + "|" + payload);
    }

    /** Takes (and removes) a ceremony payload; null when absent, malformed or older than 5 minutes. */
    @Nullable
    public String takePending(HttpSession session, String attribute) {
        Object raw = session.getAttribute(attribute);
        session.removeAttribute(attribute);
        if (!(raw instanceof String s)) {
            return null;
        }
        int bar = s.indexOf('|');
        if (bar < 1) {
            return null;
        }
        try {
            long created = Long.parseLong(s.substring(0, bar));
            if (nowMs() - created > PENDING_TTL.toMillis()) {
                return null;
            }
        } catch (NumberFormatException e) {
            return null;
        }
        return s.substring(bar + 1);
    }

    private void markUnlocked(HttpSession session) {
        long now = nowMs();
        session.setAttribute(AppLockSession.LAST_ACTIVE_MS, now);
        session.setAttribute(AppLockSession.LOCKED, Boolean.FALSE);
        session.setAttribute(AppLockSession.UNLOCKED_AT_MS, now);
        session.setAttribute(AppLockSession.FAILS, 0);
    }
}
