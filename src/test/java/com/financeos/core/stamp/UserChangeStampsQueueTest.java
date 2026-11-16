package com.financeos.core.stamp;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.startsWith;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.AbstractExecutorService;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentMatchers;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** Bumps are queued after commit, coalesced per user, never block the committing thread and drop when full. */
class UserChangeStampsQueueTest {

    /** Holds submitted tasks until the test runs them; can be told to reject. */
    private static final class ManualExecutor extends AbstractExecutorService {
        final List<Runnable> tasks = new ArrayList<>();
        boolean reject;
        boolean shutdown;

        @Override public void execute(Runnable command) {
            if (reject) {
                throw new RejectedExecutionException("full");
            }
            tasks.add(command);
        }

        void runAll() {
            List<Runnable> now = new ArrayList<>(tasks);
            tasks.clear();
            now.forEach(Runnable::run);
        }

        @Override public void shutdown() { shutdown = true; }
        @Override public List<Runnable> shutdownNow() { shutdown = true; return List.of(); }
        @Override public boolean isShutdown() { return shutdown; }
        @Override public boolean isTerminated() { return shutdown; }
        @Override public boolean awaitTermination(long timeout, TimeUnit unit) { return true; }
    }

    private final JdbcTemplate jdbc = mock(JdbcTemplate.class);
    private final ManualExecutor executor = new ManualExecutor();
    private final UserChangeStamps stamps = new UserChangeStamps(jdbc, mock(PlatformTransactionManager.class), executor);

    @AfterEach
    void clearSynchronization() {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.clearSynchronization();
        }
    }

    /** One committed transaction that changed these users. */
    private void commit(UUID... users) {
        TransactionSynchronizationManager.initSynchronization();
        for (UUID user : users) {
            stamps.markChanged(user);
        }
        List<TransactionSynchronization> syncs = new ArrayList<>(TransactionSynchronizationManager.getSynchronizations());
        syncs.forEach(TransactionSynchronization::afterCommit);
        syncs.forEach(s -> s.afterCompletion(TransactionSynchronization.STATUS_COMMITTED));
        TransactionSynchronizationManager.clearSynchronization();
    }

    private void bumps(UUID user, int times) {
        verify(jdbc, times(times)).update(startsWith("UPDATE user_change_stamp"),
                ArgumentMatchers.<Object>any(), ArgumentMatchers.eq(user.toString()));
    }

    @Test
    void aCommitQueuesTheBumpInsteadOfRunningItOnTheCommittingThread() {
        UUID alice = UUID.randomUUID();

        commit(alice);

        assertEquals(1, executor.tasks.size());
        bumps(alice, 0);
        executor.runAll();
        bumps(alice, 1);
    }

    @Test
    void manyCommitsForOneUserWhileABumpIsWaitingShareThatBump() {
        UUID alice = UUID.randomUUID();
        UUID bob = UUID.randomUUID();

        commit(alice);
        commit(alice, bob);
        commit(alice);

        assertEquals(2, executor.tasks.size());
        executor.runAll();
        bumps(alice, 1);
        bumps(bob, 1);
    }

    @Test
    void aChangeAfterTheBumpStartedQueuesAFreshBump() {
        UUID alice = UUID.randomUUID();
        commit(alice);
        executor.runAll();

        commit(alice);
        executor.runAll();

        bumps(alice, 2);
    }

    @Test
    void aFullQueueDropsTheBumpWithoutBlockingOrThrowing() {
        for (int i = 0; i < UserChangeStamps.MAX_PENDING; i++) {
            commit(UUID.randomUUID());
        }
        UUID late = UUID.randomUUID();

        assertDoesNotThrow(() -> commit(late));

        assertEquals(UserChangeStamps.MAX_PENDING, executor.tasks.size());
        executor.runAll();
        bumps(late, 0);
    }

    @Test
    void aRejectingExecutorNeverFailsTheCommitAndAllowsALaterBump() {
        UUID alice = UUID.randomUUID();
        executor.reject = true;
        assertDoesNotThrow(() -> commit(alice));

        executor.reject = false;
        commit(alice);
        executor.runAll();

        bumps(alice, 1);
    }

    @Test
    void shutdownStopsTheExecutor() {
        stamps.shutdown();

        assertEquals(true, executor.isShutdown());
    }
}
