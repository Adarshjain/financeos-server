package com.financeos.api.epf.dto;

import org.springframework.lang.Nullable;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

/**
 * {@code balance} is the user's total EPF balance after the import; {@code throughMonth} the first of the latest
 * contribution wage month of the imported employer after the import (null when it has none).
 */
public record EpfImportResultResponse(UUID employerId, String employerName, int imported, int replaced, int skipped,
                                      BigDecimal balance, @Nullable LocalDate throughMonth) {
}
