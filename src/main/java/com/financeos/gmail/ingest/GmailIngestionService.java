package com.financeos.gmail.ingest;

import com.financeos.core.observability.ObservabilityMetrics;
import com.financeos.core.security.UserContextHelper;
import com.financeos.core.time.AppTime;
import com.financeos.domain.account.Account;
import com.financeos.domain.categorization.CategorizationService;
import com.financeos.domain.transaction.Transaction;
import com.financeos.domain.transaction.TransactionRepository;
import com.financeos.gmail.classify.ClassificationResult;
import com.financeos.gmail.classify.EmailClassifier;
import com.financeos.gmail.domain.*;
import com.financeos.gmail.engine.GmailEngine;
import com.financeos.gmail.extract.ExtractedTransaction;
import com.financeos.gmail.extract.TransactionExtractor;
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
    private final ParkedMessageSettler parkedMessageSettler;

    /** Optional so tests that build the service by hand need no publisher. */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private org.springframework.context.ApplicationEventPublisher eventPublisher;

    /** Shadow classifier and its metrics: optional so tests that build the service by hand need neither. */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private EmailClassifier emailClassifier;

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private ObservabilityMetrics observabilityMetrics;

    /** Shadow offline extraction engine: null = off (tests that build the service by hand). */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private TransactionExtractor transactionExtractor;

    /** Senders (domain or subdomain) whose mails duplicate the issuer's alert for the same payment. */
    private static final Set<String> PROVIDER_SENDER_DOMAINS = Set.of("cred.club", "paytm.com", "amazonpay.in");
    private static final int MERGE_DATE_WINDOW_DAYS = 2;

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
                                 PlatformTransactionManager transactionManager,
                                 ParkedMessageSettler parkedMessageSettler) {
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
        this.parkedMessageSettler = parkedMessageSettler;
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
            return new SyncSummary(0, 0, 0, 0, 0, 0, 0, 0, 0L,
                    connection.getEmail(), 0, null, null, List.of(), 0, Map.of(), List.of(), 0);
        }

        // 1. Discovery phase
        Discovery discovery = discoverMessages(connection, enabledSenders);

        // 2. Stale PROCESSING sweep
        sweepStaleProcessing(connection);

        // 2b. Parked rows whose account now exists and that predate its window: settle without a refetch
        settleParkedMessages(userId);

        // 3. Processing phase
        return processPendingMessages(connection, enabledSenders, discovery);
    }

    /** What the discovery phase listed: new ledger rows, distinct ids Gmail returned, and the window searched. */
    private record Discovery(int newCount, int listed, Instant searchedSince, LocalDate backfilledTo) {}

    private Discovery discoverMessages(GmailConnection connection, List<GmailSender> enabledSenders) {
        List<GmailSyncCursor> cursors = cursorService.getOrSeedCursors(connection);
        if (cursors.isEmpty()) {
            return new Discovery(0, 0, null, null);
        }

        int totalDiscovered = 0;
        // Forward and backfill windows can overlap; count each message once.
        Set<String> listedIds = new HashSet<>();
        Instant searchedSince = null;
        LocalDate backfilledTo = null;

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
            listedIds.addAll(messageIds);
            Instant groupSince = Instant.ofEpochSecond(afterEpoch);
            if (searchedSince == null || groupSince.isBefore(searchedSince)) {
                searchedSince = groupSince;
            }

            cursorService.updateLastListedAt(groupCursors, fetchStart);
        }

        // Backfill Pass: check user demand floor
        UUID userId = connection.getUser().getId();
        var demandOpt = backfillDemandRepository.findById(userId);
        if (demandOpt.isPresent()) {
            LocalDate demandFloor = demandOpt.get().getFloorDate();
            LocalDate maxBackfillFloor = AppTime.today().minusDays(ingestProperties.getMaxBackfillDays());
            LocalDate effectiveFloor = demandFloor.isAfter(maxBackfillFloor) ? demandFloor : maxBackfillFloor;
            Instant floorInstant = effectiveFloor.atStartOfDay(AppTime.zone()).toInstant();

            List<GmailSyncCursor> deficitCursors = cursors.stream()
                    .filter(c -> c.getEarliestCoveredAt().isAfter(floorInstant))
                    .toList();

            if (!deficitCursors.isEmpty()) {
                Map<Instant, List<GmailSyncCursor>> backfillGroups = deficitCursors.stream()
                        .collect(Collectors.groupingBy(GmailSyncCursor::getEarliestCoveredAt));

                for (Map.Entry<Instant, List<GmailSyncCursor>> entry : backfillGroups.entrySet()) {
                    Instant earliestCovered = entry.getKey();
                    List<GmailSyncCursor> groupCursors = entry.getValue();

                    long floorEpoch = effectiveFloor.atStartOfDay(AppTime.zone()).toEpochSecond();
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
                    listedIds.addAll(messageIds);

                    cursorService.updateEarliestCoveredAt(groupCursors, floorInstant);
                }
                backfilledTo = effectiveFloor;
            }
        }

        return new Discovery(totalDiscovered, listedIds.size(), searchedSince, backfilledTo);
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

    private void settleParkedMessages(UUID userId) {
        try {
            parkedMessageSettler.settleAll(userId);
        } catch (Exception e) {
            // Housekeeping only: the sync itself must still run.
            log.warn("Settling parked Gmail rows failed for user {}: {}", userId, e.getMessage());
        }
    }

    private SyncSummary processPendingMessages(GmailConnection connection, List<GmailSender> enabledSenders, Discovery discovery) {
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
        List<SyncSummary.MessageResult> messageResults = new ArrayList<>();
        Map<GmailProcessedStatus, Integer> statusCounts = new EnumMap<>(GmailProcessedStatus.class);

        Gmail service = null;
        try {
            service = gmailEngine.createService(connection);
        } catch (Exception e) {
            log.warn("Failed to create single Gmail service instance for batch: {}", e.getMessage());
        }

        for (GmailProcessedMessage row : pendingBatch) {
            processedCount++;
            MergeRequest[] mergeOut = new MergeRequest[1];
            ProcessedMessage outcome = processSingleMessageInTx(service, connection, row, enabledSenders, mergeOut);
            mergeCheckInShadow(mergeOut[0]);
            if (!outcome.createdTxns().isEmpty()) {
                createdTxns.addAll(outcome.createdTxns());
            }
            SyncSummary.MessageResult result = outcome.result();
            messageResults.add(result);
            statusCounts.merge(result.status(), 1, Integer::sum);

            switch (result.status()) {
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
            try {
                categorizationService.batchCategorize(createdTxns);
            } finally {
                // Once per batch that wrote transactions, even if categorising failed (no transaction is
                // open here, so it fires at once).
                if (eventPublisher != null) {
                    eventPublisher.publishEvent(new com.financeos.domain.transaction.autolink.TransactionsIngestedEvent(connection.getUser().getId()));
                }
            }
        }

        long backlogRemaining = processedMessageRepository.countByConnectionIdAndStatusIn(
                connection.getId(),
                List.of(GmailProcessedStatus.DISCOVERED, GmailProcessedStatus.FAILED_RETRYABLE)
        );

        // Problems first, so a capped list still shows everything the user has to act on.
        messageResults.sort(Comparator.comparingInt(r -> displayRank(r.status())));
        int truncated = Math.max(0, messageResults.size() - SyncSummary.MAX_MESSAGES);
        List<SyncSummary.MessageResult> shown = truncated > 0
                ? List.copyOf(messageResults.subList(0, SyncSummary.MAX_MESSAGES))
                : List.copyOf(messageResults);

        List<String> senderAddresses = enabledSenders.stream()
                .map(GmailSender::getSenderAddress)
                .distinct()
                .toList();

        return new SyncSummary(
                discovery.newCount(),
                processedCount,
                createdCount,
                reconciledCount,
                skippedCount,
                parkedCount,
                failedRetryableCount,
                failedPermanentCount,
                backlogRemaining,
                connection.getEmail(),
                discovery.listed(),
                discovery.searchedSince(),
                discovery.backfilledTo(),
                senderAddresses,
                budget,
                statusCounts,
                shown,
                truncated
        );
    }

    private static int displayRank(GmailProcessedStatus status) {
        return switch (status) {
            case FAILED_PERMANENT -> 0;
            case FAILED_RETRYABLE -> 1;
            case UNRESOLVED_ACCOUNT, ACCOUNT_NOT_OPTED_IN -> 2;
            case CREATED, RECONCILED -> 3;
            default -> 4;
        };
    }

    /** One message's created transactions plus its run detail (built inside the transaction, while entities are attached). */
    private record ProcessedMessage(List<Transaction> createdTxns, SyncSummary.MessageResult result) {}

    private ProcessedMessage processSingleMessageInTx(Gmail service, GmailConnection connection, GmailProcessedMessage row, List<GmailSender> enabledSenders, MergeRequest[] mergeOut) {
        return transactionTemplate.execute(status -> {
            GmailProcessedMessage gpm = processedMessageRepository.findById(row.getId()).orElse(row);
            ReconSummary[] recon = new ReconSummary[1];
            List<Transaction> created = processMessage(service, connection, gpm, enabledSenders, recon, mergeOut);
            return new ProcessedMessage(created, toMessageResult(gpm, created, recon[0]));
        });
    }

    private SyncSummary.MessageResult toMessageResult(GmailProcessedMessage gpm, List<Transaction> created, ReconSummary recon) {
        Account account = gpm.getAccount();
        if (account == null && !created.isEmpty()) {
            account = created.get(0).getAccount();
        }
        List<Transaction> refs = created;
        if (refs.isEmpty() && gpm.getTransaction() != null) {
            refs = List.of(gpm.getTransaction());
        }
        List<SyncSummary.TxnRef> txnRefs = refs.stream()
                .limit(SyncSummary.MAX_TRANSACTIONS_PER_MESSAGE)
                .map(t -> new SyncSummary.TxnRef(
                        t.getId(),
                        t.getDate(),
                        t.getAmount(),
                        t.getDescription() != null ? t.getDescription() : t.getSourcedDescription(),
                        t.getType() != null ? t.getType().name() : null))
                .toList();
        String last4 = gpm.getExtractedLast4();
        if (last4 == null && recon != null) {
            last4 = recon.accountLast4();
        }
        String error = gpm.getError();
        if (error == null && recon != null) {
            error = recon.failureReason();
        }
        return new SyncSummary.MessageResult(
                gpm.getId(),
                gpm.getGmailMessageId(),
                gpm.getInternalDate(),
                gpm.getSenderAddress(),
                gpm.getSubject(),
                gpm.getStatus(),
                account != null ? account.getId() : null,
                account != null ? account.getName() : null,
                last4,
                recon != null ? recon.attachmentFilename() : null,
                error,
                gpm.getAttemptCount(),
                gpm.getNextRetryAt(),
                created.size(),
                recon != null ? recon.matched() : 0,
                txnRefs);
    }

    private List<Transaction> processMessage(Gmail service, GmailConnection connection, GmailProcessedMessage gpm,
                                             List<GmailSender> enabledSenders, ReconSummary[] reconOut,
                                             MergeRequest[] mergeOut) {
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
            classifyInShadow(gpm, message);
            clearExtractShadow(gpm);

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
                recordPipelineKind(gpm, "STATEMENT");
                ReconSummary recon = statementReconciliationService.reconcile(connection, message);
                reconOut[0] = recon;
                if (recon.createdTransactions() != null && !recon.createdTransactions().isEmpty()) {
                    return recon.createdTransactions();
                }
                return List.of();
            }

            // Transaction Alert path -> Gemini extraction
            GeminiExtractionResult extractionResult = geminiExtractor.extract(message);
            Optional<ExtractedTransaction> shadowEngine = extractInShadow(gpm, message, extractionResult);
            if (!extractionResult.isSuccess()) {
                mergeOut[0] = mergeRequest(gpm, connection.getUser().getId(), null, shadowEngine);
                throw new RuntimeException("Extraction failed: " + extractionResult.failureReason());
            }

            recordPipelineKind(gpm, extractionResult.isTransaction() ? "ALERT" : "OTHER");
            if (!extractionResult.isTransaction()) {
                mergeOut[0] = mergeRequest(gpm, connection.getUser().getId(), null, shadowEngine);
                gpm.setStatus(GmailProcessedStatus.SKIPPED_NOT_TRANSACTION);
                gpm.setError(null);
                processedMessageRepository.save(gpm);
                return List.of();
            }

            gpm.setExtractedLast4(normalizeLast4(extractionResult.accountLast4()));
            AccountResolver.ResolvedCard resolved = accountResolver.resolve(
                    extractionResult.accountLast4(), java.util.Arrays.asList(message.subject(), message.getStrippedText())).orElse(null);
            Account account = resolved != null ? resolved.account() : null;
            com.financeos.domain.account.card.Card card = resolved != null ? resolved.card() : null;
            mergeOut[0] = mergeRequest(gpm, connection.getUser().getId(), account, shadowEngine);

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
                    connection, message.messageId(), extractionResult, account, card);
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

            boolean permanent = e instanceof com.financeos.gmail.internal.GmailEngineException ge
                    && ge.getErrorType() == com.financeos.gmail.internal.GmailError.PERMANENT;
            if (!permanent && attempts < ingestProperties.getRetryMaxAttempts()) {
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
    }

    /** Shadow run: stores the classifier answer on the row; never changes the outcome, never throws. */
    void classifyInShadow(GmailProcessedMessage gpm, GmailMessage message) {
        // A retry must not keep the previous attempt's answer if this one throws.
        gpm.setPipelineKind(null);
        gpm.setClassifiedKind(null);
        gpm.setClassifiedSubtype(null);
        gpm.setClassifiedConfidence(null);
        gpm.setClassifiedBank(null);
        gpm.setClassifiedSignals(null);
        gpm.setClassifierVersion(null);
        if (emailClassifier == null) {
            return;
        }
        try {
            ClassificationResult c = emailClassifier.classify(message);
            gpm.setClassifiedKind(c.kind().name());
            gpm.setClassifiedSubtype(c.subtype().name());
            gpm.setClassifiedConfidence(c.confidence().name());
            gpm.setClassifiedBank(c.bankId());
            gpm.setClassifiedSignals(c.signalsJoined(500));
            gpm.setClassifierVersion(c.version());
            if (observabilityMetrics != null) {
                observabilityMetrics.recordGmailClassified(c.kind().name(), c.subtype().name(), c.confidence().name());
            }
            log.debug("Classified message {}: kind={} subtype={} confidence={} bank={} signals={}",
                    gpm.getGmailMessageId(), c.kind(), c.subtype(), c.confidence(), c.bankId(), c.signalsJoined(500));
        } catch (Exception e) {
            log.warn("Classifier failed for message {}: {}", gpm.getGmailMessageId(), e.toString());
        }
    }

    /**
     * Shadow run: the offline engine on the same message, compared with the AI's answer and stored on the row.
     * Never changes the outcome, never throws; returns what the engine produced (empty when off or failed).
     */
    /** A retry or early exit must not keep the previous attempt's shadow answer. */
    void clearExtractShadow(GmailProcessedMessage gpm) {
        gpm.setExtractTier(null);
        gpm.setExtractTemplate(null);
        gpm.setExtractVersion(null);
        gpm.setExtractAmount(null);
        gpm.setExtractCurrency(null);
        gpm.setExtractDirection(null);
        gpm.setExtractDate(null);
        gpm.setExtractLast4(null);
        gpm.setExtractCounterparty(null);
        gpm.setExtractMismatch(null);
        gpm.setExtractAiProvider(null);
        gpm.setExtractMergeTxn(null);
    }

    Optional<ExtractedTransaction> extractInShadow(GmailProcessedMessage gpm, GmailMessage message, GeminiExtractionResult ai) {
        if (transactionExtractor == null) {
            return Optional.empty();
        }
        try {
            Optional<ExtractedTransaction> engine = transactionExtractor.extract(message);
            gpm.setExtractVersion(TransactionExtractor.VERSION);
            gpm.setExtractAiProvider(truncate(ai.provider(), 64));
            String tier = "NONE";
            if (engine.isPresent()) {
                ExtractedTransaction e = engine.get();
                tier = e.tier().name();
                gpm.setExtractTemplate(truncate(e.templateId(), 64));
                gpm.setExtractAmount(e.amount());
                gpm.setExtractCurrency(truncate(e.currency(), 3));
                gpm.setExtractDirection(e.direction() == null ? null : e.direction().name());
                gpm.setExtractDate(e.date());
                gpm.setExtractLast4(truncate(e.last4(), 4));
                gpm.setExtractCounterparty(truncate(e.counterparty(), 255));
            }
            gpm.setExtractTier(tier);
            String mismatch = ExtractShadowComparator.mismatch(engine, ai);
            gpm.setExtractMismatch(truncate(mismatch, 100));
            if (observabilityMetrics != null) {
                try {
                    String provider = ai.provider() == null ? "none" : ai.provider().split("/", 2)[0];
                    observabilityMetrics.recordGmailExtractCompare(tier, ExtractShadowComparator.resultKind(engine, ai), provider);
                    if (engine.isPresent() && ai.isSuccess() && ai.isTransaction()) {
                        for (String field : ExtractShadowComparator.differingFields(engine.get(), ai)) {
                            observabilityMetrics.recordGmailExtractFieldMismatch(field);
                        }
                    }
                } catch (Exception me) {
                    log.warn("Shadow extraction metrics failed for message {}: {}", gpm.getGmailMessageId(), me.toString());
                }
            }
            log.debug("Shadow extraction for message {}: tier={} mismatch={}", gpm.getGmailMessageId(), tier, mismatch);
            return engine;
        } catch (Exception e) {
            log.warn("Shadow extraction failed for message {}: {}", gpm.getGmailMessageId(), e.toString());
            return Optional.empty();
        }
    }

    /** What the merge check needs, carried out of the message transaction. */
    record MergeRequest(UUID gpmId, UUID userId, UUID accountId, String gmailMessageId, String senderAddress,
                        java.math.BigDecimal amount, LocalDate date,
                        com.financeos.domain.transaction.TransactionType type) {}

    /** Null when the check does not apply (engine off, no engine result, or no date/amount/direction). */
    private MergeRequest mergeRequest(GmailProcessedMessage gpm, UUID userId, Account account, Optional<ExtractedTransaction> engine) {
        if (transactionExtractor == null || engine == null || engine.isEmpty()) {
            return null;
        }
        ExtractedTransaction e = engine.get();
        if (e.date() == null || e.amount() == null || e.direction() == null) {
            return null;
        }
        return new MergeRequest(gpm.getId(), userId, account == null ? null : account.getId(), gpm.getGmailMessageId(),
                gpm.getSenderAddress(), e.amount().abs(),
                e.date(), com.financeos.domain.transaction.TransactionType.valueOf(e.direction().name()));
    }

    /**
     * Shadow merge check, run after the message's own transaction has committed, in its own small transaction:
     * does this payment already exist as a transaction made by the other side's mail (issuer alert vs CRED /
     * Paytm / Amazon Pay)? Only records extract_merge_txn; nothing is skipped or merged. Never throws.
     */
    void mergeCheckInShadow(MergeRequest r) {
        if (r == null) {
            return;
        }
        try {
            String kind = transactionTemplate.execute(status -> {
                var from = r.date().minusDays(MERGE_DATE_WINDOW_DAYS);
                var to = r.date().plusDays(MERGE_DATE_WINDOW_DAYS);
                var candidates = r.accountId() != null
                        ? processedMessageRepository.findMergeCandidatesForAccount(r.userId(), r.accountId(),
                                r.gmailMessageId(), r.amount(), r.type(), from, to)
                        : processedMessageRepository.findMergeCandidates(r.userId(), r.gmailMessageId(),
                                r.amount(), r.type(), from, to);
                if (candidates == null) {
                    return null;
                }
                boolean thisIsProvider = isProviderSender(r.senderAddress());
                for (var c : candidates) {
                    if (isProviderSender(c.getSenderAddress()) != thisIsProvider) {
                        GmailProcessedMessage row = processedMessageRepository.findById(r.gpmId()).orElse(null);
                        if (row == null) {
                            return null;
                        }
                        row.setExtractMergeTxn(c.getTransactionId());
                        processedMessageRepository.save(row);
                        return thisIsProvider ? "provider_after_issuer" : "issuer_after_provider";
                    }
                }
                return null;
            });
            if (kind != null && observabilityMetrics != null) {
                observabilityMetrics.recordGmailExtractMerge(kind);
            }
        } catch (Exception ex) {
            log.warn("Shadow merge check failed for message {}: {}", r.gmailMessageId(), ex.toString());
        }
    }

    private static boolean isProviderSender(String address) {
        if (address == null) {
            return false;
        }
        int at = address.lastIndexOf('@');
        String domain = (at >= 0 ? address.substring(at + 1) : address).trim().toLowerCase(Locale.ROOT);
        return PROVIDER_SENDER_DOMAINS.stream().anyMatch(p -> domain.equals(p) || domain.endsWith("." + p));
    }

    /** What the existing pipeline decided, for comparison with the classifier; no effect on control flow. */
    private void recordPipelineKind(GmailProcessedMessage gpm, String pipelineKind) {
        gpm.setPipelineKind(pipelineKind);
        if (observabilityMetrics != null && gpm.getClassifiedKind() != null) {
            try {
                observabilityMetrics.recordGmailClassifierCompare(gpm.getClassifiedKind(), pipelineKind);
            } catch (Exception e) {
                log.warn("Classifier compare metric failed: {}", e.toString());
            }
        }
    }

    public static boolean hasStatementAttachment(GmailMessage message) {
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
