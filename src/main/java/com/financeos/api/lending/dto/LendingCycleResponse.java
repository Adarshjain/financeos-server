package com.financeos.api.lending.dto;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * One derived lending cycle: entries from a zero balance until the balance next lands on zero.
 * lent / received sum the cycle's entries by direction; closing = lent - received.
 */
public record LendingCycleResponse(
        int number,
        LocalDate startDate,
        LocalDate endDate,
        boolean settled,
        int entryCount,
        BigDecimal lent,
        BigDecimal received,
        BigDecimal closing
) {
}
