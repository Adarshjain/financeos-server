package com.financeos.api.job.dto;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.financeos.domain.job.Job;
import com.financeos.domain.job.JobStatus;
import com.financeos.domain.job.JobTrigger;
import com.financeos.domain.job.JobType;

import org.springframework.lang.Nullable;

import java.time.Instant;
import java.util.UUID;

public record JobResponse(
        UUID id,
        JobType type,
        JobStatus status,
        JobTrigger triggerSource,
        @Nullable Integer progressCurrent,
        @Nullable Integer progressTotal,
        @Nullable String progressNote,
        @Nullable String errorCode,
        @Nullable String errorMessage,
        @Nullable JsonNode result,
        boolean cancelRequested,
        int attempt,
        Instant createdAt,
        @Nullable Instant startedAt,
        @Nullable Instant finishedAt
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
