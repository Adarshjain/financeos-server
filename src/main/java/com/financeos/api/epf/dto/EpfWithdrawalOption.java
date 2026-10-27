package com.financeos.api.epf.dto;

import org.springframework.lang.Nullable;

import java.math.BigDecimal;

/** An unlinked withdrawal offered for a bank credit. */
public record EpfWithdrawalOption(
        EpfEntryResponse entry,
        /** Received amount equals the credit exactly and the dates fit. */
        boolean suggested,
        /** Credit is short of the gross by this much (a likely TDS cut) when no TDS is recorded. */
        @Nullable BigDecimal tdsGap) {
}
