package com.financeos.gmail.ingest;

import com.financeos.domain.account.Account;
import com.financeos.domain.account.card.Card;
import com.financeos.domain.transaction.*;
import com.financeos.domain.transaction.match.AlertMatcher;
import com.financeos.domain.user.User;
import com.financeos.gmail.domain.*;
import com.financeos.gmail.ingest.gemini.GeminiExtractionResult;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.LocalDate;
import java.util.Set;

@Component
public class GmailTransactionWriter {

    private final TransactionRepository transactionRepository;
    private final GmailProcessedMessageRepository processedMessageRepository;
    private final ReviewStatusManager reviewStatusManager;

    /** Optional so tests that build the writer by hand need no publisher. */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private org.springframework.context.ApplicationEventPublisher eventPublisher;

    /** Optional so tests that build the writer by hand need none; without it every alert is created Pending, unmatched. */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private AlertMatcher alertMatcher;

    /** Optional so tests that build the writer by hand need none: counts real provider / issuer merges. */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.financeos.core.observability.ObservabilityMetrics observabilityMetrics;

    /** What the alert matcher reads from the email besides the extraction: sender, send time and references. */
    public record EmailContext(String senderAddress, Instant sentAt, Set<String> refs) {
    }

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
        return writeTransaction(connection, gmailMessageId, extractionResult, resolvedAccount, resolvedCard, null);
    }

    /**
     * Writes one alert: before the account's ingest date it is skipped; else the alert matcher attaches it to the
     * payment it already is (statement line, provider / issuer twin, resend, manual entry) or creates its transaction.
     */
    @Transactional
    public GmailProcessedMessage writeTransaction(
            GmailConnection connection,
            String gmailMessageId,
            GeminiExtractionResult extractionResult,
            Account resolvedAccount,
            Card resolvedCard,
            EmailContext email) {

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

        // 2. Match (attach to an existing payment) or create. A capped date is kept without a tag.
        AlertMatcher.Alert alert = new AlertMatcher.Alert(user, resolvedAccount, resolvedCard,
                TransactionType.fromLlmDirection(extractionResult.direction()), extractionResult.amount().abs(),
                extractionResult.currency(), txDate, extractionResult.description(), extractionResult.accountLast4(),
                email == null ? Set.of() : email.refs(), email == null ? null : email.senderAddress(),
                email == null ? null : email.sentAt(), gmailMessageId);
        AlertMatcher.Outcome outcome = alertMatcher != null ? alertMatcher.matchOrCreate(alert) : createUnmatched(alert);

        GmailProcessedMessage processed = findOrCreateLedgerEntry(connection, gmailMessageId);
        if (!outcome.created()) {
            return recordAttached(processed, resolvedAccount, outcome.attached());
        }

        Transaction txn = outcome.txn();
        if (eventPublisher != null) {
            eventPublisher.publishEvent(new com.financeos.domain.transaction.autolink.TransactionsIngestedEvent(user.getId(),
                    com.financeos.domain.transaction.autolink.LinkScope.of(txn.getDate(), txn.getDate())));
        }

        // 3. Record in ledger
        processed.setAccount(resolvedAccount);
        processed.setStatus(GmailProcessedStatus.CREATED);
        processed.setTransaction(txn);
        processed.setError(null);
        processed.setProcessedAt(Instant.now());

        return processedMessageRepository.save(processed);
    }

    /**
     * An alert that joined an existing transaction: the mail points at it and nothing is created. SKIPPED_DUPLICATE
     * ("Already imported"), since RECONCILED is what a statement mail records.
     */
    public GmailProcessedMessage recordAttached(GmailProcessedMessage processed, Account account, AlertMatcher.Attached attached) {
        processed.setAccount(account);
        processed.setStatus(GmailProcessedStatus.SKIPPED_DUPLICATE);
        processed.setTransaction(attached.txn());
        processed.setError(attached.detail());
        processed.setProcessedAt(Instant.now());
        if (attached.kind() == AlertMatcher.Kind.ISSUER_EMAIL || attached.kind() == AlertMatcher.Kind.PROVIDER_EMAIL) {
            processed.setExtractMergeTxn(attached.txn().getId());
            if (observabilityMetrics != null) {
                try {
                    observabilityMetrics.recordGmailExtractMerge(attached.kind() == AlertMatcher.Kind.ISSUER_EMAIL
                            ? "provider_after_issuer" : "issuer_after_provider");
                } catch (Exception e) {
                    org.slf4j.LoggerFactory.getLogger(GmailTransactionWriter.class).warn("Merge metric failed: {}", e.toString());
                }
            }
        }
        return processedMessageRepository.save(processed);
    }

    /** Hand-built writers (tests) have no matcher: the alert is created Pending, as the matcher would on no match. */
    private AlertMatcher.Outcome createUnmatched(AlertMatcher.Alert alert) {
        Transaction txn = new Transaction();
        txn.setUser(alert.user());
        txn.setAccount(alert.account());
        txn.setCard(alert.card());
        txn.setDate(alert.date());
        alert.applyAmounts(txn);
        txn.setSourcedDescription(alert.text());
        txn.setSource(TransactionSource.gmail_transaction_alert);
        txn.setType(alert.type());
        reviewStatusManager.transitionTo(txn, ReviewType.AUTO_REVIEWED);
        txn.setSourceMessageId(alert.gmailMessageId());
        txn.setTransactionUnderMonitoring(false);
        txn.setTransactionExcluded(false);
        return new AlertMatcher.Outcome(transactionRepository.save(txn), true, null);
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
