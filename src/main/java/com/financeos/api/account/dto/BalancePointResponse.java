package com.financeos.api.account.dto;

import java.math.BigDecimal;
import java.time.LocalDate;

/** An account's end-of-day balance on {@code date}. */
public record BalancePointResponse(LocalDate date, BigDecimal balance) {
}
