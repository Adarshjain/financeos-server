package com.financeos.domain.job;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.financeos.core.observability.Events;
import com.financeos.core.observability.JobRunContext;
import com.financeos.core.observability.ObservabilityMetrics;
import com.financeos.core.security.UserContext;
import net.logstash.logback.argument.StructuredArguments;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicInteger;

@Component
public class JobWorker {

    private static final Logger log = LoggerFactory.getLogger(JobWorker.class);

    private final JobRepository jobRepository;
    private final JobService jobService;
    private final JobHandlerRegistry handlerRegistry;
    private final ObservabilityMetrics observabilityMetrics;
    private final Executor jobExecutor;
    private final ObjectMapper objectMapper;
    private final int concurrency;

    private final AtomicInteger inFlight = new AtomicInteger(0);

    public JobWorker(JobRepository jobRepository,
                     JobService jobService,
                     JobHandlerRegistry handlerRegistry,
                     ObservabilityMetrics observabilityMetrics,
                     @Qualifier("jobExecutor") Executor jobExecutor,
                     ObjectMapper objectMapper,
                     @Value("${jobs.worker.concurrency:2}") int concurrency) {
        this.jobRepository = jobRepository;
        this.jobService = jobService;
        this.handlerRegistry = handlerRegistry;
        this.observabilityMetrics = observabilityMetrics;
        this.jobExecutor = jobExecutor;
        this.objectMapper = objectMapper;
        this.concurrency = concurrency;
    }

    public int getInFlightCount() {
        return inFlight.get();
    }

    public void poke() {
        jobExecutor.execute(this::pollInternal);
    }

    @Scheduled(fixedDelayString = "${jobs.worker.poll-delay-ms:2000}")
    public void poll() {
        pollInternal();
    }

    private synchronized void pollInternal() {
        int availableSlots = concurrency - inFlight.get();
        if (availableSlots <= 0) {
            return;
        }

        List<Job> pendingJobs = jobRepository.findByStatusOrderByCreatedAtAsc(JobStatus.PENDING, PageRequest.of(0, availableSlots));
        for (Job job : pendingJobs) {
            boolean claimed = jobService.claim(job.getId());
            if (claimed) {
                inFlight.incrementAndGet();
                final UUID jobId = job.getId();
                final UUID userId = job.getUserId();
                final JobType type = job.getType();
                final JobTrigger trigger = job.getTriggerSource();
                final String payload = job.getPayload();

                try {
                    jobExecutor.execute(() -> runJob(jobId, userId, type, trigger, payload));
                } catch (RejectedExecutionException e) {
                    inFlight.decrementAndGet();
                    jobService.fail(jobId, "EXECUTOR_REJECTED", "Worker queue full; job not executed");
                    log.error("Job execution rejected by executor: jobId={}", jobId, e);
                    break;
                }
            }
        }
    }

    private void runJob(UUID jobId, UUID userId, JobType type, JobTrigger trigger, String payload) {
        String shortJobId = jobId.toString().substring(0, 8);
        String jobName = getJobName(type);

        try (JobRunContext ctx = JobRunContext.start(shortJobId)) {
            if (userId != null) {
                UserContext.setCurrentUserId(userId);
                MDC.put("userId", userId.toString());
            }

            if (jobService.isCancelRequested(jobId)) {
                jobService.markCancelled(jobId);
                log.info("Job cancelled before start: jobName={}, jobRunId={}, trigger={}",
                        jobName, shortJobId, trigger != null ? trigger.name().toLowerCase() : "unknown");
                return;
            }

            log.info("Job started: jobName={}, trigger={}", jobName, trigger != null ? trigger.name().toLowerCase() : "unknown",
                    StructuredArguments.keyValue("event", Events.JOB_STARTED),
                    StructuredArguments.keyValue("jobName", jobName),
                    StructuredArguments.keyValue("jobRunId", shortJobId),
                    StructuredArguments.keyValue("trigger", trigger != null ? trigger.name().toLowerCase() : "unknown"));

            long startMs = System.currentTimeMillis();
            try {
                JobHandler handler = handlerRegistry.get(type);
                JobExecutionContext execCtx = new JobExecutionContext(jobId, userId, payload, jobService, objectMapper);
                Object result = handler.execute(execCtx);
                String resultJson = objectMapper.writeValueAsString(result);
                jobService.succeed(jobId, resultJson);

                long durationMs = System.currentTimeMillis() - startMs;
                observabilityMetrics.recordJobSuccess(jobName);

                log.info("Job completed: jobName={}, durationMs={}", jobName, durationMs,
                        StructuredArguments.keyValue("event", Events.JOB_COMPLETED),
                        StructuredArguments.keyValue("jobName", jobName),
                        StructuredArguments.keyValue("jobRunId", shortJobId),
                        StructuredArguments.keyValue("durationMs", durationMs));
            } catch (JobCancelledException e) {
                jobService.markCancelled(jobId);
                log.info("Job completed-with-cancel: jobName={}, jobRunId={}", jobName, shortJobId);
            } catch (Exception e) {
                long durationMs = System.currentTimeMillis() - startMs;
                String errorCode = e.getClass().getSimpleName();
                String errorMessage = e.getMessage() != null ? e.getMessage() : e.toString();
                jobService.fail(jobId, errorCode, errorMessage);
                observabilityMetrics.recordJobFailure(jobName);

                log.error("Job failed: jobName={}, durationMs={}", jobName, durationMs,
                        StructuredArguments.keyValue("event", Events.JOB_FAILED),
                        StructuredArguments.keyValue("jobName", jobName),
                        StructuredArguments.keyValue("jobRunId", shortJobId),
                        StructuredArguments.keyValue("durationMs", durationMs),
                        StructuredArguments.keyValue("errorClass", e.getClass().getName()),
                        e);
            }
        } finally {
            inFlight.decrementAndGet();
            UserContext.clear();
            MDC.remove("userId");
        }
    }

    public static String getJobName(JobType type) {
        if (type == null) {
            return "unknown-job";
        }
        switch (type) {
            case STATEMENT_INGEST: return "statement-ingest";
            case GMAIL_SYNC: return "gmail-ingest";
            case PRICE_REFRESH: return "price-refresh";
            case INVESTMENT_IMPORT_COMMIT: return "investment-import-commit";
            case BROKER_RECONCILE_COMMIT: return "broker-reconcile-commit";
            case RULE_APPLY: return "rule-apply";
            default: return type.name().toLowerCase().replace('_', '-');
        }
    }
}
