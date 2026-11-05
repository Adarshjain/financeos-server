package com.financeos.api.nps.dto;

import com.financeos.domain.nps.NpsTier;
import io.swagger.v3.oas.annotations.media.Schema;
import org.springframework.lang.Nullable;

import java.math.BigDecimal;
import java.util.List;

/** The scheme split of a tier ({@code choice} is the plan's name, e.g. "Life Cycle 75 - High"); percents sum to 100. */
public record NpsAllocationRequest(NpsTier tier, @Nullable String choice, List<Row> rows) {

    @Schema(name = "NpsAllocationRowRequest")
    public record Row(String schemeCode, BigDecimal percent) {
    }
}
