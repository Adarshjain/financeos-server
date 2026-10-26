package com.financeos.api.search.dto;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

/** A credit card's current unpaid bill, as "Mark paid" needs it. */
public record SearchOpenBill(UUID statementId, BigDecimal remainingAmount, LocalDate paymentDueDate) {
}
