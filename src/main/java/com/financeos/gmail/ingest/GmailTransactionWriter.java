package com.financeos.gmail.ingest;

import com.financeos.domain.account.Account;
import com.financeos.domain.transaction.*;
import com.financeos.domain.user.User;
import com.financeos.gmail.domain.*;
import com.financeos.gmail.ingest.gemini.GeminiExtractionResult;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.LocalDate;

@Component
public class GmailTransactionWriter {

    private final TransactionRepository transactionRepository;
    private final GmailProcessedMessageRepository processedMessageRepository;

    public GmailTransactionWriter(TransactionRepository transactionRepository,
                                  GmailProcessedMessageRepository processedMessageRepository) {
        this.transactionRepository = transactionRepository;
        this.processedMessageRepository = processedMessageRepository;
    }

    @Transactional
    public GmailProcessedMessage writeTransaction(
            GmailConnection connection,
            String gmailMessageId,
            GeminiExtractionResult extractionResult,
            Account resolvedAccount) {

        User user = connection.getUser();

        // 1. Watermark gate check: date >= ingest_from_date
        LocalDate txDate = extractionResult.date();
        if (resolvedAccount != null && resolvedAccount.getIngestFromDate() != null) {
            if (txDate.isBefore(resolvedAccount.getIngestFromDate())) {
                // Skip before watermark
                GmailProcessedMessage processed = findOrCreateLedgerEntry(connection, gmailMessageId);
                processed.setStatus(GmailProcessedStatus.SKIPPED_BEFORE_WATERMARK);
                processed.setTransaction(null);
                processed.setError(null);
                processed.setProcessedAt(Instant.now());
                return processedMessageRepository.save(processed);
            }
        }

        // 2. Map fields and persist
        Transaction txn = new Transaction();
        txn.setUser(user);
        txn.setAccount(resolvedAccount);
        txn.setDate(txDate);
        txn.setAmount(extractionResult.amount().abs()); // Store unsigned magnitude
        txn.setDescription(extractionResult.description());
        txn.setSource(TransactionSource.gmail_transaction_alert);
        txn.setType(TransactionType.valueOf(extractionResult.direction().toUpperCase()));
        txn.setReviewType(ReviewType.NEEDS_REVIEW);
        txn.setSourceMessageId(gmailMessageId);
        txn.setTransactionUnderMonitoring(false);
        txn.setTransactionExcluded(false);

        txn = transactionRepository.save(txn);

        // 3. Record in ledger
        GmailProcessedMessage processed = findOrCreateLedgerEntry(connection, gmailMessageId);
        processed.setStatus(GmailProcessedStatus.CREATED);
        processed.setTransaction(txn);
        processed.setError(null);
        processed.setProcessedAt(Instant.now());

        return processedMessageRepository.save(processed);
    }

    @Transactional
    public GmailProcessedMessage recordSkipped(GmailConnection connection, String gmailMessageId, GmailProcessedStatus status, String error) {
        GmailProcessedMessage processed = findOrCreateLedgerEntry(connection, gmailMessageId);
        processed.setStatus(status);
        processed.setTransaction(null);
        processed.setError(error);
        processed.setProcessedAt(Instant.now());
        return processedMessageRepository.save(processed);
    }

    private GmailProcessedMessage findOrCreateLedgerEntry(GmailConnection connection, String gmailMessageId) {
        return processedMessageRepository
                .findByConnectionIdAndGmailMessageId(connection.getId(), gmailMessageId)
                .orElseGet(() -> {
                    GmailProcessedMessage pm = new GmailProcessedMessage();
                    pm.setConnection(connection);
                    pm.setUser(connection.getUser());
                    pm.setGmailMessageId(gmailMessageId);
                    return pm;
                });
    }
}
