package com.financeos.core.tx;

import com.financeos.core.security.UserFilterEnabler;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.function.Supplier;

/**
 * {@link SectionRunner} that gives every section its own read-only transaction
 * ({@code REQUIRES_NEW}): the caller's transaction is suspended, the section's collaborators join
 * the new one, and a failure rolls back only that transaction before the exception reaches the
 * caller's catch. The runner itself enables the tenant filter on the section's own session before the
 * section runs, so bare repository calls in the section are tenant-scoped even when the caller has no
 * transaction (and so no filter) of its own.
 */
@Component
public class TransactionalSectionRunner implements SectionRunner {

    private final TransactionTemplate template;
    private final UserFilterEnabler userFilterEnabler;

    public TransactionalSectionRunner(PlatformTransactionManager transactionManager, UserFilterEnabler userFilterEnabler) {
        this.userFilterEnabler = userFilterEnabler;
        this.template = new TransactionTemplate(transactionManager);
        this.template.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        this.template.setReadOnly(true);
    }

    @Override
    public <T> T run(Supplier<T> section) {
        return template.execute(status -> {
            userFilterEnabler.enable();
            return section.get();
        });
    }
}
