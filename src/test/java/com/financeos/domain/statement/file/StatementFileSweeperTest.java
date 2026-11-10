package com.financeos.domain.statement.file;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.Test;

class StatementFileSweeperTest {

    private final StatementFileStore store = mock(StatementFileStore.class);
    private final StatementFileSweeper sweeper = new StatementFileSweeper(store);

    @Test
    void sweepDeletesOrphans() {
        when(store.deleteAllOrphans()).thenReturn(2);

        sweeper.sweep();

        verify(store).deleteAllOrphans();
    }

    @Test
    void aFailingSweepNeverThrows() {
        when(store.deleteAllOrphans()).thenThrow(new RuntimeException("db down"));

        assertDoesNotThrow(sweeper::sweep);
    }
}
