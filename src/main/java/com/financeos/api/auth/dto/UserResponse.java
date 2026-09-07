package com.financeos.api.auth.dto;

import com.financeos.domain.user.User;

import org.springframework.lang.Nullable;

import java.time.Instant;
import java.util.UUID;

public record UserResponse(
        UUID id,
        String email,
        @Nullable String displayName,
        @Nullable String pictureUrl,
        boolean hasPassword,
        Instant createdAt,
        boolean admin) {
    public static UserResponse from(User user) {
        return from(user, false);
    }

    public static UserResponse from(User user, boolean admin) {
        return new UserResponse(
                user.getId(),
                user.getEmail(),
                user.getDisplayName(),
                user.getPictureUrl(),
                user.getPasswordHash() != null && !user.getPasswordHash().isBlank(),
                user.getCreatedAt(),
                admin);
    }
}
