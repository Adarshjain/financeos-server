package com.financeos.api.instrument;

import com.financeos.core.exception.DuplicateResourceException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;

import java.util.function.Supplier;

/**
 * Runs an instrument write (its own transaction) and, when it loses a race with an identical one — a
 * double-submitted edit, override, manual price or catalog create hitting a unique key the other
 * request just took — runs it once more: the retry reads the row the other request wrote and updates
 * or returns it. A second collision is a 409 with a plain message, never a 500.
 */
final class ConcurrentWriteRetry {

    private static final Logger log = LoggerFactory.getLogger(ConcurrentWriteRetry.class);

    static final String CONFLICT_MESSAGE =
            "This change collided with another save of the same instrument. Refresh and try again.";

    private ConcurrentWriteRetry() {
    }

    static <T> T once(Supplier<T> write) {
        try {
            return write.get();
        } catch (DataIntegrityViolationException first) {
            log.info("Instrument write hit a unique key ({}); retrying once", first.getMostSpecificCause().getMessage());
            try {
                return write.get();
            } catch (DataIntegrityViolationException second) {
                throw new DuplicateResourceException(CONFLICT_MESSAGE);
            }
        }
    }
}
