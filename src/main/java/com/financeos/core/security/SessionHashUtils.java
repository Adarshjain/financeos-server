package com.financeos.core.security;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/**
 * Utility for hashing session IDs before emission in log events.
 * Returns the first 12 hex characters of the SHA-256 digest.
 */
public final class SessionHashUtils {

    private SessionHashUtils() {
        // Utility class
    }

    public static String hashSessionId(String sessionId) {
        if (sessionId == null || sessionId.isEmpty()) {
            return "none";
        }
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(sessionId.getBytes(StandardCharsets.UTF_8));
            StringBuilder hexString = new StringBuilder(hash.length * 2);
            for (byte b : hash) {
                String hex = Integer.toHexString(0xff & b);
                if (hex.length() == 1) {
                    hexString.append('0');
                }
                hexString.append(hex);
            }
            return hexString.substring(0, Math.min(12, hexString.length()));
        } catch (NoSuchAlgorithmException e) {
            return "error";
        }
    }
}
