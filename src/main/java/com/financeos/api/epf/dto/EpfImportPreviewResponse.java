package com.financeos.api.epf.dto;

import com.financeos.domain.epf.EpfEntryType;
import com.financeos.domain.epf.EpfWithdrawalKind;
import org.springframework.lang.Nullable;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/** What uploading a passbook would do, row by row, before anything is written. */
public record EpfImportPreviewResponse(
        EpfImportEmployer employer,
        List<EpfImportRow> rows,
        int newCount,
        int existsCount,
        int replacesCount,
        int unrecognisedLines,
        List<EpfEmployerOption> employerOptions) {

    public enum Status { NEW, EXISTS, REPLACES }

    /** The employer the rows go to; {@code existingId} is null when a new employer will be created. */
    public record EpfImportEmployer(
            @Nullable UUID existingId,
            String name,
            @Nullable String memberId,
            @Nullable String establishmentId,
            @Nullable String uanLast4) {
    }

    public record EpfImportRow(
            int index,
            EpfEntryType type,
            @Nullable EpfWithdrawalKind withdrawalKind,
            @Nullable LocalDate wageMonth,
            LocalDate creditedOn,
            @Nullable Integer interestFy,
            BigDecimal employeeAmount,
            BigDecimal employerAmount,
            BigDecimal pensionAmount,
            @Nullable String particulars,
            Status status,
            @Nullable UUID replacesEntryId) {
    }

    /** An employer without a member id yet, which the passbook could be added to instead of a new one. */
    public record EpfEmployerOption(UUID id, String name) {
    }
}
