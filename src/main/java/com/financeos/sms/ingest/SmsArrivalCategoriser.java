package com.financeos.sms.ingest;

import com.financeos.domain.categorization.CategoryAssigner;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Categorises a transaction an SMS created, once the posting transaction has committed and in a transaction of its own,
 * so a failure here can never undo the post. The message text is used for this call only: it is not stored or logged.
 */
@Component
public class SmsArrivalCategoriser {

    private static final Logger log = LoggerFactory.getLogger(SmsArrivalCategoriser.class);

    /** A transaction was created from message {@code messageId}; {@code text} is the message text (transient). */
    public record Created(UUID userId, UUID transactionId, UUID messageId, String counterparty, String sender, String text,
                          String templateKey) {
    }

    private final CategoryAssigner categoryAssigner;

    public SmsArrivalCategoriser(CategoryAssigner categoryAssigner) {
        this.categoryAssigner = categoryAssigner;
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void onCreated(Created e) {
        try {
            categoryAssigner.assignOnArrivalById(e.userId(), List.of(e.transactionId()), Map.of(e.transactionId(),
                    new CategoryAssigner.ArrivalContext(e.counterparty(), "sms:" + e.sender(), null, e.text(), e.templateKey())));
        } catch (RuntimeException ex) {
            // Uncategorised rows are fine; the post stands.
            log.warn("sms.categorise.failed message={} error={}", e.messageId(), ex.getClass().getSimpleName());
        }
    }
}
