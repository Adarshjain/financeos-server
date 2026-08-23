package com.financeos.gmail.ingest;

import com.financeos.gmail.domain.GmailConnection;
import com.financeos.gmail.domain.GmailSyncCursor;
import com.financeos.gmail.domain.GmailSyncCursorRepository;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
@Transactional
public class GmailSyncCursorService {

    private final GmailSyncCursorRepository cursorRepository;
    private final GmailSenderRepository senderRepository;
    private final GmailIngestProperties ingestProperties;

    public GmailSyncCursorService(GmailSyncCursorRepository cursorRepository,
                                  GmailSenderRepository senderRepository,
                                  GmailIngestProperties ingestProperties) {
        this.cursorRepository = cursorRepository;
        this.senderRepository = senderRepository;
        this.ingestProperties = ingestProperties;
    }

    /**
     * Get or seed sync cursors for all enabled senders for this connection.
     */
    public List<GmailSyncCursor> getOrSeedCursors(GmailConnection connection) {
        UUID userId = connection.getUser().getId();
        List<GmailSender> enabledSenders = senderRepository.findByUserIdAndEnabledTrue(userId);
        if (enabledSenders.isEmpty()) {
            return List.of();
        }

        List<GmailSyncCursor> existingCursors = cursorRepository.findByConnectionId(connection.getId());
        Map<UUID, GmailSyncCursor> cursorBySenderId = existingCursors.stream()
                .collect(Collectors.toMap(c -> c.getSender().getId(), c -> c));

        List<GmailSyncCursor> result = new ArrayList<>();
        Instant defaultSeedTime = Instant.now().minus(Duration.ofDays(ingestProperties.getFirstBackfillDays()));

        for (GmailSender sender : enabledSenders) {
            GmailSyncCursor cursor = cursorBySenderId.get(sender.getId());
            if (cursor == null) {
                cursor = new GmailSyncCursor();
                cursor.setUser(connection.getUser());
                cursor.setConnection(connection);
                cursor.setSender(sender);
                cursor.setLastListedAt(defaultSeedTime);
                cursor.setEarliestCoveredAt(defaultSeedTime);
                cursor = cursorRepository.save(cursor);
            }
            result.add(cursor);
        }

        return result;
    }

    /**
     * Delete and reseed cursor when sender address changes.
     */
    public void resetCursorForSender(UUID senderId) {
        cursorRepository.deleteBySenderId(senderId);
    }

    public void updateLastListedAt(List<GmailSyncCursor> cursors, Instant fetchStart) {
        for (GmailSyncCursor cursor : cursors) {
            cursor.setLastListedAt(fetchStart);
            cursorRepository.save(cursor);
        }
    }

    public void updateEarliestCoveredAt(List<GmailSyncCursor> cursors, Instant floorInstant) {
        for (GmailSyncCursor cursor : cursors) {
            cursor.setEarliestCoveredAt(floorInstant);
            cursorRepository.save(cursor);
        }
    }
}
