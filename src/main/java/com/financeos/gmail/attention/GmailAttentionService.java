package com.financeos.gmail.attention;

import com.financeos.api.gmail.dto.AttentionReasonCount;
import com.financeos.domain.job.JobService;
import com.financeos.domain.job.JobTrigger;
import com.financeos.domain.job.JobType;
import com.financeos.domain.job.handlers.GmailSyncPayload;
import com.financeos.domain.notification.gmail.GmailAttentionNotificationService;
import com.financeos.gmail.domain.AttentionReason;
import com.financeos.gmail.domain.GmailProcessedMessage;
import com.financeos.gmail.domain.GmailProcessedMessageRepository;
import com.financeos.gmail.domain.GmailProcessedMessageRepository.AttentionBrief;
import com.financeos.gmail.domain.GmailProcessedStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** Per-reason summary and bulk retry / archive of the current user's active "needs attention" Gmail items. */
@Service
public class GmailAttentionService {

    static final int BATCH_SIZE = 100;

    /** Statuses a retry may reset (retryable failures are still in flight). */
    private static final Set<GmailProcessedStatus> RETRYABLE = Set.of(
            GmailProcessedStatus.UNRESOLVED_ACCOUNT,
            GmailProcessedStatus.ACCOUNT_NOT_OPTED_IN,
            GmailProcessedStatus.FAILED_PERMANENT);

    private final GmailProcessedMessageRepository repository;
    private final JobService jobService;
    private final TransactionTemplate tx;

    public GmailAttentionService(GmailProcessedMessageRepository repository, JobService jobService,
                                 PlatformTransactionManager transactionManager) {
        this.repository = repository;
        this.jobService = jobService;
        this.tx = new TransactionTemplate(transactionManager);
        // Also runs from an after-commit hook, where the finished transaction is still bound to the thread.
        this.tx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    /** Active item counts per reason, reasons with no items left out; same inclusion as the list. */
    public List<AttentionReasonCount> summary(UUID userId, boolean includeRetryable) {
        return summary(userId, includeRetryable, false);
    }

    /** With {@code ungroupedOnly}: counts of the mail the ungrouped list shows (no unknown-number or import-off mail). */
    public List<AttentionReasonCount> summary(UUID userId, boolean includeRetryable, boolean ungroupedOnly) {
        Map<AttentionReason, Long> counts = new EnumMap<>(AttentionReason.class);
        for (AttentionBrief b : briefs(userId, includeRetryable, ungroupedOnly)) {
            counts.merge(AttentionReason.of(b.getStatus(), b.getError()), 1L, Long::sum);
        }
        List<AttentionReason> order = new ArrayList<>(counts.keySet());
        return order.stream().map(r -> new AttentionReasonCount(r, counts.get(r))).toList();
    }

    /** Resets every retryable active item of the reason and queues one sync per affected mailbox. */
    public int retryAll(UUID userId, AttentionReason reason) {
        return retryAll(userId, reason, false);
    }

    /** With {@code ungroupedOnly}, mail that belongs to an unknown-number or import-off group is left alone. */
    public int retryAll(UUID userId, AttentionReason reason, boolean ungroupedOnly) {
        int affected = 0;
        Set<UUID> enqueued = new HashSet<>();
        for (List<UUID> batch : batches(userId, reason, ungroupedOnly)) {
            Integer n = tx.execute(s -> {
                int done = 0;
                for (GmailProcessedMessage gpm : repository.findByUserIdAndIdIn(userId, batch)) {
                    if (!RETRYABLE.contains(gpm.getStatus()) || gpm.getArchivedAt() != null) continue;
                    gpm.setStatus(GmailProcessedStatus.DISCOVERED);
                    gpm.setAttemptCount(0);
                    gpm.setNextRetryAt(null);
                    gpm.setError(null);
                    gpm.setAttentionNotifiedAt(null);
                    repository.save(gpm);
                    UUID connectionId = gpm.getConnection().getId();
                    if (enqueued.add(connectionId)) {
                        jobService.enqueue(userId, JobType.GMAIL_SYNC, JobTrigger.USER,
                                new GmailSyncPayload(connectionId), null, connectionId.toString());
                    }
                    done++;
                }
                return done;
            });
            affected += n == null ? 0 : n;
        }
        return affected;
    }

    /** Archives every active item of the reason. */
    public int archiveAll(UUID userId, AttentionReason reason) {
        return archiveAll(userId, reason, false);
    }

    /** With {@code ungroupedOnly}, mail that belongs to an unknown-number or import-off group is left alone. */
    public int archiveAll(UUID userId, AttentionReason reason, boolean ungroupedOnly) {
        int affected = 0;
        for (List<UUID> batch : batches(userId, reason, ungroupedOnly)) {
            Integer n = tx.execute(s -> {
                int done = 0;
                Instant now = Instant.now();
                for (GmailProcessedMessage gpm : repository.findByUserIdAndIdIn(userId, batch)) {
                    if (gpm.getArchivedAt() != null
                            || !GmailAttentionNotificationService.ATTENTION_STATUSES.contains(gpm.getStatus())) continue;
                    gpm.setArchivedAt(now);
                    repository.save(gpm);
                    done++;
                }
                return done;
            });
            affected += n == null ? 0 : n;
        }
        return affected;
    }

    /** Ungrouped mail is never import-off mail (archived on arrival, shown only as a group), so it leaves ACCOUNT_NOT_OPTED_IN out. */
    private List<AttentionBrief> briefs(UUID userId, boolean includeRetryable, boolean ungroupedOnly) {
        if (!ungroupedOnly) return repository.findAttentionBriefs(userId, statuses(includeRetryable));
        List<GmailProcessedStatus> statuses = new ArrayList<>(List.of(
                GmailProcessedStatus.UNRESOLVED_ACCOUNT, GmailProcessedStatus.FAILED_PERMANENT));
        if (includeRetryable) statuses.add(GmailProcessedStatus.FAILED_RETRYABLE);
        return repository.findUngroupedAttentionBriefs(userId, statuses);
    }

    private List<List<UUID>> batches(UUID userId, AttentionReason reason, boolean ungroupedOnly) {
        List<UUID> ids = briefs(userId, false, ungroupedOnly).stream()
                .filter(b -> AttentionReason.of(b.getStatus(), b.getError()) == reason)
                .map(AttentionBrief::getId)
                .toList();
        List<List<UUID>> out = new ArrayList<>();
        for (int i = 0; i < ids.size(); i += BATCH_SIZE) {
            out.add(ids.subList(i, Math.min(ids.size(), i + BATCH_SIZE)));
        }
        return out;
    }

    private static List<GmailProcessedStatus> statuses(boolean includeRetryable) {
        List<GmailProcessedStatus> statuses = new ArrayList<>(GmailAttentionNotificationService.ATTENTION_STATUSES);
        if (includeRetryable) statuses.add(GmailProcessedStatus.FAILED_RETRYABLE);
        return statuses;
    }
}
