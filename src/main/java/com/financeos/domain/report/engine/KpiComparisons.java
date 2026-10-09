package com.financeos.domain.report.engine;

import com.financeos.domain.report.definition.Comparison;
import com.financeos.domain.report.definition.ComparisonDisplay;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * The previous-period comparison of a KPI, built the same way by the SQL ({@link KpiReportExecutor})
 * and in-memory ({@link InMemoryReportExecutor}) executors whenever the KPI has a previous period.
 */
final class KpiComparisons {

    private KpiComparisons() {
    }

    /**
     * The comparison of {@code current} against {@code previousValue}. A missing value (an AVG,
     * MIN or MAX over no rows) counts as zero for the change; the echoed {@code previousValue}
     * stays null so a "previous value" display can show a dash instead of a fabricated zero.
     *
     * @param definition the KPI's comparison settings (sentiment and display); may be null
     */
    static KpiData.Comparison build(BigDecimal current, BigDecimal previousValue, DateRange previousRange,
            Comparison definition) {
        BigDecimal cur = current == null ? BigDecimal.ZERO : current;
        BigDecimal prev = previousValue == null ? BigDecimal.ZERO : previousValue;
        BigDecimal change = cur.subtract(prev);

        BigDecimal changePercent = null;
        if (prev.signum() != 0) {
            changePercent = change
                    .divide(prev.abs(), 6, RoundingMode.HALF_UP)
                    .multiply(BigDecimal.valueOf(100))
                    .setScale(2, RoundingMode.HALF_UP);
        }

        String direction = change.signum() > 0 ? "up" : change.signum() < 0 ? "down" : "flat";

        Boolean higherIsBetter = definition == null ? null : definition.higherIsBetter();
        String sentiment;
        if (higherIsBetter == null || change.signum() == 0) {
            sentiment = "neutral";
        } else if (change.signum() > 0) {
            sentiment = higherIsBetter ? "good" : "bad";
        } else {
            sentiment = higherIsBetter ? "bad" : "good";
        }

        KpiData.DateRangeView previousView = new KpiData.DateRangeView(previousRange.from(), previousRange.to());
        return new KpiData.Comparison(previousValue, previousView, change, changePercent, direction, sentiment,
                ComparisonDisplay.resolve(definition).json());
    }
}
