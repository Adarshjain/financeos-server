package com.financeos.api.nps.dto;

import com.financeos.domain.nps.NpsContributor;
import com.financeos.domain.nps.NpsEntrySource;
import com.financeos.domain.nps.NpsEntryStatus;
import com.financeos.domain.nps.NpsEntryType;
import com.financeos.domain.nps.NpsTier;
import io.swagger.v3.oas.annotations.media.Schema;
import org.springframework.lang.Nullable;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

public record NpsEntryResponse(
        UUID id,
        UUID accountId,
        NpsTier tier,
        NpsEntryType type,
        @Nullable NpsContributor contributor,
        @Nullable BigDecimal amount,
        LocalDate postedDate,
        @Nullable LocalDate navDate,
        NpsEntrySource source,
        NpsEntryStatus status,
        @Nullable String description,
        @Nullable String note,
        @Nullable UUID transactionId,
        List<Line> lines) {

    @Schema(name = "NpsEntryLineResponse")
    public record Line(String schemeCode, @Nullable String schemeName, BigDecimal units, @Nullable BigDecimal nav,
                       @Nullable BigDecimal amount) {
    }
}
