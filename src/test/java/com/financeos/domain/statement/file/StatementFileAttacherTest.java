package com.financeos.domain.statement.file;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.financeos.domain.statement.Statement;
import com.financeos.domain.statement.StatementRepository;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;

class StatementFileAttacherTest {

    private final UUID user = UUID.randomUUID();
    private final UUID account = UUID.randomUUID();
    private final UUID statementId = UUID.randomUUID();
    private final UUID fileId = UUID.randomUUID();
    private final byte[] bytes = {1, 2, 3};
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

    // ---- attach

    @Test
    void attachSetsTheFileWhenTheStatementHasNone() {
        Statement s = statement(null);
        when(store.store(user, bytes, "a.pdf")).thenReturn(Optional.of(fileId));

        attacher.attach(user, statementId, bytes, "a.pdf");

        assertEquals(fileId, s.getFileId());
        verify(statements).save(s);
    }

    @Test
    void attachNeverReplacesAnExistingFile() {
        UUID existing = UUID.randomUUID();
        Statement s = statement(existing);
        when(store.store(user, bytes, "a.pdf")).thenReturn(Optional.of(fileId));

        attacher.attach(user, statementId, bytes, "a.pdf");

        assertEquals(existing, s.getFileId());
        verify(statements, never()).save(any());
    }

    @Test
    void attachDoesNothingWhenTheStoreKeptNothing() {
        when(store.store(user, bytes, "a.pdf")).thenReturn(Optional.empty());

        attacher.attach(user, statementId, bytes, "a.pdf");

        verify(statements, never()).findById(any());
    }

    @Test
    void attachToAMissingStatementIsANoOp() {
        when(store.store(user, bytes, "a.pdf")).thenReturn(Optional.of(fileId));
        when(statements.findById(statementId)).thenReturn(Optional.empty());

        assertDoesNotThrow(() -> attacher.attach(user, statementId, bytes, "a.pdf"));
        verify(statements, never()).save(any());
    }

    @Test
    void attachNeverThrowsWhenTheStoreThrows() {
        when(store.store(user, bytes, "a.pdf")).thenThrow(new RuntimeException("boom"));

        assertDoesNotThrow(() -> attacher.attach(user, statementId, bytes, "a.pdf"));
    }

    @Test
    void attachNeverThrowsWhenTheSaveFails() {
        statement(null);
        when(store.store(user, bytes, "a.pdf")).thenReturn(Optional.of(fileId));
        when(statements.save(any())).thenThrow(new RuntimeException("db"));

        assertDoesNotThrow(() -> attacher.attach(user, statementId, bytes, "a.pdf"));
    }

    // ---- attachIfStoredStatementLacksFile

    @Test
    void backFillStoresNothingWhenNoStatementLacksAFile() {
        when(statements.findIdsByAccountIdAndFileSha256WithoutFile(account, "sha")).thenReturn(List.of());

        attacher.attachIfStoredStatementLacksFile(user, account, "sha", bytes, "a.pdf");

        verify(store, never()).store(any(), any(), any());
        verify(statements, never()).findById(any());
    }

    @Test
    void backFillStoresOnceAndFillsTheStatementThatLacksAFile() {
        when(statements.findIdsByAccountIdAndFileSha256WithoutFile(account, "sha")).thenReturn(List.of(statementId));
        Statement s = statement(null);
        when(store.store(user, bytes, "a.pdf")).thenReturn(Optional.of(fileId));

        attacher.attachIfStoredStatementLacksFile(user, account, "sha", bytes, "a.pdf");

        assertEquals(fileId, s.getFileId());
        verify(statements).save(s);
    }

    @Test
    void backFillFillsEveryStatementOfThatFileButStoresOnce() {
        UUID second = UUID.randomUUID();
        when(statements.findIdsByAccountIdAndFileSha256WithoutFile(account, "sha")).thenReturn(List.of(statementId, second));
        Statement s1 = statement(null);
        Statement s2 = new Statement();
        s2.setId(second);
        when(statements.findById(second)).thenReturn(Optional.of(s2));
        when(store.store(user, bytes, "a.pdf")).thenReturn(Optional.of(fileId));

        attacher.attachIfStoredStatementLacksFile(user, account, "sha", bytes, "a.pdf");

        assertEquals(fileId, s1.getFileId());
        assertEquals(fileId, s2.getFileId());
        verify(store, org.mockito.Mockito.times(1)).store(user, bytes, "a.pdf");
    }

    @Test
    void backFillDoesNotChangeAStatementThatGotAFileMeanwhile() {
        when(statements.findIdsByAccountIdAndFileSha256WithoutFile(account, "sha")).thenReturn(List.of(statementId));
        UUID existing = UUID.randomUUID();
        Statement s = statement(existing);
        when(store.store(user, bytes, "a.pdf")).thenReturn(Optional.of(fileId));

        attacher.attachIfStoredStatementLacksFile(user, account, "sha", bytes, "a.pdf");

        assertEquals(existing, s.getFileId());
    }

    @Test
    void backFillWithNothingStoredLeavesTheStatementAlone() {
        when(statements.findIdsByAccountIdAndFileSha256WithoutFile(account, "sha")).thenReturn(List.of(statementId));
        Statement s = statement(null);
        when(store.store(user, bytes, "a.pdf")).thenReturn(Optional.empty());

        attacher.attachIfStoredStatementLacksFile(user, account, "sha", bytes, "a.pdf");

        assertNull(s.getFileId());
    }

    @Test
    void backFillNeverThrows() {
        when(statements.findIdsByAccountIdAndFileSha256WithoutFile(account, "sha")).thenThrow(new RuntimeException("db"));

        assertDoesNotThrow(() -> attacher.attachIfStoredStatementLacksFile(user, account, "sha", bytes, "a.pdf"));
    }
}
