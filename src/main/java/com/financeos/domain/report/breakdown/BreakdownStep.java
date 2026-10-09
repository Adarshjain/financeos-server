package com.financeos.domain.report.breakdown;

import org.springframework.lang.Nullable;

import java.math.BigDecimal;

/**
 * One line of a row breakdown.
 *
 * @param op     {@code start} / {@code add} / {@code subtract} / {@code equals} for the reconciling
 *               chain, or {@code info} for a standalone fact
 * @param detail secondary text, or null
 * @param amount the step's amount; a positive magnitude on {@code add}/{@code subtract} (the op
 *               carries the sign); nullable on {@code info}
 * @param format how to render {@code amount}: {@code currency} / {@code number} / {@code percent}
 *               / {@code date}, or null
 */
public record BreakdownStep(
        String op,
        String label,
        @Nullable String detail,
        @Nullable BigDecimal amount,
        @Nullable String format) {
}
