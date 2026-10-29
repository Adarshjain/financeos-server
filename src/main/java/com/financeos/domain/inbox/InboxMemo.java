package com.financeos.domain.inbox;

import com.financeos.api.inbox.dto.InboxResponse;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.function.LongSupplier;
import java.util.function.Supplier;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * A few-seconds memo of each user's built inbox, shared by {@code GET /inbox} and
 * {@code GET /inbox/summary} (the shell polls the summary on every page). Keyed by user id, bounded
 * (least recently used goes first), and dropped for a user when that user makes any write
 * ({@link InboxMemoEvictionInterceptor}). A build that was running while an eviction happened is
 * never stored, so a read racing a write cannot leave pre-write data behind. A TTL of 0 disables it.
 */
@Component
public class InboxMemo {

    static final int MAX_ENTRIES = 500;

    private record Entry(InboxResponse value, long expiresAtNanos) {}

    private final long ttlNanos;
    private final LongSupplier nanoClock;
    private final Map<UUID, Entry> entries = new LinkedHashMap<>(16, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<UUID, Entry> eldest) {
            return size() > MAX_ENTRIES;
        }
    };
    /** Bumped on every eviction (any user); a build only stores its result when this did not move while it ran. */
    private long generation;

    @Autowired
    public InboxMemo(@Value("${app.inbox.memo-ttl-seconds:10}") long ttlSeconds) {
        this(ttlSeconds, System::nanoTime);
    }

    InboxMemo(long ttlSeconds, LongSupplier nanoClock) {
        this.ttlNanos = Math.max(0, ttlSeconds) * 1_000_000_000L;
        this.nanoClock = nanoClock;
    }

    public InboxResponse get(UUID userId, Supplier<InboxResponse> builder) {
        if (ttlNanos == 0) {
            return builder.get();
        }
        long startGeneration;
        synchronized (this) {
            Entry hit = entries.get(userId);
            if (hit != null && nanoClock.getAsLong() - hit.expiresAtNanos() < 0) {
                return hit.value();
            }
            entries.remove(userId);
            startGeneration = generation;
        }
        InboxResponse built = builder.get();
        synchronized (this) {
            if (generation == startGeneration) {
                entries.put(userId, new Entry(built, nanoClock.getAsLong() + ttlNanos));
            }
        }
        return built;
    }

    public synchronized void evict(UUID userId) {
        generation++;
        entries.remove(userId);
    }
}
