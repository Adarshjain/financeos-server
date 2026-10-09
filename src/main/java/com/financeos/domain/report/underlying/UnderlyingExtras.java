package com.financeos.domain.report.underlying;

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
}
