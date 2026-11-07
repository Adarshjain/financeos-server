package com.financeos.api.imports.dto;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/**
 * One imported file or batch in the import history. {@code id} is kind-prefixed and stable;
 * {@code kind} is statement | nps_statement | epf_passbook | broker; {@code source} is upload | gmail;
 * {@code status} is done | failed | held.
 */
public record ImportRecordResponse(
        String id,
        String kind,
        String source,
        String status,
        String fileName,
        UUID accountId,
        String accountName,
        LocalDate periodStart,
        LocalDate periodEnd,
        Integer count,
        String errorMessage,
        Instant createdAt) {
}
