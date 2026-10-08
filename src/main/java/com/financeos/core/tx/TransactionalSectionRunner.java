package com.financeos.core.tx;

import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.function.Supplier;

/**
 * {@link SectionRunner} that gives every section its own read-only transaction
 * ({@code REQUIRES_NEW}): the caller's transaction is suspended, the section's collaborators join
 * the new one, and a failure rolls back only that transaction before the exception reaches the
 * caller's catch. The section's {@code @Transactional} collaborators still enable the tenant filter
 * on entry, now on the section's own session.
 */
@Component
public class TransactionalSectionRunner implements SectionRunner {

    private final TransactionTemplate template;

    public TransactionalSectionRunner(PlatformTransactionManager transactionManager) {
        this.template = new TransactionTemplate(transactionManager);
        this.template.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        this.template.setReadOnly(true);
    }

    @Override
    public <T> T run(Supplier<T> section) {
        return template.execute(status -> section.get());
    }
}
