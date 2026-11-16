package com.financeos.domain.inbox;

import com.financeos.api.inbox.dto.InboxResponse;
import com.financeos.core.stamp.UserChangeStamps;
import com.financeos.core.time.AppTime;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.function.LongSupplier;
import java.util.function.Supplier;
import java.util.function.ToLongFunction;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * A memo of each user's built inbox, shared by {@code GET /inbox} and {@code GET /inbox/summary} (the shell polls the
 * summary on every page), plus other per-user values built beside it (see {@link #get(UUID, String, Supplier)}).
 * Keyed by user id, bounded (least recently used goes first).
 *
 * <p>An entry is served only while the user's {@link UserChangeStamps change stamp} still has the version it was
 * built at (so a change made on either JVM, API or worker, drops it), the business date has not moved, and it is
 * younger than the max age. It is also dropped when the user makes any write over HTTP
 * ({@link InboxMemoEvictionInterceptor}). A build that was running while an eviction happened is never stored, so a
 * read racing a write cannot leave pre-write data behind. A max age of 0 disables the memo.
 */
@Component
public class InboxMemo {

    static final int MAX_ENTRIES = 1000;
    private static final String INBOX = "inbox";

    private record Key(UUID userId, String slot) {}

    private record Entry(Object value, long expiresAtNanos, long stamp, LocalDate date) {}

    private final long ttlNanos;
    private final LongSupplier nanoClock;
    private final ToLongFunction<UUID> stampReader;
    private final Supplier<LocalDate> today;
    private final Map<Key, Entry> entries = new LinkedHashMap<>(16, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<Key, Entry> eldest) {
            return size() > MAX_ENTRIES;
        }
    };
    /** Bumped on every eviction (any user); a build only stores its result when this did not move while it ran. */
    private long generation;

    @Autowired
    public InboxMemo(@Value("${app.inbox.memo-ttl-seconds:60}") long maxAgeSeconds, UserChangeStamps stamps) {
        this(maxAgeSeconds, System::nanoTime, stamps::version, AppTime::today);
    }

    /** Without a change stamp (date and max age only): for tests that build their own memo. */
    public InboxMemo(long maxAgeSeconds) {
        this(maxAgeSeconds, System::nanoTime, userId -> 0L, AppTime::today);
    }

    InboxMemo(long ttlSeconds, LongSupplier nanoClock) {
        this(ttlSeconds, nanoClock, userId -> 0L, AppTime::today);
    }

    InboxMemo(long ttlSeconds, LongSupplier nanoClock, ToLongFunction<UUID> stampReader, Supplier<LocalDate> today) {
        this.ttlNanos = Math.max(0, ttlSeconds) * 1_000_000_000L;
        this.nanoClock = nanoClock;
        this.stampReader = stampReader;
        this.today = today;
    }

    public InboxResponse get(UUID userId, Supplier<InboxResponse> builder) {
        return get(userId, INBOX, builder);
    }

    /** The user's cached value for {@code slot}, built with {@code builder} when missing or out of date. */
    @SuppressWarnings("unchecked")
    public <V> V get(UUID userId, String slot, Supplier<V> builder) {
        if (ttlNanos == 0) {
            return builder.get();
        }
        Key key = new Key(userId, slot);
        // Read before building: a change landing while the build runs leaves this entry older than the stamp.
        long stamp = stampReader.applyAsLong(userId);
        LocalDate date = today.get();
        long startGeneration;
        synchronized (this) {
            Entry hit = entries.get(key);
            if (hit != null && hit.stamp() == stamp && hit.date().equals(date)
                    && nanoClock.getAsLong() - hit.expiresAtNanos() < 0) {
                return (V) hit.value();
            }
            entries.remove(key);
            startGeneration = generation;
        }
        V built = builder.get();
        synchronized (this) {
            if (generation == startGeneration && stamp >= 0) {
                entries.put(key, new Entry(built, nanoClock.getAsLong() + ttlNanos, stamp, date));
            }
        }
        return built;
    }

    public synchronized void evict(UUID userId) {
        generation++;
        entries.keySet().removeIf(key -> key.userId().equals(userId));
    }
}
