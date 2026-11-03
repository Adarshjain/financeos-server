package com.financeos.gmail.accountdetect.job;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.financeos.domain.job.Job;
import com.financeos.domain.job.JobRepository;
import com.financeos.domain.job.JobService;
import com.financeos.domain.job.JobStatus;
import com.financeos.domain.job.JobTrigger;
import com.financeos.domain.job.JobType;
import com.financeos.domain.job.handlers.GmailSyncPayload;
import com.financeos.domain.account.StatementPasswordSavedEvent;
import com.financeos.domain.notification.job.JobFinishedEvent;
import com.financeos.domain.user.ProfileChangedEvent;
import com.financeos.gmail.domain.GmailConnection;
import com.financeos.gmail.domain.GmailConnectionRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.Comparator;
import java.util.List;
import java.util.UUID;

/**
 * When ACCOUNT_DETECT runs and what clears it. Enqueued (at most one queued per user, by dedup key) after a successful
 * Gmail sync and when a Gmail connection is connected or reconnected, both gated by
 * {@code financeos.account-detect.enabled}; only the mailbox the scan reads ({@link #scanTarget}) counts.
 * Disconnecting that mailbox deletes the user's stored state and facts. Never throws into the caller.
 */
@Component
public class AccountDetectTriggers {

    private static final Logger log = LoggerFactory.getLogger(AccountDetectTriggers.class);

    private final JobService jobService;
    private final JobRepository jobRepository;
    private final GmailConnectionRepository connectionRepository;
    private final AccountDetectStore store;
    private final ObjectMapper objectMapper;
    private final TransactionTemplate newTransaction;
    private final boolean enabled;

    public AccountDetectTriggers(JobService jobService, JobRepository jobRepository, GmailConnectionRepository connectionRepository,
                                 AccountDetectStore store, ObjectMapper objectMapper, PlatformTransactionManager transactionManager,
                                 @Value("${financeos.account-detect.enabled:true}") boolean enabled) {
        this.jobService = jobService;
        this.jobRepository = jobRepository;
        this.connectionRepository = connectionRepository;
        this.store = store;
        this.objectMapper = objectMapper;
        this.newTransaction = new TransactionTemplate(transactionManager);
        this.newTransaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        this.enabled = enabled;
    }

    public static String dedupKey(UUID userId) {
        return "account-detect:" + userId;
    }

    /**
     * The mailbox the scan reads: the user's primary connection while it is connected, otherwise the oldest other
     * connected one (the app never moves the primary flag on disconnect). Null when none is connected.
     */
    public static GmailConnection scanTarget(List<GmailConnection> connections) {
        if (connections == null) return null;
        List<GmailConnection> connected = connections.stream().filter(c -> Boolean.TRUE.equals(c.getIsConnected())).toList();
        return connected.stream().filter(c -> Boolean.TRUE.equals(c.getIsPrimary())).findFirst()
                .orElseGet(() -> connected.stream()
                        .min(Comparator.comparing(GmailConnection::getCreatedAt, Comparator.nullsLast(Comparator.naturalOrder()))
                                .thenComparing(GmailConnection::getId, Comparator.nullsLast(Comparator.naturalOrder())))
                        .orElse(null));
    }

    /** After a successful Gmail sync of the mailbox the scan reads. Runs after the job's status has committed. */
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void onJobFinished(JobFinishedEvent event) {
        if (!enabled || event.type() != JobType.GMAIL_SYNC || event.status() != JobStatus.SUCCEEDED || event.userId() == null) {
            return;
        }
        try {
            // the finished transaction is gone: enqueue in a fresh one so the job row commits
            newTransaction.executeWithoutResult(s -> {
                Job job = jobRepository.findById(event.jobId()).orElse(null);
                UUID connectionId = job == null ? null : syncConnectionId(job);
                if (connectionId != null) enqueueForConnection(event.userId(), connectionId);
            });
        } catch (Exception e) {
            log.warn("Account detect enqueue after Gmail sync failed: {}", e.getClass().getSimpleName());
        }
    }

    /** The user's name or birth date changed: statement PDFs that stayed locked are tried again. */
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void onProfileChanged(ProfileChangedEvent event) {
        if (event != null && (event.nameChanged() || event.birthDateChanged())) retryUnlockAfterCommit(event.userId(), event.nameChanged());
    }

    /** An account was saved with a statement password: statement PDFs that stayed locked are tried again. */
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void onStatementPasswordSaved(StatementPasswordSavedEvent event) {
        if (event != null) retryUnlockAfterCommit(event.userId(), false);
    }

    private void retryUnlockAfterCommit(UUID userId, boolean nameChanged) {
        if (!enabled || userId == null) return;
        try {
            newTransaction.executeWithoutResult(s -> enqueueUnlockRetry(userId, nameChanged));
        } catch (Exception e) {
            log.warn("Account detect unlock-retry enqueue failed: {}", e.getClass().getSimpleName());
        }
    }

    /**
     * Puts an unlock retry on the queue for the mailbox the scan reads, once that mailbox has been scanned. Same dedup as a
     * scan: if a job is already queued the enqueue is dropped, but the persistent flag makes that job end with the retry.
     */
    void enqueueUnlockRetry(UUID userId, boolean recheckHolders) {
        GmailConnection target = scanTarget(connectionRepository.findByUserId(userId));
        if (target == null || store.state(userId).isEmpty()) return;
        // the new generation outlives a dropped enqueue (accepted: a trigger arriving while a run is in progress may be deduped
        // away): any later run of the user's ACCOUNT_DETECT ends with a retry pass over the facts not tried at it yet
        store.requestUnlockRetry(userId, recheckHolders);   // a name change also recomputes the holder flags
        jobService.enqueue(userId, JobType.ACCOUNT_DETECT, JobTrigger.CRON, new AccountDetectPayload(target.getId(), true), null, dedupKey(userId));
    }

    /**
     * A Gmail connection was connected or reconnected. Enqueues once the caller's transaction has committed (so the job
     * sees the saved connection, and a failure here can never roll the connect back).
     */
    public void onConnected(UUID userId, GmailConnection connection) {
        if (!enabled || userId == null || connection == null || connection.getId() == null) {
            return;
        }
        UUID connectionId = connection.getId();
        Runnable enqueue = () -> {
            try {
                newTransaction.executeWithoutResult(s -> enqueueForConnection(userId, connectionId));
            } catch (Exception e) {
                log.warn("Account detect enqueue after Gmail connect failed: {}", e.getClass().getSimpleName());
            }
        };
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    enqueue.run();
                }
            });
        } else {
            enqueue.run();
        }
    }

    /**
     * Gmail was disconnected: the user's account-detect state and facts go, unless they belong to another of the
     * user's mailboxes than the one disconnected.
     */
    public void onDisconnected(UUID userId, UUID connectionId) {
        if (userId == null) {
            return;
        }
        UUID scanned = store.state(userId).map(AccountDetectState::getConnectionId).orElse(null);
        if (scanned != null && connectionId != null && !scanned.equals(connectionId)) {
            return;
        }
        store.deleteForUser(userId);
    }

    /** Puts a scan on the queue (dedup: at most one pending or running per user). Not gated: used to re-queue. */
    public void enqueue(UUID userId, UUID connectionId) {
        jobService.enqueue(userId, JobType.ACCOUNT_DETECT, JobTrigger.CRON, new AccountDetectPayload(connectionId), null, dedupKey(userId));
    }

    private void enqueueForConnection(UUID userId, UUID connectionId) {
        GmailConnection connection = connectionRepository.findById(connectionId).orElse(null);
        if (connection == null || connection.getUser() == null || !userId.equals(connection.getUser().getId())
                || !Boolean.TRUE.equals(connection.getIsConnected())) {
            return;
        }
        GmailConnection target = scanTarget(connectionRepository.findByUserId(userId));
        if (target == null || !connectionId.equals(target.getId())) {
            return;
        }
        enqueue(userId, connectionId);
    }

    private UUID syncConnectionId(Job job) {
        if (job.getPayload() == null || job.getPayload().isBlank()) return null;
        try {
            GmailSyncPayload p = objectMapper.readValue(job.getPayload(), GmailSyncPayload.class);
            return p == null ? null : p.connectionId();
        } catch (Exception e) {
            return null;
        }
    }
}
