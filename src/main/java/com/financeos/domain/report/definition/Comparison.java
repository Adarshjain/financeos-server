package com.financeos.domain.report.definition;

import com.fasterxml.jackson.annotation.JsonCreator;

/**
 * Period-over-period comparison configuration for KPI reports.
 *
 * <p>{@code higherIsBetter} drives the response {@code sentiment} (good/bad): when set, an
 * increase in the value is reported as "good" if true and "bad" if false (and vice versa for a
 * decrease). When null, sentiment is "neutral".
 *
 * <p>{@code display} picks what the comparison line shows: the change against the previous
 * period ({@code change}, the default when null) or the previous period's value
 * ({@code previous_value}). Saved definitions that predate the field parse with a null display.
 */
public record Comparison(
        Boolean enabled,
        ComparisonPeriod period,
        Boolean higherIsBetter,
        ComparisonDisplay display
) {
    @JsonCreator(mode = JsonCreator.Mode.PROPERTIES)
    public Comparison {
    }

    /** A comparison with the default ({@code change}) display. */
    public Comparison(Boolean enabled, ComparisonPeriod period, Boolean higherIsBetter) {
        this(enabled, period, higherIsBetter, null);
    }
}
