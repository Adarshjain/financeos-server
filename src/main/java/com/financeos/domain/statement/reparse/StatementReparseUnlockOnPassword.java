package com.financeos.domain.statement.reparse;

import com.financeos.domain.account.StatementPasswordSavedEvent;
import com.financeos.domain.statement.StatementRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The scheduled re-read no longer retries a statement whose last failure was a missing or wrong password. When a
 * statement password is saved or changed, the user's locked statements are made selectable again so the next
 * scheduled run reads them with it.
 */
@Component
public class StatementReparseUnlockOnPassword {

    private static final Logger log = LoggerFactory.getLogger(StatementReparseUnlockOnPassword.class);

    private final StatementRepository statementRepository;
    private final TransactionTemplate tx;

    public StatementReparseUnlockOnPassword(StatementRepository statementRepository, PlatformTransactionManager txManager) {
        this.statementRepository = statementRepository;
        this.tx = new TransactionTemplate(txManager);
        // After commit the finished transaction is still bound to the thread; joining it would never commit the update.
        this.tx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void onPasswordSaved(StatementPasswordSavedEvent event) {
        if (event == null || event.userId() == null) return;
        try {
            Integer n = tx.execute(status -> statementRepository.clearLockedReparse(event.userId()));
            if (n != null && n > 0) log.info("Locked statements queued for re-read after a password change: {}", n);
        } catch (Exception e) {
            log.warn("Could not queue locked statements for re-read: {}", e.getClass().getSimpleName());
        }
    }
}
