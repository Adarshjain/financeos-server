package com.financeos.domain.transaction.match;

import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Runs {@link HistoryRematchService} in the background, one job at a time, and keeps the last few jobs' reports in
 * memory (lost on restart) for {@link HistoryRematchEndpoint}.
 */
@Component
public class HistoryRematchJobs {

    private static final Logger log = LoggerFactory.getLogger(HistoryRematchJobs.class);
    private static final int KEEP_JOBS = 10;

    /** One job: its parameters, progress and, per account, what it did. */
    public static final class Job {
        final String id = UUID.randomUUID().toString();
        final boolean dryRun;
        final UUID userId;
        final UUID accountId;
        final Instant startedAt = Instant.now();
        volatile Instant finishedAt;
        volatile String status = "RUNNING";
        volatile String error;
        volatile int totalAccounts;
        final AtomicInteger doneAccounts = new AtomicInteger();
        final List<HistoryRematchService.AccountReport> accounts = new ArrayList<>();

        Job(boolean dryRun, UUID userId, UUID accountId) {
            this.dryRun = dryRun;
            this.userId = userId;
            this.accountId = accountId;
        }

        public String id() {
            return id;
        }

        public String status() {
            return status;
        }

        /** Status and totals; with {@code full}, the per-account reports (accounts that did nothing left out). */
        public synchronized Map<String, Object> view(boolean full) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("jobId", id);
            m.put("status", status);
            m.put("dryRun", dryRun);
            m.put("userId", userId == null ? null : userId.toString());
            m.put("accountId", accountId == null ? null : accountId.toString());
            m.put("startedAt", startedAt.toString());
            m.put("finishedAt", finishedAt == null ? null : finishedAt.toString());
            m.put("accountsDone", doneAccounts.get());
            m.put("accountsTotal", totalAccounts);
            if (error != null) m.put("error", error);
            Map<String, Integer> totals = new LinkedHashMap<>();
            Map<String, Integer> reasons = new LinkedHashMap<>();
            int failed = 0;
            int changed = 0;
            for (HistoryRematchService.AccountReport r : accounts) {
                r.counts().forEach((k, v) -> totals.merge(k, v, Integer::sum));
                r.reasonsCleared().forEach((k, v) -> reasons.merge(k, v, Integer::sum));
                if (r.error() != null) failed++;
                if (!r.isEmpty()) changed++;
            }
            m.put("totals", totals);
            m.put("reasonsCleared", reasons);
            m.put("accountsWithChanges", changed);
            m.put("accountsFailed", failed);
            if (full) {
                Map<String, List<HistoryRematchService.AccountReport>> byUser = new LinkedHashMap<>();
                for (HistoryRematchService.AccountReport r : accounts) {
                    if (r.isEmpty()) continue;
                    byUser.computeIfAbsent(r.userId(), k -> new ArrayList<>()).add(r);
                }
                m.put("users", byUser);
            }
            return m;
        }

        synchronized void add(HistoryRematchService.AccountReport r) {
            accounts.add(r);
        }
    }

    private final HistoryRematchService service;
    private final ExecutorService executor = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "history-rematch");
        t.setDaemon(true);
        return t;
    });
    private final Map<String, Job> jobs = new LinkedHashMap<>();
    private Job running;

    public HistoryRematchJobs(HistoryRematchService service) {
        this.service = service;
    }

    /** Starts a job, or returns null while another is still running. */
    public synchronized Job start(boolean dryRun, UUID userId, UUID accountId) {
        if (running != null && "RUNNING".equals(running.status)) {
            return null;
        }
        Job job = new Job(dryRun, userId, accountId);
        jobs.put(job.id, job);
        while (jobs.size() > KEEP_JOBS) {
            jobs.remove(jobs.keySet().iterator().next());
        }
        running = job;
        executor.submit(() -> run(job));
        return job;
    }

    public synchronized Job get(String id) {
        return jobs.get(id);
    }

    public synchronized Job current() {
        return running;
    }

    public synchronized List<Map<String, Object>> list() {
        List<Map<String, Object>> out = new ArrayList<>();
        for (Job j : jobs.values()) out.add(j.view(false));
        return out;
    }

    /** Runs a job to the end on the calling thread (the executor, or a test). */
    void run(Job job) {
        try {
            List<UUID[]> targets = service.targets(job.userId, job.accountId);
            job.totalAccounts = targets.size();
            log.info("History re-match started: jobId={}, dryRun={}, accounts={}", job.id, job.dryRun, targets.size());
            for (UUID[] t : targets) {
                job.add(service.runAccount(t[0], t[1], job.dryRun));
                job.doneAccounts.incrementAndGet();
            }
            job.status = "DONE";
        } catch (RuntimeException e) {
            log.error("History re-match failed: jobId={}", job.id, e);
            job.error = e.getClass().getSimpleName() + ": " + e.getMessage();
            job.status = "FAILED";
        } finally {
            job.finishedAt = Instant.now();
            log.info("History re-match finished: jobId={}, status={}, dryRun={}", job.id, job.status, job.dryRun);
        }
    }

    @PreDestroy
    void shutdown() {
        executor.shutdownNow();
    }
}
