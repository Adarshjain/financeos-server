package com.financeos.api.bill.dto;

import jakarta.validation.constraints.Positive;
import java.math.BigDecimal;
import java.time.LocalDate;
import org.springframework.lang.Nullable;

/** {@code amount} null = paid in full; {@code paidOn} null = today. */
public record MarkBillPaidRequest(@Nullable @Positive BigDecimal amount, @Nullable LocalDate paidOn) {
}
