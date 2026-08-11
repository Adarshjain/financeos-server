package com.financeos.domain.reward;

/**
 * Window a milestone's threshold accumulates over. Mirrors {@link CapWindow} minus
 * DAY, plus ONE_TIME: a single non-repeating window spanning the milestone's active
 * range (welcome benefits — "spend X in the first 90 days").
 */
public enum MilestoneWindow {
    CALENDAR_MONTH,
    STATEMENT_CYCLE,
    QUARTER,
    CALENDAR_YEAR,
    ANNIVERSARY_YEAR,
    ONE_TIME;

    /** The equivalent cap window for recurring types; ONE_TIME has none. */
    public CapWindow asCapWindow() {
        if (this == ONE_TIME) {
            throw new IllegalStateException("ONE_TIME has no CapWindow equivalent");
        }
        return CapWindow.valueOf(name());
    }
}
