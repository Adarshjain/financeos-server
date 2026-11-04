package com.financeos.domain.statement.skip;

import java.time.LocalDate;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.lang.Nullable;

/**
 * The shared "latest covered period end" of a card: the later of its latest statement's period end and its latest
 * skipped ("No statement this period") end. Every statement-expected projection advances from it. A skip at or before
 * the latest statement end changes nothing, so a statement imported for a skipped cycle simply wins. Pure.
 */
public final class SkippedPeriods {

    private SkippedPeriods() {
    }

    /** {@code latestStatementEnd} null (no usable statement history) stays null: nothing to project from, skips or not. */
    @Nullable
    public static LocalDate coveredEnd(@Nullable LocalDate latestStatementEnd, @Nullable Collection<LocalDate> skippedEnds) {
        if (latestStatementEnd == null) {
            return null;
        }
        LocalDate covered = latestStatementEnd;
        if (skippedEnds != null) {
            for (LocalDate end : skippedEnds) {
                if (end != null && end.isAfter(covered)) {
                    covered = end;
                }
            }
        }
        return covered;
    }

    /** Every account's skipped ends, from one repository query. */
    public static Map<UUID, List<LocalDate>> byAccount(StatementSkippedPeriodRepository repository, Collection<UUID> accountIds) {
        Map<UUID, List<LocalDate>> out = new HashMap<>();
        if (repository == null || accountIds.isEmpty()) {
            return out;
        }
        for (StatementSkippedPeriodRepository.SkippedEnd row : repository.findEndsByAccountIds(accountIds)) {
            out.computeIfAbsent(row.getAccountId(), k -> new java.util.ArrayList<>()).add(row.getPeriodEnd());
        }
        return out;
    }
}
