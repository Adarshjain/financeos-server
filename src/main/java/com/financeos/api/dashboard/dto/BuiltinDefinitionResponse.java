package com.financeos.api.dashboard.dto;

import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.lang.Nullable;

import java.time.LocalDate;

/**
 * A template built-in's report definition exactly as its data endpoint would run it now — the
 * widget's params and the request-time filters applied — ready to save as the caller's own report
 * ("Duplicate as my report").
 */
public record BuiltinDefinitionResponse(
        String key,
        /** The built-in's label, a suggested name for the saved report. */
        String label,
        /** KPI | CHART | TABLE. */
        String type,
        /** Report datasource the definition runs over. */
        String datasource,
        JsonNode definition,
        /**
         * Null when every date filter is a relative preset (the saved report stays current). Otherwise
         * the day whose window is pinned as fixed dates (e.g. the reward windows open today): the saved
         * report is a snapshot of that day's window and does not roll forward.
         */
        @Nullable LocalDate windowAsOf) {
}
