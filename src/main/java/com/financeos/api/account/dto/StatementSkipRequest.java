package com.financeos.api.account.dto;

import jakarta.validation.constraints.NotNull;
import java.time.LocalDate;

/** {@code periodEnd}: the projected billing-cycle end the owner marks "No statement this period". */
public record StatementSkipRequest(@NotNull LocalDate periodEnd) {}
