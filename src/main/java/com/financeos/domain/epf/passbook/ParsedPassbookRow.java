package com.financeos.domain.epf.passbook;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.YearMonth;

/** One passbook entry. Amounts are always positive; direction is expressed by {@code type}. */
public record ParsedPassbookRow(
        int index,
        PassbookRowType type,
        PassbookWithdrawalKind withdrawalKind,
        YearMonth wageMonth,
        LocalDate creditedOn,
        Integer interestFy,
        BigDecimal employee,
        BigDecimal employer,
        BigDecimal pension,
        String particulars) {
}
