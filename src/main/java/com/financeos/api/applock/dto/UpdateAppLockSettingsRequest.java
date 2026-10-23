package com.financeos.api.applock.dto;

import jakarta.validation.constraints.NotNull;

public record UpdateAppLockSettingsRequest(@NotNull Integer lockAfterSeconds) {
}
