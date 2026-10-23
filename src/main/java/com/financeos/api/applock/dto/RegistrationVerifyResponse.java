package com.financeos.api.applock.dto;

/** The enrolled device's id; the client keeps it in the FINANCEOS_DEVICE cookie. */
public record RegistrationVerifyResponse(String deviceId) {
}
