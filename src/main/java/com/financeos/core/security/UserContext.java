package com.financeos.core.security;

import java.util.UUID;

public class UserContext {
    private static final ThreadLocal<UUID> currentUserId = new ThreadLocal<>();

    public static void setCurrentUserId(UUID userId) {
        currentUserId.set(userId);
    }

    public static UUID getCurrentUserId() {
        return currentUserId.get();
    }

    public static void clear() {
        currentUserId.remove();
    }
}
