package com.financeos.api.epf.dto;

import java.math.BigDecimal;
import java.util.List;

/** {@code balance} is the user's total EPF balance after the whole batch. */
public record EpfImportBatchResultResponse(List<EpfImportResultResponse> results, int imported, int replaced,
                                           int skipped, BigDecimal balance,
                                           List<EpfImportRefusedFile> refused) {
}
