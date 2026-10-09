package com.financeos.domain.report.underlying;

import org.springframework.lang.Nullable;

import java.util.List;
import java.util.Map;

/**
 * Extra KPI underlying-data content a computed datasource can supply beyond its rows.
 * Implemented by the datasource bean itself.
 */
public interface UnderlyingExtras {

    /** Labelled totals over every listed row (all pages), in display order. */
    List<UnderlyingSummaryLine> summaryLines(List<Map<String, Object>> listedRows);

    /** Items the datasource deliberately or unavoidably left out of its rows. */
    List<UnderlyingExcludedItem> notCounted();

    /**
     * The datasource's rows and the items left out of them, from one computation.
     *
     * @param rows       every row, as {@code ComputedReportDatasource#rows()} returns them
     * @param notCounted what {@link #notCounted()} returns
     */
    record Snapshot(List<Map<String, Object>> rows, List<UnderlyingExcludedItem> notCounted) {
    }

    /**
     * The rows and left-out items for one underlying-data request, computed together. A datasource
     * whose rows and left-out items come from the same (expensive) pass returns them here so a
     * request computes that pass once; null (the default) when they are independent, in which case
     * the rows load as for a KPI run and {@link #notCounted()} is asked separately. Never cached
     * across requests.
     */
    @Nullable
    default Snapshot underlyingSnapshot() {
        return null;
    }
}
