package com.financeos.api.investment.dto;

import org.springframework.lang.Nullable;

import java.math.BigDecimal;
import java.util.UUID;

/** One instrument's dividend totals over a window: gross, TDS, net (gross - TDS) and payout count. */
public record DividendInstrumentTotalResponse(
        UUID instrumentId,
        String instrumentName,
        @Nullable String symbol,
        BigDecimal gross,
        BigDecimal tds,
        BigDecimal net,
        long payouts
) {}
