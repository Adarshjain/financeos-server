package com.financeos.api.instrument.dto;

import com.financeos.api.instrument.dto.CorporateActionReviewItemResponse.ReviewInstrument;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import org.springframework.lang.Nullable;

/**
 * The Corporate actions page: what needs the user's review, what was applied on its own, and the held stocks that
 * no longer trade.
 *
 * @param needsReview         pending suggestions of every kind, newest ex-date first
 * @param appliedAutomatically suggestions applied on their own, newest ex-date first
 * @param notTrading          held stocks the price feeds no longer quote and no merger or delisting answers
 */
public record CorporateActionReviewResponse(
        List<CorporateActionReviewItemResponse> needsReview,
        List<CorporateActionReviewItemResponse> appliedAutomatically,
        List<NotTradingStock> notTrading
) {
    /**
     * @param sharesHeld    open quantity across all the user's holdings
     * @param lastPrice     latest stored price; null when none
     * @param lastPriceDate date of that price
     * @param markedAt      when the instrument was marked as not trading
     * @param details       what the company's notices say about a merger (the acquirer and ratio); null when none were read yet
     */
    public record NotTradingStock(
            ReviewInstrument instrument,
            BigDecimal sharesHeld,
            @Nullable BigDecimal lastPrice,
            @Nullable LocalDate lastPriceDate,
            @Nullable Instant markedAt,
            @Nullable CorporateActionSchemeDetails details
    ) {
    }
}
