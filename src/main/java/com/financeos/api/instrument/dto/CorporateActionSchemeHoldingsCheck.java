package com.financeos.api.instrument.dto;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;
import org.springframework.lang.Nullable;

/**
 * Whether the user's holdings in one broker account match what the company's notices say a demerger or merger gave them.
 *
 * @param source      {@code SNAPSHOT} (the account's latest holdings file), {@code TRADEBOOK} (the child shares were sold straight
 *                    away and the sells add up) or {@code NONE} (no evidence)
 * @param asOf        the snapshot's date, or the last sell's date for a tradebook; null with no evidence
 * @param expectedQty the quantity the notices and the app's own trades lead to
 * @param foundQty    the quantity the snapshot shows (or the tradebook sold)
 */
public record CorporateActionSchemeHoldingsCheck(
        UUID brokerAccountId,
        String accountName,
        String source,
        @Nullable LocalDate asOf,
        BigDecimal expectedQty,
        BigDecimal foundQty,
        boolean agrees
) {
}
