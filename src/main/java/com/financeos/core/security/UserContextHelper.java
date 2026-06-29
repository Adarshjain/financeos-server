package com.financeos.core.security;

import java.util.UUID;
import java.util.function.Supplier;

public class UserContextHelper {

    /**
     * Executes the given action with the specified user ID set in the UserContext,
     * restoring the original user ID context afterwards.
     */
    public static void runAs(UUID userId, Runnable action) {
        UUID previous = UserContext.getCurrentUserId();
        UserContext.setCurrentUserId(userId);
        try {
            action.run();
        } finally {
            if (previous != null) {
                UserContext.setCurrentUserId(previous);
            } else {
                UserContext.clear();
            }
        }
    }

    /**
     * Executes the given action and returns its result with the specified user ID set in the UserContext,
     * restoring the original user ID context afterwards.
     */
    public static <T> T callAs(UUID userId, Supplier<T> action) {
        UUID previous = UserContext.getCurrentUserId();
        UserContext.setCurrentUserId(userId);
        try {
            return action.get();
        } finally {
            if (previous != null) {
                UserContext.setCurrentUserId(previous);
            } else {
                UserContext.clear();
            }
        }
    }
}
