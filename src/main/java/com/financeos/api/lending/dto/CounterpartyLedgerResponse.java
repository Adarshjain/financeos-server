package com.financeos.api.lending.dto;

import org.springframework.data.domain.Page;
import org.springframework.lang.Nullable;

import java.math.BigDecimal;
import java.util.List;

/**
 * One person's ledger page (newest first) with totals over the selected cycle (all entries when none).
 * cycles lists every cycle of the person, newest first, whatever the selection; selectedCycle is the
 * cycle the entries are filtered to, null for all entries.
 */
public record CounterpartyLedgerResponse(
        Page<LedgerEntryResponse> entries,
        Totals totals,
        List<LendingCycleResponse> cycles,
        @Nullable Integer selectedCycle
) {
    /** lent = money from you to them; received = money from them to you; closing = lent - received = netPosition. */
    public record Totals(BigDecimal lent, BigDecimal received, BigDecimal closing) {
    }
}
