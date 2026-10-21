package com.financeos.api.lending.dto;

import org.springframework.data.domain.Page;

import java.math.BigDecimal;

/** One person's ledger page (newest first) with totals over all their entries. */
public record CounterpartyLedgerResponse(Page<LedgerEntryResponse> entries, Totals totals) {
    /** lent = money from you to them; received = money from them to you; closing = lent - received = netPosition. */
    public record Totals(BigDecimal lent, BigDecimal received, BigDecimal closing) {
    }
}
