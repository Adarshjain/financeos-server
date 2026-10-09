package com.financeos.domain.report.breakdown;

import com.financeos.domain.report.engine.ReportData;
import org.springframework.lang.Nullable;

/**
 * A supporting table of a row breakdown (e.g. the transactions after a statement anchor).
 *
 * @param key                    stable section key, used to page it via the section endpoint
 * @param rowAction              {@code transaction} / {@code breakdown} when rows open a detail
 *                               view, else null
 * @param rowBreakdownDatasource the datasource whose breakdown a row opens when
 *                               {@code rowAction} is {@code breakdown}, else null
 * @param table                  one page of the section as a raw table (each row carries a hidden {@code id})
 */
public record BreakdownSectionData(
        String key,
        String label,
        @Nullable String rowAction,
        @Nullable String rowBreakdownDatasource,
        ReportData table) {
}
