package com.financeos.domain.categorization;

import com.financeos.core.time.AppTime;
import com.financeos.domain.job.JobExecutionContext;
import com.financeos.domain.job.JobService;
import com.financeos.domain.job.JobTrigger;
import com.financeos.domain.job.JobType;
import com.financeos.domain.transaction.Transaction;
import com.financeos.domain.transaction.TransactionRepository;
import com.financeos.domain.user.UserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.core.annotation.Order;
import org.springframework.data.domain.PageRequest;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;
import java.util.UUID;

/**
 * The one-off category backfill: walks every user's never-categorised transactions (no category and no
 * category source, so a row the user cleared is left alone) in id-ordered pages and gives each the first answer
 * of rules, links, engine through {@link CategoryAssigner#backfill}. is_excluded is never changed on these
 * existing rows, so today's totals stay put.
 * <p>
 * Resumable and run-once: each page and its progress row (last id walked past, counts) commit together, so a
 * restart carries on from the last page; a completion marker stops it being enqueued again. Startup enqueues one
 * system job (dedup key) only while the marker is missing. Pages are idempotent, so two instances racing at most
 * repeat work.
 */
@Service
public class CategoryBackfillService {

    private static final Logger log = LoggerFactory.getLogger(CategoryBackfillService.class);

    public static final String KEY = "CATEGORY_V1";
    static final String DEDUP_KEY = "category-backfill-v1";
    static final int PAGE_SIZE = 500;

    /** What one run did, for the job result (counts only). */
    public record Result(int users, int scanned, int categorised, int failed) {
    }

    private final CategoryBackfillProgressRepository progressRepository;
    private final TransactionRepository transactionRepository;
    private final UserRepository userRepository;
    private final CategoryAssigner assigner;
    private final JobService jobService;
    private final TransactionTemplate tx;
    private final boolean enabled;

    public CategoryBackfillService(CategoryBackfillProgressRepository progressRepository,
                                   TransactionRepository transactionRepository, UserRepository userRepository,
                                   CategoryAssigner assigner, JobService jobService,
                                   PlatformTransactionManager transactionManager,
                                   @Value("${categorize.backfill.enabled:true}") boolean enabled) {
        this.progressRepository = progressRepository;
        this.transactionRepository = transactionRepository;
        this.userRepository = userRepository;
        this.assigner = assigner;
        this.jobService = jobService;
        this.tx = new TransactionTemplate(transactionManager);
        this.enabled = enabled;
    }

    public boolean isCompleted() {
        return progressRepository.findById(new CategoryBackfillProgress.Key(KEY, CategoryBackfillProgress.RUN))
                .map(CategoryBackfillProgress::isDone).orElse(false);
    }

    /**
     * Ordered after the startup sweep that fails jobs a restart interrupted, so an interrupted run is
     * enqueued again; the dedup key keeps it to one live job.
     */
    @EventListener(ApplicationReadyEvent.class)
    @Order(org.springframework.core.Ordered.LOWEST_PRECEDENCE)
    public void enqueueIfPending() {
        if (!enabled) {
            return;
        }
        try {
            if (isCompleted()) {
                return;
            }
            jobService.enqueue(null, JobType.CATEGORY_BACKFILL, JobTrigger.CRON, null, null, DEDUP_KEY);
            log.info("Category backfill enqueued");
        } catch (Exception e) {
            log.warn("Could not enqueue the category backfill: {}", e.getMessage());
        }
    }

    /** Runs (or resumes) the backfill for every user, then writes the completion marker. */
    public Result run(JobExecutionContext ctx) {
        List<UUID> users = userRepository.findAllIds();
        int scanned = 0;
        int categorised = 0;
        int failed = 0;
        int done = 0;
        for (UUID userId : users) {
            if (ctx != null) {
                ctx.checkCancelled();
            }
            int[] counts;
            try {
                counts = runUser(userId, ctx);
            } catch (com.financeos.domain.job.JobCancelledException e) {
                throw e;
            } catch (RuntimeException e) {
                log.error("Category backfill user={} skipped: {}", userId, e.toString());
                counts = new int[]{0, 0, 1};
            }
            scanned += counts[0];
            categorised += counts[1];
            failed += counts[2];
            done++;
            if (ctx != null) {
                ctx.progress(done, users.size(), "Categorising transactions");
            }
        }
        final int runScanned = scanned;
        final int runCategorised = categorised;
        tx.executeWithoutResult(s -> {
            CategoryBackfillProgress marker = progressRepository
                    .findById(new CategoryBackfillProgress.Key(KEY, CategoryBackfillProgress.RUN))
                    .orElseGet(() -> new CategoryBackfillProgress(KEY, CategoryBackfillProgress.RUN));
            marker.setDone(true);
            marker.setScanned(marker.getScanned() + runScanned);
            marker.setCategorised(marker.getCategorised() + runCategorised);
            marker.setUpdatedAt(AppTime.nowInstant());
            progressRepository.save(marker);
        });
        log.info("Category backfill finished: users={}, scanned={}, categorised={}, failed={}", users.size(), scanned, categorised, failed);
        return new Result(users.size(), scanned, categorised, failed);
    }

    /** Pages through one user's rows from where the last run stopped. Returns {scanned, categorised, failed} of this run. */
    public int[] runUser(UUID userId, JobExecutionContext ctx) {
        CategoryBackfillProgress.Key key = new CategoryBackfillProgress.Key(KEY, userId.toString());
        int scanned = 0;
        int categorised = 0;
        int failed = 0;
        while (true) {
            if (ctx != null) {
                ctx.checkCancelled();
            }
            int[] page;
            try {
                page = tx.execute(s -> processPage(userId, key));
            } catch (RuntimeException e) {
                // The page rolled back as a whole (nothing of it persisted). Retry its rows one by one so
                // only the bad row is skipped, and always move past the page.
                log.error("Category backfill user={}: a page failed, retrying its rows one by one: {}", userId, e.toString());
                page = retryRowByRow(userId, key);
            }
            if (page == null || page[0] < 0) {
                break;
            }
            scanned += page[0];
            categorised += page[1];
            failed += page[2];
        }
        CategoryBackfillProgress finished = scanned == 0 ? null : progressRepository.findById(key).orElse(null);
        if (finished != null) {
            log.info("Category backfill user={}: scanned={}, categorised={}, failed={} (all runs: scanned={}, categorised={})",
                    userId, scanned, categorised, failed, finished.getScanned(), finished.getCategorised());
        }
        return new int[]{scanned, categorised, failed};
    }

    /** One page and its progress row, in the caller's transaction. {-1,0,0} when the user is finished. */
    private int[] processPage(UUID userId, CategoryBackfillProgress.Key key) {
        CategoryBackfillProgress progress = progressRepository.findById(key)
                .orElseGet(() -> new CategoryBackfillProgress(KEY, userId.toString()));
        if (progress.isDone()) {
            return new int[]{-1, 0, 0};
        }
        List<Transaction> rows = nextPage(userId, progress);
        if (rows.isEmpty()) {
            progress.setDone(true);
            progress.setUpdatedAt(AppTime.nowInstant());
            progressRepository.save(progress);
            return new int[]{-1, 0, 0};
        }
        List<Transaction> changed = assigner.backfill(rows);
        if (!changed.isEmpty()) {
            transactionRepository.saveAll(changed);
        }
        progress.setLastTxnId(rows.get(rows.size() - 1).getId());
        progress.setScanned(progress.getScanned() + rows.size());
        progress.setCategorised(progress.getCategorised() + changed.size());
        progress.setUpdatedAt(AppTime.nowInstant());
        progressRepository.save(progress);
        return new int[]{rows.size(), changed.size(), 0};
    }

    private List<Transaction> nextPage(UUID userId, CategoryBackfillProgress progress) {
        PageRequest size = PageRequest.of(0, PAGE_SIZE);
        return progress.getLastTxnId() == null
                ? transactionRepository.findBackfillFirstPage(userId, size)
                : transactionRepository.findBackfillPageAfter(userId, progress.getLastTxnId(), size);
    }

    /** The failed page, row by row (each in its own transaction), then the progress row moves past it. */
    private int[] retryRowByRow(UUID userId, CategoryBackfillProgress.Key key) {
        List<UUID> ids = tx.execute(s -> {
            CategoryBackfillProgress progress = progressRepository.findById(key)
                    .orElseGet(() -> new CategoryBackfillProgress(KEY, userId.toString()));
            return nextPage(userId, progress).stream().map(Transaction::getId).toList();
        });
        if (ids == null || ids.isEmpty()) {
            return new int[]{-1, 0, 0};
        }
        int categorised = 0;
        int failed = 0;
        for (UUID id : ids) {
            try {
                Integer n = tx.execute(s -> {
                    Transaction row = transactionRepository.findById(id).orElse(null);
                    if (row == null || !userId.equals(row.getUser().getId())) {
                        return 0;
                    }
                    List<Transaction> changed = assigner.backfill(List.of(row));
                    if (!changed.isEmpty()) {
                        transactionRepository.saveAll(changed);
                    }
                    return changed.size();
                });
                categorised += n == null ? 0 : n;
            } catch (RuntimeException e) {
                failed++;
                log.error("Category backfill user={}: skipped transaction {}: {}", userId, id, e.toString());
            }
        }
        final int pageCategorised = categorised;
        final UUID last = ids.get(ids.size() - 1);
        tx.executeWithoutResult(s -> {
            CategoryBackfillProgress progress = progressRepository.findById(key)
                    .orElseGet(() -> new CategoryBackfillProgress(KEY, userId.toString()));
            progress.setLastTxnId(last);
            progress.setScanned(progress.getScanned() + ids.size());
            progress.setCategorised(progress.getCategorised() + pageCategorised);
            progress.setUpdatedAt(AppTime.nowInstant());
            progressRepository.save(progress);
        });
        return new int[]{ids.size(), categorised, failed};
    }
}
