package com.financeos.gmail.ingest;

import com.financeos.core.security.UserContextHelper;
import com.financeos.domain.account.Account;
import com.financeos.gmail.domain.GmailConnection;
import com.financeos.gmail.domain.GmailProcessedMessage;
import com.financeos.gmail.domain.GmailProcessedMessageRepository;
import com.financeos.gmail.domain.GmailProcessedStatus;
import com.financeos.gmail.engine.GmailEngine;
import com.financeos.gmail.history.SyncStateService;
import com.financeos.gmail.internal.FetchMode;
import com.financeos.gmail.internal.GmailFetchRequest;
import com.financeos.gmail.internal.GmailFetchResult;
import com.financeos.gmail.internal.GmailMessage;
import com.financeos.gmail.ingest.gemini.GeminiExtractionResult;
import com.financeos.gmail.ingest.gemini.GeminiExtractor;
import com.financeos.gmail.reconcile.StatementReconciliationService;
import com.financeos.gmail.reconcile.ReconSummary;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import com.financeos.domain.categorization.CategorizationService;
import com.financeos.domain.transaction.Transaction;
import java.util.ArrayList;

@Service
public class GmailIngestionService {

    private static final Logger log = LoggerFactory.getLogger(GmailIngestionService.class);

    private final GmailEngine gmailEngine;
    private final SyncStateService syncStateService;
    private final GmailSenderRepository gmailSenderRepository;
    private final AccountResolver accountResolver;
    private final GmailTransactionWriter gmailTransactionWriter;
    private final GeminiExtractor geminiExtractor;
    private final GmailProcessedMessageRepository processedMessageRepository;
    private final GmailIngestProperties ingestProperties;
    private final StatementReconciliationService statementReconciliationService;
    private final CategorizationService categorizationService;

    public GmailIngestionService(GmailEngine gmailEngine,
                                 SyncStateService syncStateService,
                                 GmailSenderRepository gmailSenderRepository,
                                 AccountResolver accountResolver,
                                 GmailTransactionWriter gmailTransactionWriter,
                                 GeminiExtractor geminiExtractor,
                                 GmailProcessedMessageRepository processedMessageRepository,
                                 GmailIngestProperties ingestProperties,
                                 StatementReconciliationService statementReconciliationService,
                                 CategorizationService categorizationService) {
        this.gmailEngine = gmailEngine;
        this.syncStateService = syncStateService;
        this.gmailSenderRepository = gmailSenderRepository;
        this.accountResolver = accountResolver;
        this.gmailTransactionWriter = gmailTransactionWriter;
        this.geminiExtractor = geminiExtractor;
        this.processedMessageRepository = processedMessageRepository;
        this.ingestProperties = ingestProperties;
        this.statementReconciliationService = statementReconciliationService;
        this.categorizationService = categorizationService;
    }


    /**
     * Run sync and ingestion for a specific connection.
     * Enforces user tenancy context dynamically.
     */
    public SyncSummary syncConnection(GmailConnection connection) {
        UUID userId = connection.getUser().getId();
        return UserContextHelper.callAs(userId, () -> syncConnectionInternal(connection));
    }

    private SyncSummary syncConnectionInternal(GmailConnection connection) {
        UUID userId = connection.getUser().getId();
        
        // 1. Get allowlisted senders for user
        List<GmailSender> senders = gmailSenderRepository.findByUserIdAndEnabledTrue(userId);
        if (senders.isEmpty()) {
            log.info("No enabled Gmail senders configured for user: {}", userId);
            return new SyncSummary(0, 0, 0, 0, 0);
        }

        // 2. Build the query: "from:(s1 OR s2) after:<epoch>"
        String senderQuery = senders.stream()
                .map(GmailSender::getSenderAddress)
                .collect(Collectors.joining(" OR "));
        
        var syncState = syncStateService.getSyncState(connection.getId());
        Instant lastSyncedAt = syncState != null ? syncState.lastSyncedAt() : null;
        
        long epochSeconds;
        if (lastSyncedAt != null) {
            epochSeconds = lastSyncedAt.getEpochSecond();
        } else {
            epochSeconds = Instant.now()
                    .minus(Duration.ofDays(ingestProperties.getFirstBackfillDays()))
                    .getEpochSecond();
        }
        
        String query = String.format("from:(%s) after:%d", senderQuery, epochSeconds);
        log.info("Starting Gmail sync for connection: {} using query: {}", connection.getEmail(), query);

        // 3. Fetch from Gmail Engine
        GmailFetchRequest fetchRequest = new GmailFetchRequest(FetchMode.MANUAL, null, 100, query);
        GmailFetchResult fetchResult = gmailEngine.fetch(connection, fetchRequest);
        
        int fetched = fetchResult.messages().size();
        int created = 0;
        int skipped = 0;
        int failed = 0;
        int reconciled = 0;
        int alreadyProcessed = 0;
        int nonTransaction = 0;
        int attentionTruncated = 0;
        List<SyncSummary.MessageOutcome> attention = new ArrayList<>();
        List<Transaction> createdTxns = new ArrayList<>();

        // 4. Process each message
        for (GmailMessage message : fetchResult.messages()) {
            try {
                // Check if message is already in processed ledger. Every persisted entry is
                // terminal and account-scoped; failures are not persisted, so absence = retryable.
                var processedOpt = processedMessageRepository.findByConnectionIdAndGmailMessageId(
                        connection.getId(), message.messageId());
                if (processedOpt.isPresent()) {
                    skipped++;
                    alreadyProcessed++;
                    continue;
                }

                // Resolve sender details from allowlist
                String fromAddress = extractEmailAddress(message.from());
                GmailSender sender = findMatchingSender(senders, fromAddress);
                if (sender == null) {
                    log.debug("Skipping message {}: sender not in allowlist: {}", message.messageId(), fromAddress);
                    skipped++;
                    continue;
                }

                // Route Statement vs. Alert by a zero-cost attachment heuristic instead of an LLM call.
                // Statements arrive as PDF/Excel attachments; anything else is treated as a transaction
                // alert, and the extractor itself filters out non-transactions (returns notTransaction()).
                if (hasStatementAttachment(message)) {
                    ReconSummary recon = statementReconciliationService.reconcile(connection, message);
                    created += recon.created();
                    reconciled += recon.matched();
                    failed += recon.failed();
                    // Categorized after the loop alongside alert transactions, outside reconcile's tx
                    createdTxns.addAll(recon.createdTransactions());
                    if (recon.failed() > 0 && recon.failureOutcome() != null) {
                        if (attention.size() < 50) {
                            addOutcome(attention, message, recon.attachmentFilename(), recon.failureOutcome(), recon.failureReason(), recon.accountLast4());
                        } else {
                            attentionTruncated++;
                        }
                    }
                    if (recon.emptyStatement()) {
                        nonTransaction++;
                    }
                    continue;
                }

                // TRANSACTION_ALERT purpose -> extract details via Gemini Flash
                GeminiExtractionResult extractionResult = geminiExtractor.extract(message);
                if (!extractionResult.isSuccess()) {
                    log.warn("Extraction failed for message {}: {}", message.messageId(), extractionResult.failureReason());
                    failed++;
                    if (attention.size() < 50) {
                        addOutcome(attention, message, null, SyncSummary.Outcome.EXTRACTION_FAILED, extractionResult.failureReason(), null);
                    } else {
                        attentionTruncated++;
                    }
                    continue;
                }

                if (!extractionResult.isTransaction()) {
                    log.debug("Skipping message {}: Gemini classified as non-transaction", message.messageId());
                    skipped++;
                    nonTransaction++;
                    continue;
                }

                // Resolve account by the last4 extracted from the email body (exactly-one rule)
                Account account = accountResolver.resolve(extractionResult.accountLast4()).orElse(null);

                if (account == null) {
                    log.warn("Could not resolve account for transaction (last4: {}, sender: {}). Ingestion failed.",
                            extractionResult.accountLast4(), sender.getSenderAddress());
                    failed++;
                    String reason = "No single account matches card/account ending " + extractionResult.accountLast4();
                    if (attention.size() < 50) {
                        addOutcome(attention, message, null, SyncSummary.Outcome.ACCOUNT_UNRESOLVED, reason, extractionResult.accountLast4());
                    } else {
                        attentionTruncated++;
                    }
                    continue;
                }


                // Write transaction (includes watermark check)
                GmailProcessedMessage processed = gmailTransactionWriter.writeTransaction(
                        connection, message.messageId(), extractionResult, account);

                if (processed.getStatus() == GmailProcessedStatus.CREATED) {
                    created++;
                    if (processed.getTransaction() != null) {
                        createdTxns.add(processed.getTransaction());
                    }
                } else if (processed.getStatus() == GmailProcessedStatus.SKIPPED_BEFORE_WATERMARK) {
                    skipped++;
                } else {
                    failed++;
                    if (attention.size() < 50) {
                        addOutcome(attention, message, null, SyncSummary.Outcome.ERROR, "Transaction write failed", null);
                    } else {
                        attentionTruncated++;
                    }
                }

            } catch (Exception e) {
                log.warn("Failed to process message: " + message.messageId(), e);
                failed++;
                String reason = e.getMessage() != null ? e.getMessage() : e.toString();
                if (attention.size() < 50) {
                    addOutcome(attention, message, null, SyncSummary.Outcome.ERROR, reason, null);
                } else {
                    attentionTruncated++;
                }
            }
        }

        if (!createdTxns.isEmpty()) {
            categorizationService.batchCategorize(createdTxns);
        }

        // 5. Advance watermark only after successful batch processing (durable cursor)
        syncStateService.saveSyncState(connection, fetchResult.nextState().historyId(), fetchResult.nextState().lastSyncedAt());

        return new SyncSummary(fetched, created, skipped, failed, reconciled, alreadyProcessed, nonTransaction, attention, attentionTruncated);
    }

    private void addOutcome(List<SyncSummary.MessageOutcome> attention, GmailMessage message, String attachmentFilename,
                            SyncSummary.Outcome outcome, String reason, String accountLast4) {
        String truncatedReason = reason;
        if (truncatedReason != null && truncatedReason.length() > 500) {
            truncatedReason = truncatedReason.substring(0, 500);
        }
        String receivedAt = message.internalDate() != null ? message.internalDate().toString() : null;
        attention.add(new SyncSummary.MessageOutcome(
                message.messageId(),
                message.from(),
                message.subject(),
                receivedAt,
                attachmentFilename,
                outcome,
                truncatedReason,
                accountLast4
        ));
    }


    private static final Set<String> STATEMENT_ATTACHMENT_EXTENSIONS = Set.of(".pdf", ".xlsx", ".xls");

    /**
     * Heuristic route: an email is treated as a statement (vs. a transaction alert) when it carries a
     * PDF/Excel attachment. This replaces a per-email Gemini classification call. Note this mirrors the
     * attachment selection in StatementReconciliationService#pickStatementAttachment; keep them in sync.
     */
    private boolean hasStatementAttachment(GmailMessage message) {
        if (message.attachments() == null) {
            return false;
        }
        return message.attachments().stream()
                .map(att -> att.filename() == null ? "" : att.filename().toLowerCase())
                .anyMatch(name -> STATEMENT_ATTACHMENT_EXTENSIONS.stream().anyMatch(name::endsWith));
    }

    private String extractEmailAddress(String fromHeader) {
        if (fromHeader == null) {
            return "";
        }
        int startIdx = fromHeader.indexOf('<');
        int endIdx = fromHeader.indexOf('>');
        if (startIdx != -1 && endIdx != -1 && endIdx > startIdx) {
            return fromHeader.substring(startIdx + 1, endIdx).trim().toLowerCase();
        }
        return fromHeader.trim().toLowerCase();
    }

    private GmailSender findMatchingSender(List<GmailSender> senders, String emailAddress) {
        return senders.stream()
                .filter(s -> {
                    String allowAddr = s.getSenderAddress().trim().toLowerCase();
                    if (!allowAddr.contains("@")) {
                        return emailAddress.endsWith("@" + allowAddr) || emailAddress.endsWith("." + allowAddr);
                    }
                    return allowAddr.equalsIgnoreCase(emailAddress);
                })
                .findFirst()
                .orElse(null);
    }
}
