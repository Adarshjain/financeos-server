package com.financeos.api.transaction.dto;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * Uncategorised transactions to one payee in one direction. {@code groupKey} is stable for a label and direction
 * and safe in a URL; {@code person} marks a payee that is an individual; {@code oneOff} marks a group of one.
 */
public record UncategorizedGroupResponse(
        String groupKey,
        String label,
        boolean person,
        /** OUT (money out) or IN (money in). */
        String direction,
        long count,
        BigDecimal total,
        LocalDate firstDate,
        LocalDate lastDate,
        boolean oneOff) {
}
