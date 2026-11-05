package com.financeos.domain.nps;

import com.financeos.domain.nps.statement.ParsedNpsStatement;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Holds a parsed statement between its preview and its commit, in memory for a short while. An entry belongs to
 * the user who previewed it: anyone else gets nothing back. It keeps the password the user typed (if any) so the
 * commit can save it on the account; nothing here is logged.
 */
@Component
public class NpsImportPreviewStore {

    static final Duration TTL = Duration.ofMinutes(30);
    private static final int MAX_ENTRIES = 500;

    public record Stored(UUID userId, ParsedNpsStatement parsed, String fileSha256, String providedPassword,
                         UUID accountId, Instant expiresAt) {
    }

    private final Map<UUID, Stored> entries = new ConcurrentHashMap<>();

    public UUID put(UUID userId, ParsedNpsStatement parsed, String fileSha256, String providedPassword, UUID accountId) {
        purge();
        if (entries.size() >= MAX_ENTRIES) {
            entries.entrySet().stream().min(Comparator.comparing(e -> e.getValue().expiresAt()))
                    .ifPresent(e -> entries.remove(e.getKey()));
        }
        UUID id = UUID.randomUUID();
        entries.put(id, new Stored(userId, parsed, fileSha256, providedPassword, accountId, Instant.now().plus(TTL)));
        return id;
    }

    /** The user's own live preview, or null (unknown, expired or somebody else's). */
    public Stored get(UUID id, UUID userId) {
        purge();
        Stored s = id == null ? null : entries.get(id);
        return s != null && s.userId().equals(userId) ? s : null;
    }

    public void remove(UUID id) {
        if (id != null) {
            entries.remove(id);
        }
    }

    private void purge() {
        Instant now = Instant.now();
        entries.values().removeIf(s -> s.expiresAt().isBefore(now));
    }
}
