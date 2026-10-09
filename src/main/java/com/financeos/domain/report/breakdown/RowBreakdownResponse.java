package com.financeos.domain.report.breakdown;

import org.springframework.lang.Nullable;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * How one underlying row's value is made up.
 *
 * <p>The {@code start}/{@code add}/{@code subtract}/{@code equals} steps reconcile exactly
 * (in BigDecimal) to {@code total}, which is the row's value as listed in the underlying data.
 *
 * @param datasource the datasource the row belongs to
 * @param rowId      the row's id within that datasource
 * @param subtitle   secondary heading, or null
 * @param kindLabel  the row's kind for people, or null
 * @param total      the row's listed value
 * @param totalLabel label for {@code total}
 * @param format     {@code currency} / {@code number} / {@code percent}, or null
 * @param asOf       the date the figure is computed for
 * @param steps      the reconciling chain plus standalone {@code info} facts, in display order
 * @param sections   supporting tables, each holding its first page
 * @param notes      muted explanatory lines
 */
public record RowBreakdownResponse(
        String datasource,
        String rowId,
        String title,
        @Nullable String subtitle,
        @Nullable String kindLabel,
        BigDecimal total,
        String totalLabel,
        @Nullable String format,
        LocalDate asOf,
        List<BreakdownStep> steps,
        List<BreakdownSectionData> sections,
        List<String> notes) {
}
