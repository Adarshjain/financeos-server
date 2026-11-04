package com.financeos.gmail.ingest.event;

import com.financeos.gmail.ingest.SenderAllowlistService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/** Adds a bank's sender domains after the account's transaction committed; a failure here never touches the account. */
@Component
public class BankSendersEventListener {

    private static final Logger log = LoggerFactory.getLogger(BankSendersEventListener.class);

    private final SenderAllowlistService senderAllowlistService;

    public BankSendersEventListener(SenderAllowlistService senderAllowlistService) {
        this.senderAllowlistService = senderAllowlistService;
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void handle(BankSendersRequestedEvent event) {
        try {
            int added = senderAllowlistService.addBankSenders(event.userId(), event.bankId(), event.fromDate()).size();
            log.info("Bank senders added for bank {}: {}", event.bankId(), added);
        } catch (RuntimeException e) {
            log.warn("Adding bank senders for bank {} failed: {}", event.bankId(), e.getClass().getSimpleName());
        }
    }
}
