package com.financeos.domain.failure;

import com.financeos.api.admin.dto.FailedGmailItemResponse;
import com.financeos.api.admin.dto.FailedJobResponse;
import com.financeos.api.admin.dto.FailureSummaryResponse;
import com.financeos.api.admin.dto.RetryAllResponse;
import com.financeos.core.exception.ApiStatusException;
import com.financeos.core.exception.ResourceNotFoundException;
import com.financeos.core.exception.ValidationException;
import com.financeos.core.security.UserContextHelper;
import com.financeos.core.time.AppTime;
import com.financeos.domain.job.FailedJobRow;
import com.financeos.domain.job.Job;
import com.financeos.domain.job.JobArtifactRepository;
import com.financeos.domain.job.JobKeyRow;
import com.financeos.domain.job.JobRepository;
import com.financeos.domain.job.JobService;
import com.financeos.domain.job.JobStatus;
import com.financeos.domain.job.JobTrigger;
import com.financeos.domain.job.JobType;
import com.financeos.domain.job.JobWorker;
import com.financeos.domain.job.handlers.GmailSyncPayload;
import com.financeos.domain.user.User;
import com.financeos.domain.user.UserRepository;
import com.financeos.gmail.domain.FailedGmailRow;
import com.financeos.gmail.domain.GmailProcessedMessage;
import com.financeos.gmail.domain.GmailProcessedMessageRepository;
import com.financeos.gmail.domain.GmailProcessedStatus;
import net.logstash.logback.argument.StructuredArguments;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;
import java.util.stream.Collectors;

/**
 * Admin Failures tool: finds failed jobs and failed Gmail messages across users, shows the stack, retries or gives up on them.
 *
 * <p>Cross-user by design. This class is deliberately not {@code @Transactional}: it runs its database work through its
 * own transactions with no user in the context, so the tenant filter is never enabled for the admin's id (which would hide
 * every other user's rows), and it enqueues work as the item's owner ({@link UserContextHelper#callAs}) so tenant-scoped
 * code sees the owner. The caller (the controller) has already passed the explicit-admin guard.
 */
@Service
public class FailureAdminService {

    private static final Logger log = LoggerFactory.getLogger(FailureAdminService.class);

    /** How far back failed jobs are listed. */
    static final Duration JOB_WINDOW = Duration.ofDays(30);
    static final int MAX_PAGE_SIZE = 100;
    static final Set<GmailProcessedStatus> FAILED = Set.of(GmailProcessedStatus.FAILED_RETRYABLE, GmailProcessedStatus.FAILED_PERMANENT);
    private static final Set<GmailProcessedStatus> GMAIL_RETRYABLE = Set.of(
            GmailProcessedStatus.FAILED_RETRYABLE, GmailProcessedStatus.FAILED_PERMANENT, GmailProcessedStatus.SKIPPED_BY_ADMIN);

    private final JobRepository jobRepository;
    private final JobArtifactRepository artifactRepository;
    private final GmailProcessedMessageRepository gmailRepository;
    private final UserRepository userRepository;
    private final JobService jobService;
    private final TransactionTemplate tx;

    public FailureAdminService(JobRepository jobRepository, JobArtifactRepository artifactRepository,
                               GmailProcessedMessageRepository gmailRepository, UserRepository userRepository,
                               JobService jobService, PlatformTransactionManager transactionManager) {
        this.jobRepository = jobRepository;
        this.artifactRepository = artifactRepository;
        this.gmailRepository = gmailRepository;
        this.userRepository = userRepository;
        this.jobService = jobService;
        this.tx = new TransactionTemplate(transactionManager);
    }

    // ------------------------------------------------------------------ reads

    public FailureSummaryResponse summary() {
        return unscoped(() -> new FailureSummaryResponse(
                jobRepository.countFailedActiveForAdmin(windowStart()),
                gmailRepository.countByStatusAndDismissedAtIsNull(GmailProcessedStatus.FAILED_RETRYABLE),
                gmailRepository.countByStatusAndDismissedAtIsNull(GmailProcessedStatus.FAILED_PERMANENT)));
    }

    public Page<FailedJobResponse> listJobs(int page, int size, boolean includeDismissed) {
        return unscoped(() -> {
            Page<FailedJobRow> jobs = includeDismissed
                    ? jobRepository.findFailedWithDismissedForAdmin(windowStart(), pageOf(page, size))
                    : jobRepository.findFailedForAdmin(windowStart(), pageOf(page, size));
            Set<UUID> withFiles = filesOf(jobs.getContent().stream().filter(r -> JobService.needsArtifacts(r.type())).map(FailedJobRow::id).toList());
            return jobs.map(r -> toResponse(r, withFiles));
        });
    }

    public FailedJobResponse jobDetail(UUID id) {
        return unscoped(() -> {
            Job job = jobRepository.findById(id).orElseThrow(() -> new ResourceNotFoundException("Job", id));
            return toResponse(job, emailsOf(java.util.Collections.singletonList(job.getUserId())), filesOf(List.of(job)), true);
        });
    }

    public Page<FailedGmailItemResponse> listGmail(int page, int size, GmailProcessedStatus status) {
        Set<GmailProcessedStatus> statuses = status == null ? FAILED : Set.of(status);
        if (status != null && !FAILED.contains(status) && status != GmailProcessedStatus.SKIPPED_BY_ADMIN) {
            throw new ValidationException("status must be FAILED_RETRYABLE, FAILED_PERMANENT or SKIPPED_BY_ADMIN");
        }
        return unscoped(() -> gmailRepository.findForAdmin(statuses, pageOf(page, size)).map(FailureAdminService::toResponse));
    }

    public FailedGmailItemResponse gmailDetail(UUID ledgerId) {
        return unscoped(() -> tx.execute(s -> toResponse(loadGmail(ledgerId), true)));
    }

    // ------------------------------------------------------------------ gmail actions

    /** Resets one failed or skipped row and enqueues a sync for its mailbox; returns the job id. */
    public UUID retryGmail(UUID ledgerId, String adminEmail) {
        UUID[] owner = new UUID[2];
        unscoped(() -> tx.execute(s -> {
            GmailProcessedMessage g = loadGmail(ledgerId);
            if (!GMAIL_RETRYABLE.contains(g.getStatus())) {
                throw new ApiStatusException(HttpStatus.CONFLICT, "NOT_FAILED", "Item is not failed or skipped: " + g.getStatus());
            }
            g.resetForRetry();
            gmailRepository.save(g);
            owner[0] = g.getUser().getId();
            owner[1] = g.getConnection().getId();
            return null;
        }));
        Job job = enqueueSync(owner[0], owner[1]);
        logAction(adminEmail, "retry", "gmail", List.of(ledgerId), owner[0]);
        return job.getId();
    }

    /** Gives up on one failed row: SKIPPED_BY_ADMIN, never drained or retried by the pipeline. */
    public void skipGmail(UUID ledgerId, String reason, String adminEmail) {
        UUID userId = unscoped(() -> tx.execute(s -> {
            GmailProcessedMessage g = loadGmail(ledgerId);
            if (!FAILED.contains(g.getStatus())) {
                throw new ApiStatusException(HttpStatus.CONFLICT, "NOT_FAILED", "Item is not failed: " + g.getStatus());
            }
            g.setStatus(GmailProcessedStatus.SKIPPED_BY_ADMIN);
            g.setNextRetryAt(null);
            g.setDismissedAt(Instant.now());
            g.setDismissedBy(adminEmail);
            g.setDismissReason(reason);
            gmailRepository.save(g);
            return g.getUser().getId();
        }));
        logAction(adminEmail, "skip", "gmail", List.of(ledgerId), userId);
    }

    /** Resets every failed row (not the admin-skipped ones) in one update, then one sync per affected user and mailbox. */
    public RetryAllResponse retryAllGmail(String adminEmail) {
        record Reset(List<Object[]> pairs, int rows) {}
        Reset reset = unscoped(() -> tx.execute(s -> {
            List<Object[]> found = gmailRepository.findUserConnectionPairsByStatusIn(FAILED);
            return new Reset(found, gmailRepository.resetFailedToDiscovered(FAILED));
        }));
        int enqueued = 0;
        for (Object[] p : reset.pairs()) {
            enqueueSync((UUID) p[0], (UUID) p[1]);
            enqueued++;
        }
        logAction(adminEmail, "retry-all", "gmail", List.of(), null);
        return new RetryAllResponse(reset.rows(), 0, enqueued);
    }

    // ------------------------------------------------------------------ job actions

    /** Retries one failed job as its owner; the original is marked dismissed ("Retried as ..."). Returns the new job. */
    public Job retryJob(UUID jobId, String adminEmail) {
        Job orig = unscoped(() -> jobRepository.findById(jobId).orElseThrow(() -> new ResourceNotFoundException("Job", jobId)));
        if (orig.getStatus() != JobStatus.FAILED) {
            throw new ApiStatusException(HttpStatus.CONFLICT, "NOT_FAILED", "Job is not failed: " + orig.getStatus());
        }
        Job fresh;
        try {
            fresh = UserContextHelper.callAs(orig.getUserId(), () -> jobService.retryForAdmin(jobId, adminEmail));
        } catch (ValidationException e) {
            throw new ApiStatusException(HttpStatus.CONFLICT, "NOT_RETRYABLE", e.getMessage());
        }
        logAction(adminEmail, "retry", "job", List.of(jobId), orig.getUserId());
        return fresh;
    }

    /**
     * Retries the failed jobs of the window, one retry per distinct failure: jobs of the same owner, type and payload form a
     * group. A group with a later SUCCEEDED job of the same kind is dismissed as superseded (counted as skipped); otherwise
     * only its newest job is retried and the older ones are dismissed "Superseded by &lt;new job&gt;". A group that cannot be
     * retried (expired files, files already in the Inbox) is counted as skipped and left as it is.
     * Counts: affected = groups retried, skipped = groups not retried, jobsEnqueued = jobs enqueued (= affected).
     */
    public RetryAllResponse retryAllJobs(String adminEmail) {
        Instant since = windowStart();
        List<JobKeyRow> failed = unscoped(() -> jobRepository.findFailedActiveForAdmin(since));
        if (failed.isEmpty()) {
            logAction(adminEmail, "retry-all", "job", List.of(), null);
            return new RetryAllResponse(0, 0, 0);
        }
        List<JobKeyRow> succeeded = unscoped(() -> jobRepository.findSucceededAfterFailuresForAdmin(since));
        Set<UUID> needing = failed.stream().filter(j -> JobService.needsArtifacts(j.type())).map(JobKeyRow::id).collect(Collectors.toSet());
        Set<UUID> withFiles = unscoped(() -> filesOf(needing));

        // newest first, as queried: a group's first row is its newest failure
        Map<List<Object>, List<JobKeyRow>> groups = new LinkedHashMap<>();
        for (JobKeyRow j : failed) {
            groups.computeIfAbsent(groupKey(j), k -> new ArrayList<>()).add(j);
        }
        Map<List<Object>, Instant> latestSuccess = new java.util.HashMap<>();
        for (JobKeyRow s : succeeded) {
            latestSuccess.merge(groupKey(s), s.finishedAt(), (x, y) -> x.isAfter(y) ? x : y);
        }

        int retried = 0;
        int skipped = 0;
        List<UUID> ids = new ArrayList<>();
        for (Map.Entry<List<Object>, List<JobKeyRow>> e : groups.entrySet()) {
            List<JobKeyRow> group = e.getValue();
            JobKeyRow newest = group.get(0);
            Instant success = latestSuccess.get(e.getKey());
            if (success != null && success.isAfter(newest.finishedAt())) {
                dismissAll(group, "Superseded by a later successful run", adminEmail);
                skipped++;
                continue;
            }
            if (JobService.needsArtifacts(newest.type()) && !withFiles.contains(newest.id())) {
                skipped++;
                continue;
            }
            Job fresh;
            try {
                fresh = UserContextHelper.callAs(newest.userId(), () -> jobService.retryForAdmin(newest.id(), adminEmail));
            } catch (RuntimeException ex) {
                skipped++; // e.g. the files are already waiting in the Inbox
                continue;
            }
            retried++;
            ids.add(newest.id());
            if (group.size() > 1) {
                dismissAll(group.subList(1, group.size()), "Superseded by " + fresh.getId(), adminEmail);
            }
        }
        logAction(adminEmail, "retry-all", "job", ids, null);
        return new RetryAllResponse(retried, skipped, retried);
    }

    private static List<Object> groupKey(JobKeyRow j) {
        return java.util.Arrays.asList(j.userId(), j.type(), j.payload());
    }

    private void dismissAll(List<JobKeyRow> jobs, String reason, String adminEmail) {
        List<UUID> ids = jobs.stream().map(JobKeyRow::id).toList();
        unscoped(() -> tx.execute(s -> jobRepository.dismissAll(ids, AppTime.nowInstant(), adminEmail, reason)));
    }

    /** Marks a failed job as handled without retrying it. */
    public void dismissJob(UUID jobId, String reason, String adminEmail) {
        UUID userId = unscoped(() -> tx.execute(s -> {
            Job job = jobRepository.findById(jobId).orElseThrow(() -> new ResourceNotFoundException("Job", jobId));
            if (job.getStatus() != JobStatus.FAILED) {
                throw new ApiStatusException(HttpStatus.CONFLICT, "NOT_FAILED", "Job is not failed: " + job.getStatus());
            }
            job.setDismissedAt(Instant.now());
            job.setDismissedBy(adminEmail);
            job.setDismissReason(reason);
            jobRepository.save(job);
            return job.getUserId();
        }));
        logAction(adminEmail, "dismiss", "job", List.of(jobId), userId);
    }

    // ------------------------------------------------------------------ helpers

    private Job enqueueSync(UUID userId, UUID connectionId) {
        return UserContextHelper.callAs(userId, () -> jobService.enqueue(
                userId, JobType.GMAIL_SYNC, JobTrigger.USER, new GmailSyncPayload(connectionId), null, connectionId.toString()));
    }

    private GmailProcessedMessage loadGmail(UUID ledgerId) {
        return gmailRepository.findById(ledgerId).orElseThrow(() -> new ResourceNotFoundException("GmailProcessedMessage", ledgerId));
    }

    /** Runs the work with no user in the context: no tenant filter, so every user's rows are visible. */
    private static <T> T unscoped(Supplier<T> work) {
        return UserContextHelper.callAs(null, work);
    }

    private static Instant windowStart() {
        return AppTime.nowInstant().minus(JOB_WINDOW);
    }

    private static PageRequest pageOf(int page, int size) {
        return PageRequest.of(Math.max(0, page), Math.min(MAX_PAGE_SIZE, Math.max(1, size)));
    }

    private Map<UUID, String> emailsOf(Collection<UUID> userIds) {
        Set<UUID> ids = userIds.stream().filter(java.util.Objects::nonNull).collect(Collectors.toSet());
        if (ids.isEmpty()) {
            return Map.of();
        }
        Map<UUID, String> emails = new LinkedHashMap<>();
        for (User u : userRepository.findAllById(ids)) {
            emails.put(u.getId(), u.getEmail());
        }
        return emails;
    }

    /** Ids of the jobs (among these) that still have stored files; one query. */
    private Set<UUID> filesOf(List<Job> jobs) {
        return filesOf(jobs.stream().filter(JobService::needsArtifacts).map(Job::getId).toList());
    }

    /** Ids (among these, which must need files) that still have stored files; one query. */
    private Set<UUID> filesOf(Collection<UUID> needing) {
        return needing.isEmpty() ? Set.of() : artifactRepository.findJobIdsWithArtifacts(needing);
    }

    private static boolean isRetryable(Job job, Set<UUID> withFiles) {
        return job.getStatus() == JobStatus.FAILED && (!JobService.needsArtifacts(job) || withFiles.contains(job.getId()));
    }

    private static FailedJobResponse toResponse(Job j, Map<UUID, String> emails, Set<UUID> withFiles, boolean detail) {
        return new FailedJobResponse(j.getId(), j.getType().name(), JobWorker.getJobName(j.getType()), j.getUserId(),
                j.getUserId() == null ? null : emails.get(j.getUserId()), j.getErrorCode(), j.getErrorMessage(), j.getAttempt(),
                j.getStartedAt(), j.getFinishedAt(), isRetryable(j, withFiles),
                j.getDismissedAt(), j.getDismissedBy(), j.getDismissReason(), detail ? j.getErrorDetail() : null);
    }

    /** A list row: no stack. */
    private static FailedJobResponse toResponse(FailedJobRow r, Set<UUID> withFiles) {
        boolean retryable = !JobService.needsArtifacts(r.type()) || withFiles.contains(r.id());
        return new FailedJobResponse(r.id(), r.type().name(), JobWorker.getJobName(r.type()), r.userId(),
                r.userId() == null ? null : r.userEmail(), r.errorCode(), r.errorMessage(), r.attempt(),
                r.startedAt(), r.finishedAt(), retryable, r.dismissedAt(), r.dismissedBy(), r.dismissReason(), null);
    }

    /** A list row: no stack. */
    private static FailedGmailItemResponse toResponse(FailedGmailRow g) {
        return new FailedGmailItemResponse(g.id(), g.gmailMessageId(), g.userId(), g.userEmail(), g.connectionEmail(),
                g.senderAddress(), g.subject(), g.internalDate(), g.status(), g.attemptCount(), g.nextRetryAt(), g.error(),
                g.processedAt(), g.dismissedAt(), g.dismissedBy(), g.dismissReason(), null);
    }

    /** The detail: needs the row's user and connection loaded (inside a transaction or fetched). */
    private static FailedGmailItemResponse toResponse(GmailProcessedMessage g, boolean detail) {
        return new FailedGmailItemResponse(g.getId(), g.getGmailMessageId(), g.getUser().getId(), g.getUser().getEmail(),
                g.getConnection().getEmail(), g.getSenderAddress(), g.getSubject(), g.getInternalDate(), g.getStatus(),
                g.getAttemptCount(), g.getNextRetryAt(), g.getError(), g.getProcessedAt(),
                g.getDismissedAt(), g.getDismissedBy(), g.getDismissReason(), detail ? g.getErrorDetail() : null);
    }

    private void logAction(String adminEmail, String action, String kind, List<UUID> ids, UUID userId) {
        log.info("Admin failure action: action={}, kind={}", action, kind,
                StructuredArguments.keyValue("event", "admin.failure_action"),
                StructuredArguments.keyValue("adminEmail", adminEmail),
                StructuredArguments.keyValue("action", action),
                StructuredArguments.keyValue("kind", kind),
                StructuredArguments.keyValue("ids", ids.stream().map(UUID::toString).toList()),
                StructuredArguments.keyValue("userId", userId != null ? userId.toString() : null));
    }
}
