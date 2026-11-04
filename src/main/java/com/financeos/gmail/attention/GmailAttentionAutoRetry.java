package com.financeos.gmail.attention;

import com.financeos.domain.account.StatementPasswordSavedEvent;
import com.financeos.domain.llm.LlmKeySavedEvent;
import com.financeos.gmail.domain.AttentionReason;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import java.util.UUID;

/**
 * Retries the Gmail items a fix unblocks: a saved AI key retries "No AI key" items, a changed statement
 * password retries "Locked statement" items. Runs after the saving transaction commits; a failure never
 * reaches the save.
 */
@Component
public class GmailAttentionAutoRetry {

    private static final Logger log = LoggerFactory.getLogger(GmailAttentionAutoRetry.class);

    private final GmailAttentionService service;

    public GmailAttentionAutoRetry(GmailAttentionService service) {
        this.service = service;
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void onKeySaved(LlmKeySavedEvent event) {
        if (event != null) retry(event.userId(), AttentionReason.NO_AI_KEY);
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void onPasswordSaved(StatementPasswordSavedEvent event) {
        if (event != null) retry(event.userId(), AttentionReason.LOCKED_STATEMENT);
    }

    void retry(UUID userId, AttentionReason reason) {
        if (userId == null) return;
        try {
            int n = service.retryAll(userId, reason);
            if (n > 0) log.info("Auto-retried {} Gmail attention item(s) for reason {}", n, reason);
        } catch (Exception e) {
            log.warn("Gmail attention auto-retry failed for reason {}: {}", reason, e.getClass().getSimpleName());
        }
    }
}
