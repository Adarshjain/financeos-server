package com.financeos.sms.device;

import com.financeos.core.exception.TooManyAttemptsException;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * In-memory sliding window over device sign-in attempts (reserved before verifying, cleared on success), keyed per account (lower-cased email, or Google subject) so
 * one stranger's failures never lock anybody else out. A success clears the key. Tokens that cannot be read at all
 * share one small bucket. The map is bounded: expired keys are dropped first and, past {@link #MAX_KEYS}, the oldest
 * key goes. Single instance, resets on restart (same trade-off as the other limiters).
 */
@Component
public class SmsLoginLimiter {

    static final int MAX_FAILURES = 8;
    static final int MAX_UNPARSED_FAILURES = 20;
    static final Duration WINDOW = Duration.ofMinutes(15);
    static final int MAX_KEYS = 10_000;
    static final String UNPARSED_KEY = "google:unparsed";
    private static final java.util.regex.Pattern SUBJECT = java.util.regex.Pattern.compile("[A-Za-z0-9._-]{1,64}");

    private final Map<String, Deque<Instant>> failures = new LinkedHashMap<>();

    public static String passwordKey(String email) {
        return "pw:" + (email == null ? "" : email.trim().toLowerCase(java.util.Locale.ROOT));
    }

    /** The subject comes from an unsigned payload: only a plain 1-64 character id may become a key. */
    public static String googleKey(String subject) {
        return subject != null && SUBJECT.matcher(subject).matches() ? "g:" + subject : UNPARSED_KEY;
    }

    /**
     * Counts one attempt for the key and returns true, or returns false (counting nothing) when the key has already used
     * up its attempts inside the window. Reserve before verifying so parallel requests cannot overshoot the cap; a
     * success calls {@link #clear}, an attempt that must not count calls {@link #release}.
     */
    public synchronized boolean tryAcquire(String key, Instant now) {
        Deque<Instant> window = failures.remove(key);
        if (window == null) {
            window = new ArrayDeque<>();
            makeRoom(now);
        }
        trim(window, now);
        int max = UNPARSED_KEY.equals(key) ? MAX_UNPARSED_FAILURES : MAX_FAILURES;
        boolean allowed = window.size() < max;
        if (allowed) {
            window.addLast(now);
        }
        if (window.isEmpty()) {
            return allowed;
        }
        failures.put(key, window);
        return allowed;
    }

    /** Seconds until the key's oldest counted attempt leaves the window (at least 1). */
    public synchronized long retryAfterSeconds(String key, Instant now) {
        Deque<Instant> window = failures.get(key);
        if (window == null || window.isEmpty()) {
            return 1;
        }
        return Math.max(1, Duration.between(now, window.peekFirst().plus(WINDOW)).getSeconds());
    }

    /** Gives back the key's latest reservation (for outcomes that are not failed guesses). */
    public synchronized void release(String key) {
        Deque<Instant> window = failures.get(key);
        if (window != null) {
            window.pollLast();
            if (window.isEmpty()) {
                failures.remove(key);
            }
        }
    }

    public synchronized void clear(String key) {
        failures.remove(key);
    }

    /** Test hook and reset: forgets everything. */
    public synchronized void clearAll() {
        failures.clear();
    }

    synchronized int size() {
        return failures.size();
    }

    private void makeRoom(Instant now) {
        if (failures.size() < MAX_KEYS) {
            return;
        }
        for (Iterator<Deque<Instant>> it = failures.values().iterator(); it.hasNext(); ) {
            Deque<Instant> w = it.next();
            trim(w, now);
            if (w.isEmpty()) {
                it.remove();
            }
        }
        if (failures.size() >= MAX_KEYS) {
            // forged Google subjects are cheap: they go before any password lockout
            String victim = null;
            for (String k : failures.keySet()) {
                if (k.startsWith("g:")) {
                    victim = k;
                    break;
                }
            }
            if (victim == null) {
                for (String k : failures.keySet()) {
                    if (!UNPARSED_KEY.equals(k)) {
                        victim = k;
                        break;
                    }
                }
            }
            failures.remove(victim != null ? victim : failures.keySet().iterator().next());
        }
    }

    private static void trim(Deque<Instant> window, Instant now) {
        Instant cutoff = now.minus(WINDOW);
        while (!window.isEmpty() && !window.peekFirst().isAfter(cutoff)) {
            window.removeFirst();
        }
    }
}
