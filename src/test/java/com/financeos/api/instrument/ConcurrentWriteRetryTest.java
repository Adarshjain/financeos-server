package com.financeos.api.instrument;

import com.financeos.core.exception.DuplicateResourceException;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.DuplicateKeyException;

import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** A double-submitted instrument write that loses the unique-key race is retried once, then a 409. */
class ConcurrentWriteRetryTest {

    @Test
    void aWriteThatSucceedsRunsOnce() {
        AtomicInteger calls = new AtomicInteger();
        assertEquals("ok", ConcurrentWriteRetry.once(() -> {
            calls.incrementAndGet();
            return "ok";
        }));
        assertEquals(1, calls.get());
    }

    @Test
    void aLostRaceIsRetriedAndTheRetryReadsWhatTheOtherRequestWrote() {
        AtomicInteger calls = new AtomicInteger();
        String result = ConcurrentWriteRetry.once(() -> {
            if (calls.incrementAndGet() == 1) {
                throw new DuplicateKeyException("uq_uio_user_instrument");
            }
            return "updated the row the other request inserted";
        });
        assertEquals(2, calls.get());
        assertEquals("updated the row the other request inserted", result);
    }

    @Test
    void aSecondCollisionIsAConflictWithAPlainMessageNotA500() {
        AtomicInteger calls = new AtomicInteger();
        DuplicateResourceException e = assertThrows(DuplicateResourceException.class, () -> ConcurrentWriteRetry.once(() -> {
            calls.incrementAndGet();
            throw new DataIntegrityViolationException("uk_inst_prices_inst_asof_usr");
        }));
        assertEquals(2, calls.get());
        assertEquals(ConcurrentWriteRetry.CONFLICT_MESSAGE, e.getMessage());
    }

    @Test
    void otherFailuresAreNotRetried() {
        AtomicInteger calls = new AtomicInteger();
        assertThrows(IllegalStateException.class, () -> ConcurrentWriteRetry.once(() -> {
            calls.incrementAndGet();
            throw new IllegalStateException("boom");
        }));
        assertEquals(1, calls.get());
    }
}
