package com.financeos.domain.notification.feed;

import com.financeos.core.exception.ResourceNotFoundException;
import com.financeos.core.time.AppTime;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
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
        n.setDedupeKey(clip(dedupeKey, AppNotification.MAX_KEY_LENGTH));
        n.setCreatedAt(now());
        repository.saveAndFlush(n);
    }

    /** Newest first (id breaks ties so paging is stable), cleared rows excluded. */
    @Transactional(readOnly = true)
    public Page<AppNotification> list(UUID userId, Pageable pageable) {
        Pageable sorted = PageRequest.of(pageable.getPageNumber(), pageable.getPageSize(), Sort.by(Sort.Direction.DESC, "createdAt").and(Sort.by(Sort.Direction.DESC, "id")));
        return repository.findByUserIdAndClearedAtIsNull(userId, sorted);
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
        repository.save(n);
    }

    @Transactional
    public void restore(UUID userId, UUID id) {
        AppNotification n = find(userId, id);
        n.setClearedAt(null);
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
