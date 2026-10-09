package com.financeos.domain.report.engine;

import com.financeos.core.exception.ValidationException;
import com.financeos.domain.report.definition.FilterClause;
import org.springframework.lang.Nullable;

import java.util.List;

/**
 * The periods one KPI run covers, as resolved by {@link KpiPeriodResolver}: the current period
 * and, when the KPI shows a comparison, the previous one.
 *
 * @param dateFilter the KPI's date filter (the first filter on a DATE field), or null
 * @param current    the definition's own filters and the range they select
 * @param previous   the previous period, or null when the KPI has no comparison
 */
public record KpiPeriods(@Nullable FilterClause dateFilter, Period current, @Nullable Period previous) {

    /** Which of a KPI's periods to read. */
    public enum Kind {
        CURRENT("current"),
        PREVIOUS("previous");

        private final String json;

        Kind(String json) {
            this.json = json;
        }

        public String json() {
            return json;
        }

        /**
         * The period named by a request parameter: {@code current} (also when absent or blank) or
         * {@code previous}, case-insensitive.
         *
         * @throws ValidationException for any other value
         */
        public static Kind from(@Nullable String raw) {
            if (raw == null || raw.isBlank()) {
                return CURRENT;
            }
            for (Kind kind : values()) {
                if (kind.json.equalsIgnoreCase(raw.trim())) {
                    return kind;
                }
            }
            throw new ValidationException("Invalid period '" + raw + "': expected 'current' or 'previous'");
        }
    }

    /**
     * One period of a KPI.
     *
     * @param filters the filters that select exactly this period's rows
     * @param range   the period's dates; unbounded when the filters select no bounded range
     */
    public record Period(List<FilterClause> filters, DateRange range) {
    }

    /** Whether the KPI shows a comparison against a previous period. */
    public boolean previousAvailable() {
        return previous != null;
    }

    /**
     * The requested period.
     *
     * @throws ValidationException when the previous period is requested but the KPI has none
     */
    public Period select(Kind kind) {
        if (kind == Kind.CURRENT) {
            return current;
        }
        if (previous == null) {
            throw new ValidationException("This KPI has no previous period");
        }
        return previous;
    }
}
