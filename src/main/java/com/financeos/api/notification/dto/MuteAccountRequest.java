package com.financeos.api.notification.dto;

import jakarta.validation.constraints.NotNull;

public record MuteAccountRequest(@NotNull Boolean muted) {
}
