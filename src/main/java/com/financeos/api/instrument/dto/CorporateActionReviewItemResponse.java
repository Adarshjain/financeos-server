package com.financeos.api.instrument.dto;

import com.financeos.domain.instrument.corporateaction.detect.SuggestionKind;
import com.financeos.domain.instrument.corporateaction.detect.SuggestionReason;
import com.financeos.domain.instrument.corporateaction.detect.SuggestionStatus;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.springframework.lang.Nullable;

/**
 * One detected corporate action with the evidence the user decides on.
 *
 * @param source         {@code NSE} for an event from the exchange feed; null for an oversold finding
 * @param feedFactor     the price feed's split factor for the ex-date; null when it had none or was not consulted
 * @param exchangeFactor the share multiplier the exchange notice implies (split or bonus); null otherwise
 * @param sharesBefore   the user's open quantity of the instrument (all holdings) the day before the ex-date
 *                       (for oversold: before the first oversold sell)
 * @param sharesAfter    {@code sharesBefore x exchangeFactor} for a split or bonus; null otherwise
 * @param avgCostBefore  average cost per share of that open quantity; null when it cannot be computed
 * @param avgCostAfter   {@code avgCostBefore / exchangeFactor} (total cost is unchanged); null when not computable
 * @param soldQty        oversold only: the quantity sold beyond the lots held
 * @param sellDate       oversold only: the first oversold sell date
 * @param possibleChildren demerger only (pending): companies the exchange listed after the ex-date with a similar name; never empty-null
 */
public record CorporateActionReviewItemResponse(
        UUID id,
        SuggestionKind kind,
        SuggestionStatus status,
        @Nullable SuggestionReason reason,
        LocalDate exDate,
        @Nullable Integer ratioFrom,
        @Nullable Integer ratioTo,
        @Nullable String subject,
        ReviewInstrument instrument,
        @Nullable UUID corporateActionId,
        Instant createdAt,
        @Nullable Instant resolvedAt,
        @Nullable String source,
        @Nullable BigDecimal feedFactor,
        @Nullable BigDecimal exchangeFactor,
        @Nullable BigDecimal sharesBefore,
        @Nullable BigDecimal sharesAfter,
        @Nullable BigDecimal avgCostBefore,
        @Nullable BigDecimal avgCostAfter,
        @Nullable BigDecimal soldQty,
        @Nullable LocalDate sellDate,
        List<CorporateActionChildCandidate> possibleChildren
) {
    public record ReviewInstrument(UUID id, String name, @Nullable String symbol, @Nullable String exchange) {
    }
}
