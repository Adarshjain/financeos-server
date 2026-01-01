package com.financeos.gmail.history;

import com.financeos.gmail.domain.GmailConnection;
import com.financeos.gmail.domain.GmailSyncStateEntity;
import com.financeos.gmail.domain.GmailSyncStateRepository;
import com.financeos.gmail.internal.GmailSyncState;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.UUID;

/**
 * Manages Gmail sync state (historyId).
 */
@Service
@Transactional
public class SyncStateService {

    private final GmailSyncStateRepository repository;

    public SyncStateService(GmailSyncStateRepository repository) {
        this.repository = repository;
    }

    /**
     * Get current sync state for a connection.
     */
    @Transactional(readOnly = true)
    public GmailSyncState getSyncState(UUID connectionId) {
        return repository.findByConnectionId(connectionId)
                .map(state -> new GmailSyncState(
                        state.getHistoryId(),
                        state.getLastSyncedAt()
                ))
                .orElse(null);
    }

    /**
     * Save or update sync state.
     */
    public void saveSyncState(GmailConnection connection, String historyId, Instant lastSyncedAt) {
        GmailSyncStateEntity state = repository.findByConnectionId(connection.getId())
                .orElse(new GmailSyncStateEntity());

        state.setConnection(connection);
        state.setHistoryId(historyId);
        state.setLastSyncedAt(lastSyncedAt);

        repository.save(state);
    }

    /**
     * Delete sync state (when connection is removed).
     */
    public void deleteSyncState(UUID connectionId) {
        repository.findByConnectionId(connectionId)
                .ifPresent(repository::delete);
    }
}

