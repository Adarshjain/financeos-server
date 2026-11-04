package com.financeos.api.ingestion.dto;

import com.financeos.domain.ingestion.held.HeldUpload;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import org.springframework.lang.Nullable;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** Request and response bodies of the auto-detect upload, held uploads, password retry and statement move. */
public final class IngestDtos {

    private IngestDtos() {
    }

    /** Body of {@code POST /jobs/{jobId}/files/{fileIndex}/password}. */
    public record PasswordRetryRequest(@NotBlank String password, boolean saveToAccount) {}

    /** Body of {@code POST /held-uploads/{id}/assign}. */
    public record AssignHeldUploadRequest(@NotNull UUID accountId, @Nullable String password) {}

    /** Body of {@code POST /accounts/{accountId}/statements/{statementId}/move}. */
    public record MoveStatementRequest(@NotNull UUID targetAccountId) {}

    /** A file waiting for the user to pick its account. */
    public record HeldUploadResponse(
            UUID id,
            UUID jobId,
            String fileName,
            @Nullable String detectedBankId,
            @Nullable String detectedLast4,
            HeldUpload.Reason reason,
            List<UUID> candidateAccountIds,
            Instant createdAt
    ) {
        public static HeldUploadResponse from(HeldUpload h) {
            return new HeldUploadResponse(h.getId(), h.getJobId(), h.getFileName(), h.getDetectedBankId(), h.getDetectedLast4(),
                    h.getReason(), h.candidateIds(), h.getCreatedAt());
        }
    }
}
