package com.financeos.api.investment.dto;

import java.math.BigDecimal;
import java.time.LocalDate;

public record SipProgressDto(
        int expectedInstallments,
        int executedInstallments,
        int missedInstallments,
        BigDecimal investedSoFar,
        BigDecimal unitsAccumulated,
        BigDecimal avgCost,
        LocalDate nextDueDate
) {
}
