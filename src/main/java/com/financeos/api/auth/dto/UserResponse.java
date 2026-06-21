package com.financeos.api.auth.dto;

import com.financeos.domain.user.User;

import java.time.Instant;
import java.util.UUID;

public record UserResponse(
        UUID id,
        String email,
        String displayName,
        String pictureUrl,
        Instant createdAt) {
    public static UserResponse from(User user) {
        return new UserResponse(
                user.getId(),
                user.getEmail(),
                user.getDisplayName(),
                user.getPictureUrl(),
                user.getCreatedAt());
    }
}
