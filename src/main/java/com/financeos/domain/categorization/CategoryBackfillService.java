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
    public record Result(int users, int scanned, int categorised) {
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
        int done = 0;
        for (UUID userId : users) {
            if (ctx != null) {
                ctx.checkCancelled();
            }
            int[] counts = runUser(userId, ctx);
            scanned += counts[0];
            categorised += counts[1];
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
        log.info("Category backfill finished: users={}, scanned={}, categorised={}", users.size(), scanned, categorised);
        return new Result(users.size(), scanned, categorised);
    }

    /** Pages through one user's rows from where the last run stopped. Returns {scanned, categorised} of this run. */
    public int[] runUser(UUID userId, JobExecutionContext ctx) {
        CategoryBackfillProgress.Key key = new CategoryBackfillProgress.Key(KEY, userId.toString());
        int scanned = 0;
        int categorised = 0;
        while (true) {
            if (ctx != null) {
                ctx.checkCancelled();
            }
            int[] page = tx.execute(s -> {
                CategoryBackfillProgress progress = progressRepository.findById(key)
                        .orElseGet(() -> new CategoryBackfillProgress(KEY, userId.toString()));
                if (progress.isDone()) {
                    return new int[]{-1, 0};
                }
                PageRequest size = PageRequest.of(0, PAGE_SIZE);
                List<Transaction> rows = progress.getLastTxnId() == null
                        ? transactionRepository.findBackfillFirstPage(userId, size)
                        : transactionRepository.findBackfillPageAfter(userId, progress.getLastTxnId(), size);
                if (rows.isEmpty()) {
                    progress.setDone(true);
                    progress.setUpdatedAt(AppTime.nowInstant());
                    progressRepository.save(progress);
                    return new int[]{-1, 0};
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
                return new int[]{rows.size(), changed.size()};
            });
            if (page == null || page[0] < 0) {
                break;
            }
            scanned += page[0];
            categorised += page[1];
        }
        CategoryBackfillProgress finished = scanned == 0 ? null : progressRepository.findById(key).orElse(null);
        if (finished != null) {
            log.info("Category backfill user={}: scanned={}, categorised={} (all runs: scanned={}, categorised={})",
                    userId, scanned, categorised, finished.getScanned(), finished.getCategorised());
        }
        return new int[]{scanned, categorised};
    }
}
