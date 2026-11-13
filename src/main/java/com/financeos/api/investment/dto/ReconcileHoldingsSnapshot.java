package com.financeos.api.investment.dto;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import org.springframework.lang.Nullable;

/**
 * The demat holdings file of an import as parsed at preview: sent back with the commit so the quantities are kept.
 *
 * @param asOfDate the file's date when it states one, else the day it was uploaded
 * @param items    every row of the file, including stocks the app has not seen
 */
public record ReconcileHoldingsSnapshot(LocalDate asOfDate, List<ReconcileHoldingsSnapshotRow> items) {

    /** One holdings row; {@code isin} is null when the file has none. */
    public record ReconcileHoldingsSnapshotRow(@Nullable String isin, @Nullable String symbol, BigDecimal quantity) {
    }
}
