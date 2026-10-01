package com.financeos.domain.account.cycle;

import com.financeos.domain.account.cycle.BillingCycles.Cycle;
import org.springframework.lang.Nullable;

import java.time.LocalDate;
import java.util.Map;
import java.util.UUID;

/** One cycle per credit card (the same relative cycle, e.g. "this cycle", for each card). */
public record CycleWindows(Map<UUID, Cycle> byCard) {

    /** Whether {@code date} on {@code accountId} falls in that card's window; non-cards never do. */
    public boolean contains(@Nullable UUID accountId, @Nullable LocalDate date) {
        if (accountId == null || date == null) {
            return false;
        }
        Cycle cycle = byCard.get(accountId);
        return cycle != null && cycle.contains(date);
    }

    /** Earliest start across cards, or null when the user has no cards. */
    @Nullable
    public LocalDate earliestStart() {
        return byCard.values().stream().map(Cycle::start).min(LocalDate::compareTo).orElse(null);
    }

    /** Latest end across cards, or null when the user has no cards. */
    @Nullable
    public LocalDate latestEnd() {
        return byCard.values().stream().map(Cycle::end).max(LocalDate::compareTo).orElse(null);
    }
}
