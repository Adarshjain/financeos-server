package com.financeos.domain.notification.feed;

import com.financeos.core.exception.ResourceNotFoundException;
import com.financeos.core.time.AppTime;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The in-app notifications feed: "what happened", kept per user until cleared or old. Independent of
 * push (settings, devices and mutes never gate it). {@link #record} is safe to call from anywhere: it
 * runs in its own transaction and swallows failures, so a feed problem never fails a job, sync or alert.
 */
@Service
public class NotificationFeedService {

    private static final Logger log = LoggerFactory.getLogger(NotificationFeedService.class);

    static final int RETENTION_DAYS = 90;
    static final int CLEARED_RETENTION_DAYS = 1;
    /** The stage that un-clears a reminder the user had cleared. */
    public static final String STAGE_OVERDUE = "OVERDUE";

    private final AppNotificationRepository repository;
    private final TransactionTemplate isolated;

    public NotificationFeedService(AppNotificationRepository repository, PlatformTransactionManager transactionManager) {
        this.repository = repository;
        this.isolated = new TransactionTemplate(transactionManager);
        this.isolated.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    /** Adds a feed row; a repeated dedupe key for the same user is a no-op. Never throws. */
    public void record(UUID userId, String kind, String title, String body, AppNotificationRefs refs, String dedupeKey) {
        try {
            isolated.executeWithoutResult(status -> insert(userId, kind, title, body, refs, dedupeKey));
        } catch (Exception e) {
            log.warn("Feed record failed (user {}, kind {}, key {}): {}", userId, kind, dedupeKey, e.getMessage());
        }
    }

    private void insert(UUID userId, String kind, String title, String body, AppNotificationRefs refs, String dedupeKey) {
        if (repository.existsByUserIdAndDedupeKey(userId, dedupeKey)) {
            return;
        }
        AppNotificationRefs r = refs == null ? AppNotificationRefs.NONE : refs;
        AppNotification n = new AppNotification();
        n.setUserId(userId);
        n.setKind(kind);
        n.setTitle(clip(title, AppNotification.MAX_TITLE_LENGTH));
        n.setBody(body == null || body.isBlank() ? null : clip(body, AppNotification.MAX_BODY_LENGTH));
        n.setJobId(r.jobId());
        n.setJobType(r.jobType());
        n.setAccountId(r.accountId());
        n.setConnectionId(r.connectionId());
        n.setLoanId(r.loanId());
        n.setStatementId(r.statementId());
        n.setDedupeKey(clip(dedupeKey, AppNotification.MAX_KEY_LENGTH));
        n.setCreatedAt(now());
        repository.saveAndFlush(n);
    }

    /** One in-place reminder: what its row should say right now. */
    public record Reminder(String dedupeKey, String kind, String title, String body, AppNotificationRefs refs, String stage) {
    }

    /** {@link #upsertAll} for one reminder. Never throws. */
    public void upsert(UUID userId, String dedupeKey, String kind, String title, String body, AppNotificationRefs refs, String stage) {
        upsertAll(userId, List.of(new Reminder(dedupeKey, kind, title, body, refs, stage)));
    }

    /**
     * Inserts each reminder's row or updates the existing one in place (title, body, refs, updated_at), all in
     * one transaction with one lookup for the whole batch. A changed {@code stage} ("in 3 days" to "tomorrow" to
     * "today" to overdue) marks the row unread again; a row the user cleared stays cleared unless the stage changed
     * to {@link #STAGE_OVERDUE}. A row the system auto-resolved comes back, unread, when it is due again. An
     * unchanged reminder writes nothing. Never throws; returns false when the write failed.
     */
    public boolean upsertAll(UUID userId, List<Reminder> reminders) {
        if (reminders == null || reminders.isEmpty()) {
            return true;
        }
        try {
            isolated.executeWithoutResult(status -> doUpsertAll(userId, reminders));
            return true;
        } catch (Exception e) {
            log.warn("Feed upsert failed (user {}, {} reminder(s)): {}", userId, reminders.size(), e.getMessage());
            return false;
        }
    }

    private void doUpsertAll(UUID userId, List<Reminder> reminders) {
        Map<String, AppNotification> existing = new HashMap<>();
        List<String> keys = reminders.stream().map(r -> clip(r.dedupeKey(), AppNotification.MAX_KEY_LENGTH)).toList();
        for (int i = 0; i < keys.size(); i += 900) {
            repository.findByUserIdAndDedupeKeyIn(userId, keys.subList(i, Math.min(i + 900, keys.size())))
                    .forEach(n -> existing.put(n.getDedupeKey(), n));
        }
        Instant now = now();
        List<AppNotification> toSave = new ArrayList<>();
        for (Reminder reminder : reminders) {
            String key = clip(reminder.dedupeKey(), AppNotification.MAX_KEY_LENGTH);
            String title = clip(reminder.title(), AppNotification.MAX_TITLE_LENGTH);
            String body = reminder.body() == null || reminder.body().isBlank() ? null : clip(reminder.body(), AppNotification.MAX_BODY_LENGTH);
            AppNotification n = existing.get(key);
            if (n == null) {
                n = new AppNotification();
                n.setUserId(userId);
                n.setKind(reminder.kind());
                n.setDedupeKey(key);
                n.setCreatedAt(now);
                n.setUpdatedAt(now);
                applyRefs(n, reminder.refs());
                n.setTitle(title);
                n.setBody(body);
                n.setStage(reminder.stage());
                toSave.add(n);
                continue;
            }
            boolean stageChanged = !Objects.equals(n.getStage(), reminder.stage());
            boolean textChanged = !Objects.equals(n.getTitle(), title) || !Objects.equals(n.getBody(), body);
            boolean autoCleared = n.getClearedAt() != null && AppNotification.CLEARED_BY_AUTO.equals(n.getClearedBy());
            if (!stageChanged && !textChanged && !autoCleared) {
                continue;
            }
            n.setTitle(title);
            n.setBody(body);
            n.setStage(reminder.stage());
            n.setUpdatedAt(now);
            applyRefs(n, reminder.refs());
            if (autoCleared) {
                // Resolved by the system (bill paid, EMI recorded) and due again: back in the feed, unread.
                n.setClearedAt(null);
                n.setClearedBy(null);
                n.setReadAt(null);
            }
            if (stageChanged) {
                n.setReadAt(null);
                if (STAGE_OVERDUE.equals(reminder.stage())) {
                    n.setClearedAt(null);
                    n.setClearedBy(null);
                }
            }
            toSave.add(n);
        }
        if (!toSave.isEmpty()) {
            repository.saveAll(toSave);
        }
    }

    /** Auto-resolve: clears the row with this key if it is still in the feed. Never throws. */
    public void resolve(UUID userId, String dedupeKey) {
        try {
            isolated.executeWithoutResult(status -> repository.findByUserIdAndDedupeKey(userId, dedupeKey)
                    .filter(n -> n.getClearedAt() == null)
                    .ifPresent(n -> {
                        n.setClearedAt(now());
                        n.setClearedBy(AppNotification.CLEARED_BY_AUTO);
                        repository.save(n);
                    }));
        } catch (Exception e) {
            log.warn("Feed resolve failed (user {}, key {}): {}", userId, dedupeKey, e.getMessage());
        }
    }

    /** Auto-resolve for every row of this user whose key starts with {@code prefix} (a closed or deleted loan's EMIs). Never throws. */
    public void resolvePrefix(UUID userId, String prefix) {
        try {
            isolated.executeWithoutResult(status -> {
                Instant now = now();
                List<AppNotification> rows = repository.findByUserIdAndDedupeKeyStartingWithAndClearedAtIsNull(userId, prefix);
                rows.forEach(n -> {
                    n.setClearedAt(now);
                    n.setClearedBy(AppNotification.CLEARED_BY_AUTO);
                });
                if (!rows.isEmpty()) {
                    repository.saveAll(rows);
                }
            });
        } catch (Exception e) {
            log.warn("Feed prefix resolve failed (user {}, prefix {}): {}", userId, prefix, e.getMessage());
        }
    }

    /**
     * Auto-resolve for a producer's whole set: every row of {@code kinds} still in the feed whose key is not in
     * {@code keepKeys} is cleared (the bill was paid, the installment recorded, the loan closed). Returns how many.
     * Never throws; returns -1 when it failed.
     */
    public int resolveAllExcept(UUID userId, Collection<String> kinds, Set<String> keepKeys) {
        try {
            Integer resolved = isolated.execute(status -> {
                Instant now = now();
                List<AppNotification> stale = repository.findByUserIdAndKindInAndClearedAtIsNull(userId, kinds).stream()
                        .filter(n -> !keepKeys.contains(n.getDedupeKey()))
                        .toList();
                stale.forEach(n -> {
                    n.setClearedAt(now);
                    n.setClearedBy(AppNotification.CLEARED_BY_AUTO);
                });
                if (!stale.isEmpty()) {
                    repository.saveAll(stale);
                }
                return stale.size();
            });
            return resolved == null ? 0 : resolved;
        } catch (Exception e) {
            log.warn("Feed resolve-all failed (user {}): {}", userId, e.getMessage());
            return -1;
        }
    }

    private static void applyRefs(AppNotification n, AppNotificationRefs refs) {
        AppNotificationRefs r = refs == null ? AppNotificationRefs.NONE : refs;
        n.setJobId(r.jobId());
        n.setJobType(r.jobType());
        n.setAccountId(r.accountId());
        n.setConnectionId(r.connectionId());
        n.setLoanId(r.loanId());
        n.setStatementId(r.statementId());
    }

    /** Newest first by when each row last surfaced (updated, else created; id breaks ties so paging is stable), cleared rows excluded. */
    @Transactional(readOnly = true)
    public Page<AppNotification> list(UUID userId, Pageable pageable) {
        return repository.findFeed(userId, PageRequest.of(pageable.getPageNumber(), pageable.getPageSize()));
    }

    @Transactional(readOnly = true)
    public long unreadCount(UUID userId) {
        return repository.countByUserIdAndClearedAtIsNullAndReadAtIsNull(userId);
    }

    /** Marks unread rows created at or before {@code before} (default: now) as read. */
    @Transactional
    public int markRead(UUID userId, Instant before) {
        Instant now = now();
        return repository.markRead(userId, before == null ? now : before, now);
    }

    @Transactional
    public void clear(UUID userId, UUID id) {
        AppNotification n = find(userId, id);
        n.setClearedAt(now());
        n.setClearedBy(AppNotification.CLEARED_BY_USER);
        repository.save(n);
    }

    @Transactional
    public void restore(UUID userId, UUID id) {
        AppNotification n = find(userId, id);
        n.setClearedAt(null);
        n.setClearedBy(null);
        repository.save(n);
    }

    /** Clears every row still in the feed; returns how many. */
    @Transactional
    public int clearAll(UUID userId) {
        return repository.clearAll(userId, now());
    }

    /** Daily: rows older than 90 days, and rows cleared more than a day ago, go for good. */
    @Scheduled(cron = "${notifications.feed.purge-cron:0 45 3 * * *}", zone = "${app.zone:Asia/Kolkata}")
    @Transactional
    public int purge() {
        Instant now = now();
        int deleted = repository.purge(now.minus(Duration.ofDays(RETENTION_DAYS)), now.minus(Duration.ofDays(CLEARED_RETENTION_DAYS)));
        if (deleted > 0) {
            log.info("Notification feed purge deleted {} row(s)", deleted);
        }
        return deleted;
    }

    private AppNotification find(UUID userId, UUID id) {
        return repository.findByIdAndUserId(id, userId).orElseThrow(() -> new ResourceNotFoundException("Notification", id));
    }

    private static Instant now() {
        return AppTime.now().atZone(AppTime.zone()).toInstant();
    }

    private static String clip(String text, int max) {
        return text == null || text.length() <= max ? text : text.substring(0, max - 1).stripTrailing() + "…";
    }
}
