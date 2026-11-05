package com.financeos.api.admin.dto;

/** Admin Failures tool: what is currently broken (not dismissed; jobs of the last 30 days). */
public record FailureSummaryResponse(long failedJobs, long failedGmailRetryable, long failedGmailPermanent) {}
