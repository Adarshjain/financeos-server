package com.financeos.api.nps.dto;

import com.financeos.domain.nps.NpsCra;
import com.financeos.domain.nps.NpsEntryType;
import com.financeos.domain.nps.NpsRelation;
import com.financeos.domain.nps.NpsSector;
import com.financeos.domain.nps.NpsTier;
import io.swagger.v3.oas.annotations.media.Schema;
import org.springframework.lang.Nullable;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * The preview of one NPS statement upload. With {@code passwordNeeded} nothing else is set: the file is locked
 * and none of the passwords tried opened it. Otherwise {@code previewId} is what the commit sends back.
 */
public record NpsImportPreviewResponse(
        @Nullable UUID previewId,
        boolean passwordNeeded,
        @Nullable Statement statement,
        @Nullable Account account,
        @Nullable NpsImportResultResponse result,
        List<Row> rows) {

    @Schema(name = "NpsImportStatement")
    public record Statement(String pranMasked, @Nullable String holderName, LocalDate periodStart,
                            LocalDate periodEnd, @Nullable String layout, @Nullable String schemeChoice) {
    }

    /** The matching account ({@code id}), or the one a commit would create ({@code proposal}). */
    @Schema(name = "NpsImportAccount")
    public record Account(@Nullable UUID id, @Nullable String holderName, @Nullable NpsRelation relation,
                          @Nullable String pranLast4, @Nullable Proposal proposal) {
    }

    @Schema(name = "NpsImportProposal")
    public record Proposal(@Nullable String holderName, String pranMasked, NpsCra cra, @Nullable NpsSector sector,
                           NpsRelation relation) {
    }

    @Schema(name = "NpsImportRow")
    public record Row(LocalDate date, NpsTier tier, NpsEntryType type, @Nullable String description, @Nullable String schemeCode,
                      String schemeName, @Nullable BigDecimal units, @Nullable BigDecimal nav,
                      @Nullable BigDecimal amount, NpsImportAction action) {
    }
}
