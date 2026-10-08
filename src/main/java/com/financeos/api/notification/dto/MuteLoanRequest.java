package com.financeos.api.notification.dto;

import jakarta.validation.constraints.NotNull;

public record MuteLoanRequest(@NotNull Boolean muted) {
}
