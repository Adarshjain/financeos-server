package com.financeos.core.util;

import com.github.f4b6a3.uuid.UuidCreator;

import java.util.UUID;

/**
 * Utility class for generating UUID version 7 (time-ordered UUIDs).
 * UUID7 provides better database performance and natural chronological ordering
 * compared to UUID4 (random).
 */
public class UuidGenerator {

    private UuidGenerator() {
        // Private constructor to prevent instantiation
    }

    /**
     * Generate a new UUID version 7.
     * UUID7 is time-ordered, which provides:
     * - Better database index performance
     * - Natural chronological ordering
     * - Reduced index fragmentation
     * 
     * @return a new UUID7
     */
    public static UUID generateUuid7() {
        return UuidCreator.getTimeOrderedEpoch();
    }
}
