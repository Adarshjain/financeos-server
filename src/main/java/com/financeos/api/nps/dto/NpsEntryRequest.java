package com.financeos.api.nps.dto;

import com.financeos.domain.nps.NpsContributor;
import com.financeos.domain.nps.NpsEntryType;
import com.financeos.domain.nps.NpsTier;
import io.swagger.v3.oas.annotations.media.Schema;
import org.springframework.lang.Nullable;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * A manual ledger entry. {@code amount} is signed (money in positive; a withdrawal is stored negative whichever
 * sign is sent). A CONTRIBUTION without {@code lines} is split by the account's allocation for the tier and its
 * units estimated at the NAV on {@code postedDate}; every other type except OTHER needs lines.
 */
public record NpsEntryRequest(
        UUID accountId,
        NpsTier tier,
        NpsEntryType type,
        @Nullable NpsContributor contributor,
        @Nullable BigDecimal amount,
        LocalDate postedDate,
        @Nullable LocalDate navDate,
        @Nullable String description,
        @Nullable String note,
        @Nullable List<Line> lines) {

    /** Units of one scheme (signed); {@code nav} is optional and, with it, the line amount is units times nav. */
    @Schema(name = "NpsEntryLineRequest")
    public record Line(String schemeCode, BigDecimal units, @Nullable BigDecimal nav) {
    }
}
