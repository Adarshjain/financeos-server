package com.financeos.api.bill.dto;

import jakarta.validation.constraints.PositiveOrZero;
import java.math.BigDecimal;
import java.time.LocalDate;
import org.springframework.lang.Nullable;

/** Fill in what the parser missed; at least one field is required. */
public record UpdateBillDetailsRequest(
        @Nullable LocalDate paymentDueDate,
        @Nullable @PositiveOrZero BigDecimal totalAmountDue,
        @Nullable @PositiveOrZero BigDecimal minimumAmountDue) {
}
