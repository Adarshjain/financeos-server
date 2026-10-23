package com.financeos.api.applock.dto;

import com.fasterxml.jackson.databind.JsonNode;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/** {@code credential} is the browser's PublicKeyCredential serialised to JSON (toJSON()). */
public record RegistrationVerifyRequest(
        @NotNull JsonNode credential,
        @Size(max = 100) String label) {
}
