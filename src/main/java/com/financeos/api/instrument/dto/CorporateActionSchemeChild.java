package com.financeos.api.instrument.dto;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;
import org.springframework.lang.Nullable;

/**
 * The child company of a demerger, or the acquirer of a merger, as the company's notices name it.
 *
 * @param yahooSymbol  the exchange symbol plus {@code .NS}
 * @param instrumentId an instrument already in the catalog with this ISIN or NSE symbol (NSE row preferred); null when none
 * @param ratioFrom    shares of the parent the ratio is stated for ("for every {@code ratioFrom} shares held"); null when not read
 * @param ratioTo      shares of this company given for them; null when not read
 * @param ratioSource  the notice the ratio was read from; null when not read
 * @param childCostPct this company's percentage of the parent's cost of acquisition; null when not published
 */
public record CorporateActionSchemeChild(
        String symbol,
        String name,
        @Nullable String isin,
        @Nullable LocalDate listedOn,
        String yahooSymbol,
        @Nullable UUID instrumentId,
        @Nullable Integer ratioFrom,
        @Nullable Integer ratioTo,
        @Nullable CorporateActionSchemeSource ratioSource,
        @Nullable BigDecimal childCostPct
) {
}
