package com.financeos.api.applock.dto;

import jakarta.validation.constraints.NotNull;

/** {@code lockAfterSeconds} must be one of the allowed values (0, 60, 300, 900). */
public record RegistrationOptionsRequest(@NotNull Integer lockAfterSeconds) {
}
