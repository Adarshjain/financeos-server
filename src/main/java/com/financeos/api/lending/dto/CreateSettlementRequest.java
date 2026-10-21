package com.financeos.api.lending.dto;

import jakarta.validation.constraints.NotNull;

import java.util.UUID;

public record CreateSettlementRequest(@NotNull UUID transactionId) {
}
