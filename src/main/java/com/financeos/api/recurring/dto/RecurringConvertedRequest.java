package com.financeos.api.recurring.dto;

import jakarta.validation.constraints.NotNull;

import java.util.UUID;

/** The loan an EMI proposal was turned into. */
public record RecurringConvertedRequest(@NotNull UUID loanId) {
}
