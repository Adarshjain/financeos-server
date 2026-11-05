package com.financeos.api.nps.dto;

import com.financeos.domain.nps.NpsCra;
import com.financeos.domain.nps.NpsRelation;
import com.financeos.domain.nps.NpsSector;
import io.swagger.v3.oas.annotations.media.Schema;
import org.springframework.lang.Nullable;

import java.util.UUID;

/**
 * Commits a previewed statement into {@code accountId}, or into the preview's matched account when omitted, or
 * into a new account made from {@code newAccount} when the preview matched none.
 */
public record NpsImportCommitRequest(
        UUID previewId,
        @Nullable UUID accountId,
        @Nullable NewAccount newAccount) {

    @Schema(name = "NpsImportNewAccount")
    public record NewAccount(@Nullable String holderName, @Nullable NpsRelation relation, @Nullable NpsSector sector,
                             @Nullable NpsCra cra, @Nullable UUID employerId) {
    }
}
