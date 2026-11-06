package com.financeos.domain.statement;

import com.financeos.domain.statement.skip.StatementSkippedPeriodRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.List;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/** The coverage cache is dropped after the statement's transaction completes, or at once outside a transaction. */
class StatementCoverageInvalidateTest {

    private final StatementRepository statements = mock(StatementRepository.class);
    private final StatementSkippedPeriodRepository skips = mock(StatementSkippedPeriodRepository.class);
    private final StatementCoverage coverage = new StatementCoverage(statements, skips);
    private final UUID accountId = UUID.randomUUID();

    @AfterEach
    void tearDown() {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.clearSynchronization();
        }
    }

    private void lookUp() {
        coverage.statements(accountId);
    }

    @Test
    void outsideATransactionTheCacheIsDroppedAtOnce() {
        when(statements.findCoveragePeriodsByAccountIdIn(any())).thenReturn(List.of());
        when(skips.findRangesByAccountIds(any())).thenReturn(List.of());
        lookUp();
        coverage.invalidateAll();
        lookUp();
        verify(statements, times(2)).findCoveragePeriodsByAccountIdIn(any());
    }

    @Test
    void insideATransactionTheCacheIsDroppedOnlyAfterItCompletes() {
        when(statements.findCoveragePeriodsByAccountIdIn(any())).thenReturn(List.of());
        when(skips.findRangesByAccountIds(any())).thenReturn(List.of());
        TransactionSynchronizationManager.initSynchronization();
        lookUp();
        coverage.invalidateAll();
        lookUp();
        verify(statements, times(1)).findCoveragePeriodsByAccountIdIn(any());

        for (TransactionSynchronization s : TransactionSynchronizationManager.getSynchronizations()) {
            s.afterCompletion(TransactionSynchronization.STATUS_COMMITTED);
        }
        lookUp();
        verify(statements, times(2)).findCoveragePeriodsByAccountIdIn(any());
    }
}
