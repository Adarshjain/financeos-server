package com.financeos.domain.statement.file;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.financeos.core.crypto.EncryptionService;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.PlatformTransactionManager;

/** The no-grace delete used right after a statement is deleted, and the all-users sweep. */
class StatementFileStoreDeleteTest {

    private final UUID user = UUID.randomUUID();
    private final Instant now = Instant.parse("2026-11-10T10:00:00Z");
    private StatementFileRepository repo;
    private StatementFileStore store;

    @BeforeEach
    void setUp() {
        repo = mock(StatementFileRepository.class);
        store = new StatementFileStore(repo, mock(EncryptionService.class), new StatementFileProperties(),
                new StatementFileMetrics(new SimpleMeterRegistry(), repo), mock(PlatformTransactionManager.class),
                Clock.fixed(now, ZoneOffset.UTC));
    }

    @Test
    void immediateDeleteRemovesAnUnreferencedFileRegardlessOfAge() {
        UUID id = UUID.randomUUID();
        when(repo.deleteByIdNowIfUnreferenced(id, user)).thenReturn(1);

        assertTrue(store.deleteIfUnreferencedNow(user, id));
    }

    @Test
    void immediateDeleteKeepsAFileAnotherStatementStillReferences() {
        UUID id = UUID.randomUUID();
        when(repo.deleteByIdNowIfUnreferenced(id, user)).thenReturn(0);

        assertFalse(store.deleteIfUnreferencedNow(user, id));
    }

    @Test
    void sweepUsesTheOneHourGraceAndReturnsTheCount() {
        when(repo.deleteAllOrphans(any())).thenReturn(4);

        assertEquals(4, store.deleteAllOrphans());
        verify(repo).deleteAllOrphans(eq(now.minus(StatementFileStore.ORPHAN_GRACE)));
    }
}
