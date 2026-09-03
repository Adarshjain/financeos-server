package com.financeos.api.auth.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

import org.springframework.lang.Nullable;

public record GoogleTokenResponse(
        @JsonProperty("access_token") String accessToken,
        @JsonProperty("expires_in") Long expiresIn,
        @Nullable @JsonProperty("refresh_token") String refreshToken,
        @Nullable @JsonProperty("scope") String scope,
        @JsonProperty("token_type") String tokenType,
        @Nullable @JsonProperty("id_token") String idToken) {
}
