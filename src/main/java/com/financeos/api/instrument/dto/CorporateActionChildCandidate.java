package com.financeos.api.instrument.dto;

import java.time.LocalDate;
import java.util.UUID;
import org.springframework.lang.Nullable;

/**
 * A possible child company of a demerger, found in the exchange's list of new listings. Never applied by itself.
 *
 * @param yahooSymbol  the exchange symbol plus {@code .NS}
 * @param instrumentId an instrument already in the catalog with this ISIN or NSE symbol; null when there is none
 */
public record CorporateActionChildCandidate(
        String symbol,
        String name,
        LocalDate listedOn,
        @Nullable String isin,
        String yahooSymbol,
        @Nullable UUID instrumentId
) {
}
