package com.financeos.api.gmail;

import com.financeos.api.gmail.dto.*;
import com.financeos.api.job.dto.EnqueueResponse;
import com.financeos.core.exception.ResourceNotFoundException;
import com.financeos.core.exception.ValidationException;
import com.financeos.domain.account.Account;
import com.financeos.domain.account.AccountRepository;
import com.financeos.domain.job.Job;
import com.financeos.domain.job.JobService;
import com.financeos.domain.job.JobTrigger;
import com.financeos.domain.job.JobType;
import com.financeos.domain.job.handlers.GmailSyncPayload;
import com.financeos.domain.transaction.Transaction;
import com.financeos.domain.transaction.TransactionRepository;
import com.financeos.domain.user.AuthService;
import com.financeos.domain.user.User;
import com.financeos.gmail.domain.*;
import com.financeos.gmail.ingest.GmailIngestProperties;
import com.financeos.gmail.ingest.GmailIngestionService;
import com.financeos.gmail.ingest.SenderAllowlistService;
import com.financeos.gmail.oauth.GmailOAuthService;
import jakarta.validation.Valid;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.util.*;

@RestController
public class GmailController {

    private static final Logger log = LoggerFactory.getLogger(GmailController.class);

    private final GmailOAuthService oauthService;
    private final AuthService authService;
    private final GmailIngestionService gmailIngestionService;
    private final SenderAllowlistService senderAllowlistService;
    private final GmailConnectionRepository connectionRepository;
    private final GmailSyncCursorRepository syncCursorRepository;
    private final GmailProcessedMessageRepository processedMessageRepository;
    private final GmailBackfillDemandRepository backfillDemandRepository;
    private final AccountRepository accountRepository;
    private final TransactionRepository transactionRepository;
    private final JobService jobService;
    private final GmailIngestProperties ingestProperties;

    @Value("${app.ui-path:http://localhost:3001}")
    private String uiPath;

    public GmailController(GmailOAuthService oauthService,
                           AuthService authService,
                           GmailIngestionService gmailIngestionService,
                           SenderAllowlistService senderAllowlistService,
                           GmailConnectionRepository connectionRepository,
                           GmailSyncCursorRepository syncCursorRepository,
                           GmailProcessedMessageRepository processedMessageRepository,
                           GmailBackfillDemandRepository backfillDemandRepository,
                           AccountRepository accountRepository,
                           TransactionRepository transactionRepository,
                           JobService jobService,
                           GmailIngestProperties ingestProperties) {
        this.oauthService = oauthService;
        this.authService = authService;
        this.gmailIngestionService = gmailIngestionService;
        this.senderAllowlistService = senderAllowlistService;
        this.connectionRepository = connectionRepository;
        this.syncCursorRepository = syncCursorRepository;
        this.processedMessageRepository = processedMessageRepository;
        this.backfillDemandRepository = backfillDemandRepository;
        this.accountRepository = accountRepository;
        this.transactionRepository = transactionRepository;
        this.jobService = jobService;
        this.ingestProperties = ingestProperties;
    }

    @GetMapping("/api/v1/gmail/oauth/start")
    public ResponseEntity<OAuthStartResponse> startOAuth() {
        User currentUser = authService.getCurrentUser();
        String authUrl = oauthService.buildAuthorizationUrl(currentUser.getId());
        return ResponseEntity.ok(new OAuthStartResponse(authUrl));
    }

    @GetMapping("/api/v1/gmail/oauth/callback")
    public ResponseEntity<Void> handleCallback(
            @RequestParam(required = false) String code,
            @RequestParam(required = false) String error,
            @RequestParam(required = false) String state) {

        if (error != null) {
            String redirectUrl = uiPath + "/settings/gmail?gmail=error&message=" + URLEncoder.encode(error, StandardCharsets.UTF_8);
            return ResponseEntity.status(HttpStatus.FOUND).location(URI.create(redirectUrl)).build();
        }

        if (code == null) {
            String redirectUrl = uiPath + "/settings/gmail?gmail=error&message=" + URLEncoder.encode("missing_code", StandardCharsets.UTF_8);
            return ResponseEntity.status(HttpStatus.FOUND).location(URI.create(redirectUrl)).build();
        }

        try {
            User currentUser = authService.getCurrentUser();
            GmailConnection connection = oauthService.handleCallback(currentUser.getId(), code);

            String redirectUrl = uiPath + "/settings/gmail?gmail=success&email=" + URLEncoder.encode(connection.getEmail(), StandardCharsets.UTF_8);
            return ResponseEntity.status(HttpStatus.FOUND).location(URI.create(redirectUrl)).build();
        } catch (Exception e) {
            String redirectUrl = uiPath + "/settings/gmail?gmail=error&message=" + URLEncoder.encode(e.getMessage() != null ? e.getMessage() : "callback_failed", StandardCharsets.UTF_8);
            return ResponseEntity.status(HttpStatus.FOUND).location(URI.create(redirectUrl)).build();
        }
    }

    @PostMapping("/api/v1/gmail/sync")
    public ResponseEntity<EnqueueResponse> syncEmails() {
        User currentUser = authService.getCurrentUser();
        GmailConnection connection = oauthService.getConnection(currentUser.getId());

        if (!connection.getIsConnected()) {
            return ResponseEntity.badRequest().build();
        }

        Job job = jobService.enqueue(
                currentUser.getId(),
                JobType.GMAIL_SYNC,
                JobTrigger.USER,
                new GmailSyncPayload(connection.getId()),
                null,
                connection.getId().toString()
        );

        return ResponseEntity.accepted().body(new EnqueueResponse(job.getId()));
    }

    @GetMapping("/api/v1/gmail/connections")
    public ResponseEntity<List<GmailConnectionResponse>> getConnections() {
        User currentUser = authService.getCurrentUser();
        List<GmailConnection> connections = connectionRepository.findByUserId(currentUser.getId());
        List<GmailConnectionResponse> responses = connections.stream()
                .map(connection -> {
                    List<GmailSyncCursor> cursors = syncCursorRepository.findByConnectionId(connection.getId());
                    Instant maxLastListed = cursors.stream()
                            .map(GmailSyncCursor::getLastListedAt)
                            .max(Instant::compareTo)
                            .orElse(null);
                    return GmailConnectionResponse.from(connection, maxLastListed);
                })
                .toList();
        return ResponseEntity.ok(responses);
    }

    @DeleteMapping("/api/v1/gmail/connections/{id}")
    public ResponseEntity<Void> disconnectConnection(@PathVariable UUID id) {
        User currentUser = authService.getCurrentUser();
        GmailConnection connection = connectionRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Gmail connection", id));

        if (!connection.getUser().getId().equals(currentUser.getId())) {
            log.error("Security Breach Attempt: User {} tried to disconnect Gmail connection {} owned by User {}",
                    currentUser.getId(), id, connection.getUser().getId());
            throw new ValidationException("You do not have permission to access this connection.");
        }

        connection.setIsConnected(false);
        connectionRepository.save(connection);

        return ResponseEntity.noContent().build();
    }

    @GetMapping("/api/v1/gmail/senders")
    public ResponseEntity<List<GmailSenderResponse>> getSenders() {
        User currentUser = authService.getCurrentUser();
        List<GmailSenderResponse> response = senderAllowlistService.getSenders(currentUser.getId()).stream()
                .map(GmailSenderResponse::from)
                .toList();
        return ResponseEntity.ok(response);
    }

    @PostMapping("/api/v1/gmail/senders")
    public ResponseEntity<GmailSenderResponse> createSender(@Valid @RequestBody GmailSenderRequest request) {
        User currentUser = authService.getCurrentUser();
        var sender = senderAllowlistService.createSender(currentUser.getId(), request);
        return ResponseEntity.ok(GmailSenderResponse.from(sender));
    }

    @PutMapping("/api/v1/gmail/senders/{id}")
    public ResponseEntity<GmailSenderResponse> updateSender(
            @PathVariable UUID id,
            @Valid @RequestBody GmailSenderRequest request) {
        User currentUser = authService.getCurrentUser();
        var sender = senderAllowlistService.updateSender(currentUser.getId(), id, request);
        return ResponseEntity.ok(GmailSenderResponse.from(sender));
    }

    @DeleteMapping("/api/v1/gmail/senders/{id}")
    public ResponseEntity<Void> deleteSender(@PathVariable UUID id) {
        User currentUser = authService.getCurrentUser();
        senderAllowlistService.deleteSender(currentUser.getId(), id);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/api/v1/gmail/attention")
    public ResponseEntity<Page<GmailAttentionItemResponse>> getAttentionItems(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(defaultValue = "false") boolean includeRetryable) {
        User currentUser = authService.getCurrentUser();
        List<GmailProcessedStatus> statuses = new ArrayList<>(List.of(
                GmailProcessedStatus.UNRESOLVED_ACCOUNT,
                GmailProcessedStatus.ACCOUNT_NOT_OPTED_IN,
                GmailProcessedStatus.FAILED_PERMANENT
        ));
        if (includeRetryable) {
            statuses.add(GmailProcessedStatus.FAILED_RETRYABLE);
        }

        Page<GmailProcessedMessage> items = processedMessageRepository.findAttentionItems(
                currentUser.getId(),
                statuses,
                PageRequest.of(page, size, Sort.by(Sort.Direction.DESC, "discoveredAt"))
        );

        Page<GmailAttentionItemResponse> response = items.map(GmailAttentionItemResponse::from);
        return ResponseEntity.ok(response);
    }

    @PostMapping("/api/v1/gmail/attention/{ledgerId}/retry")
    @Transactional
    public ResponseEntity<EnqueueResponse> retryAttentionItem(@PathVariable UUID ledgerId) {
        User currentUser = authService.getCurrentUser();
        GmailProcessedMessage gpm = processedMessageRepository.findById(ledgerId)
                .orElseThrow(() -> new ResourceNotFoundException("GmailProcessedMessage", ledgerId));

        if (!gpm.getUser().getId().equals(currentUser.getId())) {
            throw new ValidationException("You do not have permission to retry this item.");
        }

        Set<GmailProcessedStatus> retryableStatuses = Set.of(
                GmailProcessedStatus.UNRESOLVED_ACCOUNT,
                GmailProcessedStatus.ACCOUNT_NOT_OPTED_IN,
                GmailProcessedStatus.FAILED_PERMANENT
        );

        if (!retryableStatuses.contains(gpm.getStatus())) {
            throw new ValidationException("Item is not in a retryable attention status: " + gpm.getStatus());
        }

        gpm.setStatus(GmailProcessedStatus.DISCOVERED);
        gpm.setAttemptCount(0);
        gpm.setNextRetryAt(null);
        gpm.setError(null);
        processedMessageRepository.save(gpm);

        GmailConnection conn = gpm.getConnection();
        Job job = jobService.enqueue(
                currentUser.getId(),
                JobType.GMAIL_SYNC,
                JobTrigger.USER,
                new GmailSyncPayload(conn.getId()),
                null,
                conn.getId().toString()
        );

        return ResponseEntity.accepted().body(new EnqueueResponse(job.getId()));
    }

    @PostMapping("/api/v1/gmail/rescan")
    @Transactional
    public ResponseEntity<EnqueueResponse> rescan(@Valid @RequestBody RescanRequest request) {
        User currentUser = authService.getCurrentUser();
        LocalDate minAllowed = LocalDate.now().minusDays(ingestProperties.getMaxBackfillDays());
        if (request.fromDate().isBefore(minAllowed)) {
            throw new ValidationException("Rescan date cannot be earlier than " + minAllowed);
        }

        GmailBackfillDemand demand = backfillDemandRepository.findById(currentUser.getId())
                .orElseGet(() -> new GmailBackfillDemand(currentUser, request.fromDate()));
        if (demand.getFloorDate() == null || request.fromDate().isBefore(demand.getFloorDate())) {
            demand.setFloorDate(request.fromDate());
            backfillDemandRepository.save(demand);
        }

        GmailConnection connection = oauthService.getConnection(currentUser.getId());
        if (!connection.getIsConnected()) {
            throw new ValidationException("No connected Gmail connection found for rescan.");
        }

        Job job = jobService.enqueue(
                currentUser.getId(),
                JobType.GMAIL_SYNC,
                JobTrigger.USER,
                new GmailSyncPayload(connection.getId()),
                null,
                connection.getId().toString()
        );

        return ResponseEntity.accepted().body(new EnqueueResponse(job.getId()));
    }

    @GetMapping("/api/v1/accounts/{id}/gmail-cleanup-preview")
    public ResponseEntity<CleanupPreviewResponse> cleanupPreview(
            @PathVariable UUID id,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate before) {
        User currentUser = authService.getCurrentUser();
        Account account = accountRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Account", id));

        if (!account.getUser().getId().equals(currentUser.getId())) {
            throw new ValidationException("You do not have permission to access this account.");
        }

        List<Transaction> txns = transactionRepository.findUnreconciledAlertsBeforeDate(id, currentUser.getId(), before);
        return ResponseEntity.ok(new CleanupPreviewResponse(txns.size(), before));
    }

    @PostMapping("/api/v1/accounts/{id}/gmail-cleanup")
    @Transactional
    public ResponseEntity<CleanupResultResponse> performCleanup(
            @PathVariable UUID id,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate before) {
        User currentUser = authService.getCurrentUser();
        Account account = accountRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Account", id));

        if (!account.getUser().getId().equals(currentUser.getId())) {
            throw new ValidationException("You do not have permission to access this account.");
        }

        List<Transaction> txns = transactionRepository.findUnreconciledAlertsBeforeDate(id, currentUser.getId(), before);
        int deletedCount = 0;
        for (Transaction txn : txns) {
            Optional<GmailProcessedMessage> gpmOpt = processedMessageRepository.findByTransactionId(txn.getId());
            if (gpmOpt.isPresent()) {
                GmailProcessedMessage gpm = gpmOpt.get();
                gpm.setStatus(GmailProcessedStatus.CLEANED_UP);
                gpm.setTransaction(null);
                processedMessageRepository.save(gpm);
            }
            transactionRepository.delete(txn);
            deletedCount++;
        }

        return ResponseEntity.ok(new CleanupResultResponse(deletedCount));
    }
}
