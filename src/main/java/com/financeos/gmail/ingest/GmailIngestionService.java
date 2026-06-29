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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

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

    public GmailIngestionService(GmailEngine gmailEngine,
                                 SyncStateService syncStateService,
                                 GmailSenderRepository gmailSenderRepository,
                                 AccountResolver accountResolver,
                                 GmailTransactionWriter gmailTransactionWriter,
                                 GeminiExtractor geminiExtractor,
                                 GmailProcessedMessageRepository processedMessageRepository,
                                 GmailIngestProperties ingestProperties) {
        this.gmailEngine = gmailEngine;
        this.syncStateService = syncStateService;
        this.gmailSenderRepository = gmailSenderRepository;
        this.accountResolver = accountResolver;
        this.gmailTransactionWriter = gmailTransactionWriter;
        this.geminiExtractor = geminiExtractor;
        this.processedMessageRepository = processedMessageRepository;
        this.ingestProperties = ingestProperties;
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

        // 4. Process each message
        for (GmailMessage message : fetchResult.messages()) {
            try {
                // Check if message is already in processed ledger (terminal/non-FAILED state)
                var processedOpt = processedMessageRepository.findByConnectionIdAndGmailMessageId(
                        connection.getId(), message.messageId());
                if (processedOpt.isPresent() && processedOpt.get().getStatus() != GmailProcessedStatus.FAILED) {
                    skipped++;
                    continue;
                }

                // Resolve sender details from allowlist
                String fromAddress = extractEmailAddress(message.from());
                GmailSender sender = findMatchingSender(senders, fromAddress);
                if (sender == null) {
                    gmailTransactionWriter.recordSkipped(connection, message.messageId(), 
                            GmailProcessedStatus.SKIPPED_NOT_TRANSACTION, "Sender not in allowlist: " + fromAddress);
                    skipped++;
                    continue;
                }

                if (sender.getPurpose() == SenderPurpose.STATEMENT) {
                    // M4 reconciliation logic goes here. For M2/M3, we skip statement emails.
                    gmailTransactionWriter.recordSkipped(connection, message.messageId(),
                            GmailProcessedStatus.SKIPPED_NOT_TRANSACTION, "Statement emails reconciliation deferred to M4");
                    skipped++;
                    continue;
                }

                // TRANSACTION_ALERT purpose -> extract details via Gemini Flash
                GeminiExtractionResult extractionResult = geminiExtractor.extract(message);
                if (!extractionResult.isSuccess()) {
                    gmailTransactionWriter.recordSkipped(connection, message.messageId(), 
                            GmailProcessedStatus.FAILED, extractionResult.failureReason());
                    failed++;
                    continue;
                }

                if (!extractionResult.isTransaction()) {
                    gmailTransactionWriter.recordSkipped(connection, message.messageId(), 
                            GmailProcessedStatus.SKIPPED_NOT_TRANSACTION, "Gemini classified as non-transaction");
                    skipped++;
                    continue;
                }

                // Resolve account
                Account account = accountResolver.resolve(extractionResult.accountLast4(), sender).orElse(null);

                // Write transaction (includes watermark check)
                GmailProcessedMessage processed = gmailTransactionWriter.writeTransaction(
                        connection, message.messageId(), extractionResult, account);
                
                if (processed.getStatus() == GmailProcessedStatus.CREATED) {
                    created++;
                } else if (processed.getStatus() == GmailProcessedStatus.SKIPPED_BEFORE_WATERMARK) {
                    skipped++;
                } else {
                    failed++;
                }

            } catch (Exception e) {
                log.error("Failed to process message: " + message.messageId(), e);
                try {
                    gmailTransactionWriter.recordSkipped(connection, message.messageId(), 
                            GmailProcessedStatus.FAILED, "Internal error: " + e.getMessage());
                } catch (Exception ex) {
                    log.error("Failed to record failure in processed ledger", ex);
                }
                failed++;
            }
        }

        // 5. Advance watermark only after successful batch processing (durable cursor)
        syncStateService.saveSyncState(connection, fetchResult.nextState().historyId(), fetchResult.nextState().lastSyncedAt());

        return new SyncSummary(fetched, created, skipped, failed, reconciled);
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
