package com.financeos.domain.job;

import java.time.Instant;
import java.util.UUID;

/** A job's identity for grouping repeats (owner, type, payload) and its finish time; no stack, no result. */
public record JobKeyRow(UUID id, UUID userId, JobType type, String payload, Instant finishedAt) {}
