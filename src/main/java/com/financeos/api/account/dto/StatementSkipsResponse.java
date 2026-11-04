package com.financeos.api.account.dto;

import java.time.LocalDate;
import java.util.List;
import org.springframework.lang.Nullable;

/**
 * The Statements tab's "no statement" state of a card: the cycles already marked (newest first) and the earliest
 * cycle still missing a statement whose period has ended (null when the card is current).
 */
public record StatementSkipsResponse(List<StatementSkipResponse> skipped, @Nullable Missing missing) {

    /** {@code expectedBy} is the period end plus the grace days after which the statement counts as late. */
    public record Missing(LocalDate periodStart, LocalDate periodEnd, LocalDate expectedBy) {}
}
