package com.financeos.api.applock.dto;

import com.fasterxml.jackson.databind.JsonNode;
import jakarta.validation.constraints.NotNull;

/** {@code credential} is the browser's PublicKeyCredential assertion serialised to JSON (toJSON()). */
public record DisableAppLockRequest(@NotNull JsonNode credential) {
}
