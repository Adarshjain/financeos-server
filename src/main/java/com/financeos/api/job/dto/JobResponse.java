package com.financeos.api.job.dto;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.financeos.domain.job.Job;
import com.financeos.domain.job.JobStatus;
import com.financeos.domain.job.JobTrigger;
import com.financeos.domain.job.JobType;

import java.time.Instant;
import java.util.UUID;

public record JobResponse(
        UUID id,
        JobType type,
        JobStatus status,
        JobTrigger triggerSource,
        Integer progressCurrent,
        Integer progressTotal,
        String progressNote,
        String errorCode,
        String errorMessage,
        JsonNode result,
        boolean cancelRequested,
        int attempt,
        Instant createdAt,
        Instant startedAt,
        Instant finishedAt
) {
    public static JobResponse from(Job job, ObjectMapper mapper) {
        JsonNode resultNode = null;
        if (job.getResult() != null && !job.getResult().isBlank()) {
            try {
                resultNode = mapper.readTree(job.getResult());
            } catch (Exception e) {
                // Ignore parsing errors
            }
        }
        return new JobResponse(
                job.getId(),
                job.getType(),
                job.getStatus(),
                job.getTriggerSource(),
                job.getProgressCurrent(),
                job.getProgressTotal(),
                job.getProgressNote(),
                job.getErrorCode(),
                job.getErrorMessage(),
                resultNode,
                job.isCancelRequested(),
                job.getAttempt(),
                job.getCreatedAt(),
                job.getStartedAt(),
                job.getFinishedAt()
        );
    }
}
