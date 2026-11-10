package com.financeos.domain.statement.file;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.financeos.domain.statement.Statement;
import com.financeos.domain.statement.StatementRepository;
import com.financeos.domain.statement.file.StatementFileAttacher.AttachResult;
import com.financeos.domain.statement.file.StatementFileStore.StoreOutcome;
import com.financeos.domain.statement.file.StatementFileStore.StoreResult;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;

class StatementFileAttacherDetailedTest {

    private final UUID user = UUID.randomUUID();
    private final UUID statementId = UUID.randomUUID();
    private final UUID fileId = UUID.randomUUID();
    private final byte[] bytes = {1};
    private StatementFileStore store;
    private StatementRepository statements;
    private StatementFileAttacher attacher;

    @BeforeEach
    void setUp() {
        store = mock(StatementFileStore.class);
        statements = mock(StatementRepository.class);
        PlatformTransactionManager tm = mock(PlatformTransactionManager.class);
        when(tm.getTransaction(any())).thenReturn(new SimpleTransactionStatus());
        attacher = new StatementFileAttacher(store, statements, tm);
    }

    private Statement statement(UUID existingFile) {
        Statement s = new Statement();
        s.setId(statementId);
        s.setFileId(existingFile);
        when(statements.findById(statementId)).thenReturn(Optional.of(s));
        return s;
    }

    private void stored(StoreOutcome o, UUID id) {
        when(store.storeDetailed(user, bytes, "a.pdf")).thenReturn(new StoreResult(id, o));
    }

    @Test
    void attachedWhenTheStatementHadNoFile() {
        Statement s = statement(null);
        stored(StoreOutcome.STORED, fileId);

        assertEquals(AttachResult.ATTACHED, attacher.attachDetailed(user, statementId, bytes, "a.pdf"));
        assertEquals(fileId, s.getFileId());
    }

    @Test
    void attachedWhenTheFileWasDeduped() {
        statement(null);
        stored(StoreOutcome.DEDUPED, fileId);

        assertEquals(AttachResult.ATTACHED, attacher.attachDetailed(user, statementId, bytes, "a.pdf"));
    }

    @Test
    void alreadyHadFileLeavesItAlone() {
        UUID other = UUID.randomUUID();
        Statement s = statement(other);
        stored(StoreOutcome.STORED, fileId);

        assertEquals(AttachResult.ALREADY_HAD_FILE, attacher.attachDetailed(user, statementId, bytes, "a.pdf"));
        assertEquals(other, s.getFileId());
        verify(statements, never()).save(any());
    }

    @Test
    void statementMissing() {
        when(statements.findById(statementId)).thenReturn(Optional.empty());
        stored(StoreOutcome.STORED, fileId);

        assertEquals(AttachResult.STATEMENT_MISSING, attacher.attachDetailed(user, statementId, bytes, "a.pdf"));
    }

    @Test
    void tooLargeUnsupportedAndEmptyArePermanent() {
        for (StoreOutcome o : new StoreOutcome[] {StoreOutcome.TOO_LARGE, StoreOutcome.UNSUPPORTED, StoreOutcome.EMPTY}) {
            stored(o, null);
            assertEquals(AttachResult.NOT_STORED_PERMANENT, attacher.attachDetailed(user, statementId, bytes, "a.pdf"), o.name());
        }
    }

    @Test
    void disabledQuotaCapAndErrorAreTransient() {
        for (StoreOutcome o : new StoreOutcome[] {StoreOutcome.DISABLED, StoreOutcome.USER_QUOTA, StoreOutcome.TOTAL_CAP, StoreOutcome.ERROR}) {
            stored(o, null);
            assertEquals(AttachResult.NOT_STORED_TRANSIENT, attacher.attachDetailed(user, statementId, bytes, "a.pdf"), o.name());
        }
    }

    @Test
    void aFailureWhileSettingTheFileIsTransientAndNeverThrows() {
        statement(null);
        stored(StoreOutcome.STORED, fileId);
        when(statements.save(any())).thenThrow(new RuntimeException("db"));

        assertEquals(AttachResult.NOT_STORED_TRANSIENT, attacher.attachDetailed(user, statementId, bytes, "a.pdf"));
    }
}
