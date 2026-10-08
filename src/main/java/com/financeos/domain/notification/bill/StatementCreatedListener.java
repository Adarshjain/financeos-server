package com.financeos.domain.notification.bill;

import com.financeos.core.security.UserContext;
import com.financeos.domain.notification.StatementCreatedEvent;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * Fires the statement digest once the ingesting transaction has committed (same pattern as the
 * price-refresh listener). Failures are swallowed: a push problem must never fail an ingest.
 */
@Component
public class StatementCreatedListener {

    private static final Logger log = LoggerFactory.getLogger(StatementCreatedListener.class);

    private final BillNotificationService billNotificationService;

    public StatementCreatedListener(BillNotificationService billNotificationService) {
        this.billNotificationService = billNotificationService;
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onStatementCreated(StatementCreatedEvent event) {
        UUID previous = UserContext.getCurrentUserId();
        if (previous == null && event.userId() != null) {
            UserContext.setCurrentUserId(event.userId());
        }
        try {
            billNotificationService.onStatementCreated(event.userId(), event.statementId());
        } catch (Exception e) {
            log.warn("Statement notification failed for statement {}: {}", event.statementId(), e.getMessage());
        } finally {
            if (previous == null) {
                UserContext.clear();
            }
        }
    }
}
