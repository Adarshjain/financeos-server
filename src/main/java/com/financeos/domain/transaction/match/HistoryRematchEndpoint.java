package com.financeos.domain.transaction.match;

import org.springframework.boot.actuate.endpoint.annotation.Endpoint;
import org.springframework.boot.actuate.endpoint.annotation.ReadOperation;
import org.springframework.boot.actuate.endpoint.annotation.Selector;
import org.springframework.boot.actuate.endpoint.annotation.WriteOperation;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.UUID;

/**
 * History re-match control at /actuator/rematch, like {@code /actuator/jobs}: only exposed in prod, where the
 * management server is bound to 127.0.0.1:8081, so it is reachable from the box itself and never through Caddy.
 *
 * <ul>
 *   <li>POST {@code {"dryRun": true, "userId": "…", "accountId": "…"}} (all optional; dryRun defaults to true) →
 *       starts a background job, {@code {"jobId": …}}; refused while one is running</li>
 *   <li>GET → the kept jobs' status and totals</li>
 *   <li>GET /{jobId} → that job's status, totals and per-user, per-account report</li>
 * </ul>
 */
@Component
@Endpoint(id = "rematch")
public class HistoryRematchEndpoint {

    private final HistoryRematchJobs jobs;

    public HistoryRematchEndpoint(HistoryRematchJobs jobs) {
        this.jobs = jobs;
    }

    @WriteOperation
    public Map<String, Object> start(@Nullable Boolean dryRun, @Nullable String userId, @Nullable String accountId) {
        boolean dry = dryRun == null || dryRun;
        UUID user;
        UUID account;
        try {
            user = blank(userId) ? null : UUID.fromString(userId.trim());
            account = blank(accountId) ? null : UUID.fromString(accountId.trim());
        } catch (IllegalArgumentException e) {
            return Map.of("error", "userId and accountId must be UUIDs");
        }
        HistoryRematchJobs.Job job = jobs.start(dry, user, account);
        if (job == null) {
            HistoryRematchJobs.Job current = jobs.current();
            return Map.of("error", "A re-match job is still running", "jobId", current == null ? "" : current.id());
        }
        return job.view(false);
    }

    @ReadOperation
    public Map<String, Object> list() {
        return Map.of("jobs", jobs.list());
    }

    @ReadOperation
    public Map<String, Object> job(@Selector String jobId) {
        HistoryRematchJobs.Job job = jobs.get(jobId);
        if (job == null) {
            return Map.of("error", "No such job (jobs are kept in memory until restart)", "jobId", jobId);
        }
        return job.view(true);
    }

    private static boolean blank(String s) {
        return s == null || s.isBlank();
    }
}
