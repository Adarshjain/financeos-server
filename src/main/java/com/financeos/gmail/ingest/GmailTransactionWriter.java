package com.financeos.gmail.ingest;

import com.financeos.domain.account.Account;
import com.financeos.domain.account.card.Card;
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
    private final ReviewStatusManager reviewStatusManager;

    /** Optional so tests that build the writer by hand need no publisher. */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private org.springframework.context.ApplicationEventPublisher eventPublisher;

    /** Stateless; replaced by the Spring bean when present. */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private TransactionMatcher transactionMatcher = new TransactionMatcher();

    public GmailTransactionWriter(TransactionRepository transactionRepository,
                                  GmailProcessedMessageRepository processedMessageRepository,
                                  ReviewStatusManager reviewStatusManager) {
        this.transactionRepository = transactionRepository;
        this.processedMessageRepository = processedMessageRepository;
        this.reviewStatusManager = reviewStatusManager;
    }

    @Transactional
    public GmailProcessedMessage writeTransaction(
            GmailConnection connection,
            String gmailMessageId,
            GeminiExtractionResult extractionResult,
            Account resolvedAccount) {
        return writeTransaction(connection, gmailMessageId, extractionResult, resolvedAccount, null);
    }

    @Transactional
    public GmailProcessedMessage writeTransaction(
            GmailConnection connection,
            String gmailMessageId,
            GeminiExtractionResult extractionResult,
            Account resolvedAccount,
            Card resolvedCard) {

        User user = connection.getUser();

        // 1. Watermark gate check: date >= ingest_from_date
        LocalDate txDate = extractionResult.date();
        if (resolvedAccount.getIngestFromDate() != null) {
            if (txDate.isBefore(resolvedAccount.getIngestFromDate())) {
                // Skip before watermark
                GmailProcessedMessage processed = findOrCreateLedgerEntry(connection, gmailMessageId);
                processed.setAccount(resolvedAccount);
                processed.setStatus(GmailProcessedStatus.SKIPPED_BEFORE_WATERMARK);
                processed.setTransaction(null);
                processed.setError(null);
                processed.setProcessedAt(Instant.now());
                return processedMessageRepository.save(processed);
            }
        }

        // 1b. An alert that arrives after its statement: the statement row already records the payment.
        Transaction statementRow = findPairedStatementRow(resolvedAccount, resolvedCard, extractionResult);
        if (statementRow != null) {
            GmailProcessedMessage processed = findOrCreateLedgerEntry(connection, gmailMessageId);
            processed.setAccount(resolvedAccount);
            processed.setStatus(GmailProcessedStatus.SKIPPED_DUPLICATE);
            processed.setTransaction(statementRow);
            processed.setError(null);
            processed.setProcessedAt(Instant.now());
            return processedMessageRepository.save(processed);
        }

        // 2. Map fields and persist
        Transaction txn = new Transaction();
        txn.setUser(user);
        txn.setAccount(resolvedAccount);
        txn.setCard(resolvedCard);
        txn.setDate(txDate);
        txn.setAmount(extractionResult.amount().abs()); // Store unsigned magnitude
        txn.setSourcedDescription(extractionResult.description());
        txn.setSource(TransactionSource.gmail_transaction_alert);
        txn.setType(TransactionType.fromLlmDirection(extractionResult.direction()));
        reviewStatusManager.addReason(txn, ReviewReason.UNRECONCILED);
        if (extractionResult.dateCorrected()) {
            reviewStatusManager.addReason(txn, ReviewReason.DATE_CORRECTED);
        }
        txn.setSourceMessageId(gmailMessageId);
        txn.setTransactionUnderMonitoring(false);
        txn.setTransactionExcluded(false);

        txn = transactionRepository.save(txn);
        if (eventPublisher != null) {
            eventPublisher.publishEvent(new com.financeos.domain.transaction.autolink.TransactionsIngestedEvent(user.getId()));
        }

        // 3. Record in ledger
        GmailProcessedMessage processed = findOrCreateLedgerEntry(connection, gmailMessageId);
        processed.setAccount(resolvedAccount);
        processed.setStatus(GmailProcessedStatus.CREATED);
        processed.setTransaction(txn);
        processed.setError(null);
        processed.setProcessedAt(Instant.now());

        return processedMessageRepository.save(processed);
    }

    /**
     * The closest statement-sourced row (Gmail statement or upload) on the same account with the alert's
     * direction and exact amount, dated within a day, that no other mail already stands for. One query per alert.
     */
    private Transaction findPairedStatementRow(Account account, Card card, GeminiExtractionResult alert) {
        LocalDate date = alert.date();
        java.util.List<Transaction> rows = transactionRepository.findUnpairedStatementRows(
                account.getId(), TransactionType.fromLlmDirection(alert.direction()), alert.amount().abs(),
                date.minusDays(1), date.plusDays(1),
                java.util.List.of(TransactionSource.gmail_statement, TransactionSource.file_upload));
        if (rows == null) return null;
        Transaction alertRow = new Transaction();
        alertRow.setSource(TransactionSource.gmail_transaction_alert);
        alertRow.setSourcedDescription(alert.description());
        return rows.stream()
                // A row already in an uncertain duplicate pairing is waiting for the user, not a settled record.
                .filter(r -> r.getReviewReasons() == null || !r.getReviewReasons().contains(ReviewReason.DUPLICATE_SUSPECT))
                // Skip only for the same merchant; two different merchants for one amount are two payments.
                .filter(r -> !hasMerchant(r) || !hasMerchant(alertRow)
                        || transactionMatcher.sameCounterparty(r, alertRow))
                .filter(r -> card == null || r.getCard() == null || card.getId().equals(r.getCard().getId()))
                .min(java.util.Comparator
                        .comparingLong((Transaction r) -> Math.abs(java.time.temporal.ChronoUnit.DAYS.between(date, r.getDate())))
                        .thenComparing(Transaction::getCreatedAt, java.util.Comparator.nullsLast(java.util.Comparator.naturalOrder())))
                .orElse(null);
    }

    private static boolean hasMerchant(Transaction t) {
        String d = t.getSourcedDescription() != null ? t.getSourcedDescription() : t.getDescription();
        return d != null && !d.isBlank();
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
