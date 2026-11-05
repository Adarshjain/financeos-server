package com.financeos.api.admin.dto;

/** {@code affected}: Gmail rows reset or jobs retried; {@code skipped}: jobs that could not be retried; {@code jobsEnqueued}: new jobs. */
public record RetryAllResponse(int affected, int skipped, int jobsEnqueued) {}
