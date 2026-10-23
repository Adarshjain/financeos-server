package com.financeos.core.applock;

import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;

/**
 * The app-lock attributes kept in the server session. Plain types only (Spring Session JDBC
 * serialises them). A session is "armed" when {@link #DEVICE_ID} is set; every other attribute is
 * meaningless on an unarmed session.
 */
public final class AppLockSession {

    /** Enrolled device id (String). Set = the session is armed. */
    public static final String DEVICE_ID = "APP_LOCK_DEVICE_ID";
    /** Seconds away before locking: 0, 60, 300 or 900 (Integer). */
    public static final String AFTER_S = "APP_LOCK_AFTER_S";
    /** Last heartbeat, epoch millis (Long). */
    public static final String LAST_ACTIVE_MS = "APP_LOCK_LAST_ACTIVE_MS";
    /** Sticky once set; only an unlock or a fresh enrolment clears it (Boolean). */
    public static final String LOCKED = "APP_LOCK_LOCKED";
    /** When the last unlock happened; stops a stale request re-locking a fresh unlock (Long). */
    public static final String UNLOCKED_AT_MS = "APP_LOCK_UNLOCKED_AT_MS";
    /** Failed unlocks since the last success (Integer). */
    public static final String FAILS = "APP_LOCK_FAILS";
    /** Pending registration ceremony: created-ms, then payload (String, single use). */
    public static final String PENDING_REG = "APP_LOCK_PENDING_REG";
    /** Pending unlock ceremony: created-ms, then payload (String, single use). */
    public static final String PENDING_ASSERT = "APP_LOCK_PENDING_ASSERT";

    /** Cookie naming this browser's enrolled device (an app_lock_devices id). Set by the client, forwarded on sign-in. */
    public static final String DEVICE_COOKIE = "FINANCEOS_DEVICE";

    private AppLockSession() {
    }

    /** The armed device id, or null. The only read an unarmed session ever costs. */
    public static String deviceId(HttpSession session) {
        return session.getAttribute(DEVICE_ID) instanceof String s ? s : null;
    }

    static Long getLong(HttpSession session, String name) {
        return session.getAttribute(name) instanceof Number n ? n.longValue() : null;
    }

    static int getInt(HttpSession session, String name, int fallback) {
        return session.getAttribute(name) instanceof Number n ? n.intValue() : fallback;
    }

    static boolean getBool(HttpSession session, String name) {
        return Boolean.TRUE.equals(session.getAttribute(name));
    }

    static void clearAll(HttpSession session) {
        for (String name : new String[] {DEVICE_ID, AFTER_S, LAST_ACTIVE_MS, LOCKED, UNLOCKED_AT_MS, FAILS,
                PENDING_REG, PENDING_ASSERT}) {
            session.removeAttribute(name);
        }
    }

    /** The device cookie's value, or null. */
    public static String deviceCookie(HttpServletRequest request) {
        Cookie[] cookies = request.getCookies();
        if (cookies == null) {
            return null;
        }
        for (Cookie c : cookies) {
            if (DEVICE_COOKIE.equals(c.getName())) {
                return c.getValue();
            }
        }
        return null;
    }
}
