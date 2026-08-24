package com.financeos.gmail.ingest;

import com.financeos.core.security.UserContextHelper;
import com.financeos.domain.account.Account;
import com.financeos.domain.categorization.CategorizationService;
import com.financeos.domain.transaction.Transaction;
import com.financeos.domain.transaction.TransactionRepository;
import com.financeos.gmail.domain.*;
import com.financeos.gmail.engine.GmailEngine;
import com.financeos.gmail.ingest.gemini.GeminiExtractionResult;
import com.financeos.gmail.ingest.gemini.GeminiExtractor;
import com.financeos.gmail.internal.GmailAttachment;
import com.financeos.gmail.internal.GmailMessage;
import com.financeos.gmail.internal.MessageGoneException;
import com.financeos.gmail.reconcile.ReconSummary;
import com.financeos.gmail.reconcile.StatementReconciliationService;
import com.google.api.services.gmail.Gmail;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.*;
import java.util.stream.Collectors;

@Service
public class GmailIngestionService {

    private static final Logger log = LoggerFactory.getLogger(GmailIngestionService.class);
    private static final Set<String> STATEMENT_ATTACHMENT_EXTENSIONS = Set.of(".pdf", ".xlsx", ".xls");

    private final GmailEngine gmailEngine;
    private final GmailSyncCursorService cursorService;
    private final GmailSenderRepository gmailSenderRepository;
    private final AccountResolver accountResolver;
    private final GmailTransactionWriter gmailTransactionWriter;
    private final GeminiExtractor geminiExtractor;
    private final GmailProcessedMessageRepository processedMessageRepository;
    private final GmailBackfillDemandRepository backfillDemandRepository;
    private final GmailIngestProperties ingestProperties;
    private final StatementReconciliationService statementReconciliationService;
    private final CategorizationService categorizationService;
    private final TransactionRepository transactionRepository;
    private final TransactionTemplate transactionTemplate;

    public GmailIngestionService(GmailEngine gmailEngine,
                                 GmailSyncCursorService cursorService,
                                 GmailSenderRepository gmailSenderRepository,
                                 AccountResolver accountResolver,
                                 GmailTransactionWriter gmailTransactionWriter,
                                 GeminiExtractor geminiExtractor,
                                 GmailProcessedMessageRepository processedMessageRepository,
                                 GmailBackfillDemandRepository backfillDemandRepository,
                                 GmailIngestProperties ingestProperties,
                                 StatementReconciliationService statementReconciliationService,
                                 CategorizationService categorizationService,
                                 TransactionRepository transactionRepository,
                                 PlatformTransactionManager transactionManager) {
        this.gmailEngine = gmailEngine;
        this.cursorService = cursorService;
        this.gmailSenderRepository = gmailSenderRepository;
        this.accountResolver = accountResolver;
        this.gmailTransactionWriter = gmailTransactionWriter;
        this.geminiExtractor = geminiExtractor;
        this.processedMessageRepository = processedMessageRepository;
        this.backfillDemandRepository = backfillDemandRepository;
        this.ingestProperties = ingestProperties;
        this.statementReconciliationService = statementReconciliationService;
        this.categorizationService = categorizationService;
        this.transactionRepository = transactionRepository;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
    }

    public SyncSummary syncConnection(GmailConnection connection) {
        UUID userId = connection.getUser().getId();
        return UserContextHelper.callAs(userId, () -> syncConnectionInternal(connection));
    }

    private SyncSummary syncConnectionInternal(GmailConnection connection) {
        UUID userId = connection.getUser().getId();
        List<GmailSender> enabledSenders = gmailSenderRepository.findByUserIdAndEnabledTrue(userId);
        if (enabledSenders.isEmpty()) {
            log.info("No enabled Gmail senders configured for user: {}", userId);
            return new SyncSummary(0, 0, 0, 0, 0, 0, 0, 0, 0L);
        }

        // 1. Discovery phase
        int discoveredCount = discoverMessages(connection, enabledSenders);

        // 2. Stale PROCESSING sweep
        sweepStaleProcessing(connection);

        // 3. Processing phase
        return processPendingMessages(connection, enabledSenders, discoveredCount);
    }

    private int discoverMessages(GmailConnection connection, List<GmailSender> enabledSenders) {
        List<GmailSyncCursor> cursors = cursorService.getOrSeedCursors(connection);
        if (cursors.isEmpty()) {
            return 0;
        }

        int totalDiscovered = 0;

        // Forward Pass: group senders by identical lastListedAt
        Map<Instant, List<GmailSyncCursor>> forwardGroups = cursors.stream()
                .collect(Collectors.groupingBy(GmailSyncCursor::getLastListedAt));

        for (Map.Entry<Instant, List<GmailSyncCursor>> entry : forwardGroups.entrySet()) {
            Instant groupLastListedAt = entry.getKey();
            List<GmailSyncCursor> groupCursors = entry.getValue();

            Instant fetchStart = Instant.now();
            long overlapSeconds = Duration.ofMinutes(ingestProperties.getOverlapLapMinutes()).getSeconds();
            long afterEpoch = Math.max(0, groupLastListedAt.getEpochSecond() - overlapSeconds);

            String senderQuery = groupCursors.stream()
                    .map(c -> c.getSender().getSenderAddress())
                    .distinct()
                    .collect(Collectors.joining(" OR "));

            String query = String.format("from:(%s) after:%d", senderQuery, afterEpoch);
            log.info("Forward discovery listing for connection {} query: {}", connection.getEmail(), query);

            List<String> messageIds = gmailEngine.listMessageIds(connection, query);
            int newCount = saveDiscoveredMessageIds(connection, messageIds, fetchStart);
            totalDiscovered += newCount;

            cursorService.updateLastListedAt(groupCursors, fetchStart);
        }

        // Backfill Pass: check user demand floor
        UUID userId = connection.getUser().getId();
        var demandOpt = backfillDemandRepository.findById(userId);
        if (demandOpt.isPresent()) {
            LocalDate demandFloor = demandOpt.get().getFloorDate();
            LocalDate maxBackfillFloor = LocalDate.now().minusDays(ingestProperties.getMaxBackfillDays());
            LocalDate effectiveFloor = demandFloor.isAfter(maxBackfillFloor) ? demandFloor : maxBackfillFloor;
            Instant floorInstant = effectiveFloor.atStartOfDay(ZoneOffset.UTC).toInstant();

            List<GmailSyncCursor> deficitCursors = cursors.stream()
                    .filter(c -> c.getEarliestCoveredAt().isAfter(floorInstant))
                    .toList();

            if (!deficitCursors.isEmpty()) {
                Map<Instant, List<GmailSyncCursor>> backfillGroups = deficitCursors.stream()
                        .collect(Collectors.groupingBy(GmailSyncCursor::getEarliestCoveredAt));

                for (Map.Entry<Instant, List<GmailSyncCursor>> entry : backfillGroups.entrySet()) {
                    Instant earliestCovered = entry.getKey();
                    List<GmailSyncCursor> groupCursors = entry.getValue();

                    long floorEpoch = effectiveFloor.atStartOfDay(ZoneOffset.UTC).toEpochSecond();
                    long beforeEpoch = earliestCovered.plus(Duration.ofDays(1)).getEpochSecond();

                    String senderQuery = groupCursors.stream()
                            .map(c -> c.getSender().getSenderAddress())
                            .distinct()
                            .collect(Collectors.joining(" OR "));

                    String query = String.format("from:(%s) after:%d before:%d", senderQuery, floorEpoch, beforeEpoch);
                    log.info("Backfill discovery listing for connection {} query: {}", connection.getEmail(), query);

                    List<String> messageIds = gmailEngine.listMessageIds(connection, query);
                    int newCount = saveDiscoveredMessageIds(connection, messageIds, Instant.now());
                    totalDiscovered += newCount;

                    cursorService.updateEarliestCoveredAt(groupCursors, floorInstant);
                }
            }
        }

        return totalDiscovered;
    }

    private int saveDiscoveredMessageIds(GmailConnection connection, List<String> messageIds, Instant fetchStart) {
        if (messageIds.isEmpty()) {
            return 0;
        }

        Set<String> existingSet = new HashSet<>();
        int chunkSize = 500;
        for (int i = 0; i < messageIds.size(); i += chunkSize) {
            List<String> chunk = messageIds.subList(i, Math.min(i + chunkSize, messageIds.size()));
            List<String> existingChunk = processedMessageRepository.findExistingMessageIds(connection.getId(), chunk);
            existingSet.addAll(existingChunk);
        }

        List<GmailProcessedMessage> newRows = new ArrayList<>();
        for (String msgId : messageIds) {
            if (!existingSet.contains(msgId)) {
                GmailProcessedMessage gpm = new GmailProcessedMessage();
                gpm.setConnection(connection);
                gpm.setUser(connection.getUser());
                gpm.setGmailMessageId(msgId);
                gpm.setStatus(GmailProcessedStatus.DISCOVERED);
                gpm.setDiscoveredAt(fetchStart);
                newRows.add(gpm);
                existingSet.add(msgId); // prevent duplicate in same batch
            }
        }

        if (!newRows.isEmpty()) {
            processedMessageRepository.saveAll(newRows);
        }
        return newRows.size();
    }

    private void sweepStaleProcessing(GmailConnection connection) {
        Instant cutoff = Instant.now().minus(Duration.ofMinutes(ingestProperties.getStaleProcessingMinutes()));
        List<GmailProcessedMessage> staleRows = processedMessageRepository.findStaleProcessing(connection.getId(), cutoff);
        if (!staleRows.isEmpty()) {
            log.info("Sweeping {} stale PROCESSING rows back to DISCOVERED for connection {}", staleRows.size(), connection.getId());
            for (GmailProcessedMessage row : staleRows) {
                row.setStatus(GmailProcessedStatus.DISCOVERED);
            }
            processedMessageRepository.saveAll(staleRows);
        }
    }

    private SyncSummary processPendingMessages(GmailConnection connection, List<GmailSender> enabledSenders, int discoveredInRun) {
        long pendingTotal = processedMessageRepository.countByConnectionIdAndStatusIn(
                connection.getId(),
                List.of(GmailProcessedStatus.DISCOVERED, GmailProcessedStatus.FAILED_RETRYABLE)
        );

        int budget = pendingTotal > ingestProperties.getProcessBudget()
                ? ingestProperties.getBackfillProcessBudget()
                : ingestProperties.getProcessBudget();

        List<GmailProcessedMessage> pendingBatch = processedMessageRepository.findPendingForDrain(
                connection.getId(),
                Instant.now(),
                PageRequest.of(0, budget)
        );

        int processedCount = 0;
        int createdCount = 0;
        int reconciledCount = 0;
        int skippedCount = 0;
        int parkedCount = 0;
        int failedRetryableCount = 0;
        int failedPermanentCount = 0;

        List<Transaction> createdTxns = new ArrayList<>();

        Gmail service = null;
        try {
            service = gmailEngine.createService(connection);
        } catch (Exception e) {
            log.warn("Failed to create single Gmail service instance for batch: {}", e.getMessage());
        }

        for (GmailProcessedMessage row : pendingBatch) {
            processedCount++;
            List<Transaction> singleCreatedTxns = processSingleMessageInTx(service, connection, row, enabledSenders);
            if (singleCreatedTxns != null && !singleCreatedTxns.isEmpty()) {
                createdTxns.addAll(singleCreatedTxns);
            }

            // Fetch refreshed status to increment counters
            GmailProcessedMessage refreshed = processedMessageRepository.findById(row.getId()).orElse(row);
            switch (refreshed.getStatus()) {
                case CREATED -> createdCount++;
                case RECONCILED -> reconciledCount++;
                case SKIPPED_NOT_TRANSACTION, SKIPPED_BEFORE_WATERMARK, SKIPPED_SENDER_DISABLED, SKIPPED_DUPLICATE, SKIPPED_MESSAGE_GONE -> skippedCount++;
                case UNRESOLVED_ACCOUNT, ACCOUNT_NOT_OPTED_IN -> parkedCount++;
                case FAILED_RETRYABLE -> failedRetryableCount++;
                case FAILED_PERMANENT -> failedPermanentCount++;
                default -> {}
            }
        }

        if (!createdTxns.isEmpty()) {
            categorizationService.batchCategorize(createdTxns);
        }

        long backlogRemaining = processedMessageRepository.countByConnectionIdAndStatusIn(
                connection.getId(),
                List.of(GmailProcessedStatus.DISCOVERED, GmailProcessedStatus.FAILED_RETRYABLE)
        );

        return new SyncSummary(
                discoveredInRun,
                processedCount,
                createdCount,
                reconciledCount,
                skippedCount,
                parkedCount,
                failedRetryableCount,
                failedPermanentCount,
                backlogRemaining
        );
    }

    private List<Transaction> processSingleMessageInTx(Gmail service, GmailConnection connection, GmailProcessedMessage row, List<GmailSender> enabledSenders) {
        return transactionTemplate.execute(status -> {
            GmailProcessedMessage gpm = processedMessageRepository.findById(row.getId()).orElse(row);
            gpm.setStatus(GmailProcessedStatus.PROCESSING);
            gpm.setProcessedAt(Instant.now());
            processedMessageRepository.save(gpm);

            try {
                GmailMessage message;
                try {
                    message = service != null
                            ? gmailEngine.fetchMessageDetails(service, gpm.getGmailMessageId())
                            : gmailEngine.fetchMessageDetails(connection, gpm.getGmailMessageId());
                } catch (MessageGoneException e) {
                    gpm.setStatus(GmailProcessedStatus.SKIPPED_MESSAGE_GONE);
                    gpm.setError(null);
                    processedMessageRepository.save(gpm);
                    return List.of();
                }

                gpm.setInternalDate(message.internalDate());
                gpm.setSubject(truncate(message.subject(), 500));
                String fromAddress = extractEmailAddress(message.from());
                gpm.setSenderAddress(fromAddress);

                // Allowlist check
                GmailSender sender = findMatchingSender(enabledSenders, fromAddress);
                if (sender == null) {
                    gpm.setStatus(GmailProcessedStatus.SKIPPED_SENDER_DISABLED);
                    gpm.setError(null);
                    processedMessageRepository.save(gpm);
                    return List.of();
                }

                // Duplicate guard across user transactions
                if (transactionRepository.existsByUserIdAndSourceMessageId(connection.getUser().getId(), message.messageId())) {
                    gpm.setStatus(GmailProcessedStatus.SKIPPED_DUPLICATE);
                    gpm.setError(null);
                    processedMessageRepository.save(gpm);
                    return List.of();
                }

                // Statement path check
                if (hasStatementAttachment(message)) {
                    ReconSummary recon = statementReconciliationService.reconcile(connection, message);
                    if (recon.createdTransactions() != null && !recon.createdTransactions().isEmpty()) {
                        return recon.createdTransactions();
                    }
                    return List.of();
                }

                // Transaction Alert path -> Gemini extraction
                GeminiExtractionResult extractionResult = geminiExtractor.extract(message);
                if (!extractionResult.isSuccess()) {
                    throw new RuntimeException("Extraction failed: " + extractionResult.failureReason());
                }

                if (!extractionResult.isTransaction()) {
                    gpm.setStatus(GmailProcessedStatus.SKIPPED_NOT_TRANSACTION);
                    gpm.setError(null);
                    processedMessageRepository.save(gpm);
                    return List.of();
                }

                gpm.setExtractedLast4(normalizeLast4(extractionResult.accountLast4()));
                Account account = accountResolver.resolve(extractionResult.accountLast4()).orElse(null);

                if (account == null) {
                    gpm.setStatus(GmailProcessedStatus.UNRESOLVED_ACCOUNT);
                    gpm.setError("No single account matches card/account ending " + extractionResult.accountLast4());
                    processedMessageRepository.save(gpm);
                    return List.of();
                }

                gpm.setAccount(account);

                if (account.getIngestFromDate() == null) {
                    gpm.setStatus(GmailProcessedStatus.ACCOUNT_NOT_OPTED_IN);
                    gpm.setError("Account " + account.getName() + " is not opted in for Gmail ingestion");
                    processedMessageRepository.save(gpm);
                    return List.of();
                }

                // Write transaction (checks ingestFromDate watermark inside writer)
                GmailProcessedMessage result = gmailTransactionWriter.writeTransaction(
                        connection, message.messageId(), extractionResult, account);
                gpm.setStatus(result.getStatus());
                gpm.setTransaction(result.getTransaction());
                gpm.setError(result.getError());
                processedMessageRepository.save(gpm);

                if (result.getTransaction() != null) {
                    return List.of(result.getTransaction());
                }
                return List.of();

            } catch (Exception e) {
                log.warn("Failed processing message {}: {}", gpm.getGmailMessageId(), e.getMessage());
                int attempts = gpm.getAttemptCount() + 1;
                gpm.setAttemptCount(attempts);
                boolean isNoKeys = (e instanceof com.financeos.llm.LlmException le && le.getKind() == com.financeos.llm.LlmException.Kind.NO_KEYS)
                        || (e.getMessage() != null && e.getMessage().contains("No API keys configured"));
                if (isNoKeys) {
                    gpm.setError("needs attention: add an API key in Settings");
                } else {
                    gpm.setError(truncate(e.getMessage() != null ? e.getMessage() : e.toString(), 2000));
                }

                if (attempts < ingestProperties.getRetryMaxAttempts()) {
                    gpm.setStatus(GmailProcessedStatus.FAILED_RETRYABLE);
                    long backoffSeconds = Math.min(86400L, 7200L * (1L << (attempts - 1)));
                    gpm.setNextRetryAt(Instant.now().plusSeconds(backoffSeconds));
                } else {
                    gpm.setStatus(GmailProcessedStatus.FAILED_PERMANENT);
                    gpm.setNextRetryAt(null);
                }
                processedMessageRepository.save(gpm);
                return List.of();
            }
        });
    }

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

    private String normalizeLast4(String raw) {
        if (raw == null) return null;
        String trimmed = raw.trim();
        if (trimmed.length() > 4) {
            return trimmed.substring(trimmed.length() - 4);
        }
        return trimmed;
    }

    private String truncate(String input, int maxLength) {
        if (input == null) return null;
        return input.length() <= maxLength ? input : input.substring(0, maxLength);
    }
}
