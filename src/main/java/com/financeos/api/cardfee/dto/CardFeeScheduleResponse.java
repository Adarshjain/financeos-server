package com.financeos.api.cardfee.dto;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

public record CardFeeScheduleResponse(
        List<FeeOccurrenceResponse> occurrences,
        BigDecimal totalAmortisedInRange,
        BigDecimal totalAmortisedToDate,
        boolean unanchoredFees,
        boolean unlinkedFeeCharges,
        boolean waiverSpendIncomplete,
        boolean notConfiguredFeeYears,
        List<LocalDate> orphanedFeeOverrides
) {
    public static CardFeeScheduleResponse empty() {
        return new CardFeeScheduleResponse(
                List.of(),
                BigDecimal.ZERO,
                BigDecimal.ZERO,
                false,
                false,
                false,
                false,
                List.of()
        );
    }
}
