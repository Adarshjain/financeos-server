package com.financeos.domain.report.underlying;

import org.springframework.lang.Nullable;

import java.math.BigDecimal;

/**
 * A labelled total over all listed underlying rows (e.g. net worth's "Assets" / "Liabilities").
 *
 * @param format {@code currency} / {@code number} / {@code percent}, or null
 */
public record UnderlyingSummaryLine(String label, BigDecimal value, @Nullable String format) {
}
