package com.financeos.api.cardfee.dto;

import com.financeos.domain.cardfee.CardFeeKind;
import com.financeos.domain.cardfee.FeeOccurrenceStatus;
import com.financeos.domain.cardfee.FeeWaiverBasis;
import com.financeos.domain.cardfee.FeeWaiverSource;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

public record FeeOccurrenceResponse(
        CardFeeKind kind,
        FeeOccurrenceStatus status,
        LocalDate feeYearStart,
        LocalDate feeYearEnd,
        LocalDate amortiseFrom,
        LocalDate amortiseTo,
        LocalDate dueDate,
        BigDecimal baseAmount,
        BigDecimal gstRate,
        BigDecimal gstAmount,
        BigDecimal totalAmount,
        BigDecimal waiverSpendThreshold,
        FeeWaiverBasis waiverBasis,
        LocalDate waiverWindowStart,
        LocalDate waiverWindowEnd,
        BigDecimal spendConsidered,
        Boolean waived,
        FeeWaiverSource waiverSource,
        Boolean provisional,
        Boolean waiverSpendIncomplete,
        Boolean waiverContradictsLinkedCharge,
        BigDecimal netAmount,
        BigDecimal amortisedInRange,
        BigDecimal amortisedToDate,
        List<UUID> transactionIds,
        String note
) {
}
