package com.financeos.domain.account.cycle;

import com.financeos.domain.account.cycle.BillingCycles.Cycle;
import org.springframework.lang.Nullable;

import java.time.LocalDate;
import java.util.Map;
import java.util.UUID;

/** One cycle per account (the same relative cycle, e.g. "this cycle", for each account). */
public record CycleWindows(Map<UUID, Cycle> byAccount) {

    /** Whether {@code date} on {@code accountId} falls in that account's window; unknown accounts never do. */
    public boolean contains(@Nullable UUID accountId, @Nullable LocalDate date) {
        if (accountId == null || date == null) {
            return false;
        }
        Cycle cycle = byAccount.get(accountId);
        return cycle != null && cycle.contains(date);
    }

    /** Earliest start across cards, or null when the user has no cards. */
    @Nullable
    public LocalDate earliestStart() {
        return byAccount.values().stream().map(Cycle::start).min(LocalDate::compareTo).orElse(null);
    }

    /** Latest end across cards, or null when the user has no cards. */
    @Nullable
    public LocalDate latestEnd() {
        return byAccount.values().stream().map(Cycle::end).max(LocalDate::compareTo).orElse(null);
    }
}
