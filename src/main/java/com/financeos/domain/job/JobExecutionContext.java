package com.financeos.domain.job;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.List;
import java.util.UUID;

public class JobExecutionContext {

    private final UUID jobId;
    private final UUID userId;
    private final String payloadJson;
    private final JobService jobService;
    private final ObjectMapper objectMapper;

    private long lastProgressMs = 0;
    private long lastCancelCheckMs = 0;
    private boolean lastCancelCheckResult = false;

    public JobExecutionContext(UUID jobId, UUID userId, String payloadJson, JobService jobService, ObjectMapper objectMapper) {
        this.jobId = jobId;
        this.userId = userId;
        this.payloadJson = payloadJson;
        this.jobService = jobService;
        this.objectMapper = objectMapper;
    }

    public UUID getJobId() {
        return jobId;
    }

    public UUID getUserId() {
        return userId;
    }

    public <T> T payload(Class<T> type) {
        if (payloadJson == null || payloadJson.isBlank()) {
            return null;
        }
        try {
            return objectMapper.readValue(payloadJson, type);
        } catch (Exception e) {
            throw new RuntimeException("Failed to deserialize job payload", e);
        }
    }

    public List<JobArtifact> artifacts() {
        return jobService.getArtifacts(jobId);
    }

    public void progress(int current, int total, String note) {
        long now = System.currentTimeMillis();
        if (current == total || (now - lastProgressMs >= 500)) {
            lastProgressMs = now;
            String sanitizedNote = note;
            if (sanitizedNote != null && sanitizedNote.length() > 255) {
                sanitizedNote = sanitizedNote.substring(0, 255);
            }
            jobService.updateProgress(jobId, current, total, sanitizedNote);
        }
    }

    public void checkCancelled() {
        long now = System.currentTimeMillis();
        if (now - lastCancelCheckMs >= 500) {
            lastCancelCheckMs = now;
            lastCancelCheckResult = jobService.isCancelRequested(jobId);
        }
        if (lastCancelCheckResult) {
            throw new JobCancelledException("Job execution cancelled");
        }
    }
}
