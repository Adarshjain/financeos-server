package com.financeos.domain.categorization;

import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;

public class DescriptionNormalizer {

    private static final Set<String> NOISE_TOKENS = Set.of(
            "UPI", "POS", "NEFT", "IMPS", "RTGS", "ACH", "REF", "TXN",
            "PAYMENT", "PVT", "LTD", "LIMITED", "PRIVATE", "INDIA", "WWW", "COM", "IN"
    );

    public static String normalize(String description) {
        if (description == null || description.isBlank()) {
            return "";
        }

        // 1. Uppercase
        String upper = description.toUpperCase();

        // 2. Replace non-alphanumeric with space
        String replaced = upper.replaceAll("[^A-Z0-9]", " ");

        // Split into tokens
        String[] tokens = replaced.split("\\s+");

        String result = Arrays.stream(tokens)
                .filter(token -> !token.isEmpty())
                // 3. Drop tokens that contain 3 or more digits
                .filter(token -> countDigits(token) < 3)
                // 4. Drop noise tokens
                .filter(token -> !NOISE_TOKENS.contains(token))
                .collect(Collectors.joining(" "));

        // 5. Trim and cap at 255 chars
        result = result.trim();
        if (result.length() > 255) {
            result = result.substring(0, 255).trim();
        }
        return result;
    }

    private static int countDigits(String s) {
        int count = 0;
        for (int i = 0; i < s.length(); i++) {
            if (Character.isDigit(s.charAt(i))) {
                count++;
            }
        }
        return count;
    }
}
