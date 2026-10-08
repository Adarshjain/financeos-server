package com.financeos.core.tx;

import java.util.function.Supplier;

/**
 * Runs one independent section of a composite read (one kind of obligation, one part of net worth)
 * so that its failure cannot poison the caller's transaction.
 *
 * <p>Catching an exception is not enough on its own: when the failing collaborator is a
 * {@code @Transactional} bean (a service or a Spring Data repository) that joined the caller's
 * transaction, its interceptor has already marked that shared transaction rollback-only, and the
 * caller's commit then throws {@code UnexpectedRollbackException}. The Spring implementation runs
 * each section in its own transaction instead, so only that section rolls back.
 */
public interface SectionRunner {

    /** Runs the section, propagating its exception after rolling back only the section's own work. */
    <T> T run(Supplier<T> section);

    /** Runs the section inline, with no transaction of its own (plain unit tests, no Spring). */
    SectionRunner DIRECT = new SectionRunner() {
        @Override
        public <T> T run(Supplier<T> section) {
            return section.get();
        }
    };
}
