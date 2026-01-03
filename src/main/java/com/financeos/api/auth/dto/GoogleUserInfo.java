package com.financeos.api.auth.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

public record GoogleUserInfo(
        String id,
        String email,
        String name,
        @JsonProperty("picture") String pictureUrl,
        @JsonProperty("verified_email") boolean verifiedEmail) {
}
