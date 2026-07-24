package com.financeos.core.util;

import java.security.SecureRandom;
import java.util.UUID;

/**
 * Utility for generating time-ordered UUIDv7 instances according to RFC 9562.
 * Time-ordered UUIDs prevent B-Tree index page splitting in databases like Oracle.
 */
public final class UUIDv7Generator {

    private static final SecureRandom RANDOM = new SecureRandom();

    private UUIDv7Generator() {
        // Utility class
    }

    /**
     * Generate a new time-ordered UUID (UUIDv7).
     */
    public static UUID generate() {
        long epochMillis = System.currentTimeMillis();
        long randomBytes = RANDOM.nextLong();

        // 48 bits timestamp + 4 bits version (7) + 12 bits random
        long mostSigBits = (epochMillis & 0xFFFFFFFFFFFFL) << 16;
        mostSigBits |= (0x7L << 12); // Version 7
        mostSigBits |= ((randomBytes >>> 48) & 0x0FFFL);

        // 2 bits variant (10) + 62 bits random
        long leastSigBits = (randomBytes & 0x3FFFFFFFFFFFFFFFL);
        leastSigBits |= 0x8000000000000000L; // Variant 1 (0b10)

        return new UUID(mostSigBits, leastSigBits);
    }
}
