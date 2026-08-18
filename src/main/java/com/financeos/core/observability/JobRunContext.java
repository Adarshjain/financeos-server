package com.financeos.core.observability;

import org.slf4j.MDC;

import java.util.UUID;

/**
 * Context manager for scheduled job execution scopes.
 * Binds a unique jobRunId to MDC for correlation across all downstream logs.
 */
public final class JobRunContext implements AutoCloseable {

    private final String jobRunId;

    private JobRunContext(String jobRunId) {
        this.jobRunId = jobRunId;
        MDC.put("jobRunId", jobRunId);
    }

    public static JobRunContext start() {
        return new JobRunContext(UUID.randomUUID().toString().substring(0, 8));
    }

    public static JobRunContext start(String explicitJobRunId) {
        return new JobRunContext(explicitJobRunId != null ? explicitJobRunId : UUID.randomUUID().toString().substring(0, 8));
    }

    public String getJobRunId() {
        return jobRunId;
    }

    @Override
    public void close() {
        MDC.remove("jobRunId");
    }
}
