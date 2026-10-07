package com.financeos.domain.lending;

import org.springframework.lang.Nullable;

import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Picks the counterparty whose name best overlaps a transaction's free-text
 * description, so the lending picker can pre-fill "Rahul Sharma" for
 * "Dinner with Rahul Sharma". Token overlap only: no fuzzy spelling.
 */
public final class CounterpartyNameMatcher {

    private CounterpartyNameMatcher() {}

    /**
     * Lowercases, strips everything but letters/digits, splits on whitespace and
     * drops tokens shorter than 3 characters (initials and connectors like "to"
     * are too noisy to match on).
     */
    static List<String> tokenize(String input) {
        String cleaned = input.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9\\s]+", " ");
        return Arrays.stream(cleaned.trim().split("\\s+"))
                .filter(token -> token.length() >= 3)
                .toList();
    }

    /**
     * Scores each counterparty by how many of its name tokens appear in the text
     * and returns the highest scorer, or {@code null} when no name shares a
     * token with the text. Ties keep whichever counterparty comes first in the
     * input list, so callers should pass a deterministically ordered list.
     */
    @Nullable
    public static Counterparty bestMatch(@Nullable String text, List<Counterparty> counterparties) {
        if (text == null || text.isBlank()) {
            return null;
        }
        Set<String> textTokens = new HashSet<>(tokenize(text));
        if (textTokens.isEmpty()) {
            return null;
        }

        Counterparty best = null;
        int bestScore = 0;
        for (Counterparty cp : counterparties) {
            List<String> nameTokens = tokenize(cp.getName());
            if (nameTokens.isEmpty()) {
                continue;
            }
            int score = (int) nameTokens.stream().filter(textTokens::contains).count();
            if (score > bestScore) {
                bestScore = score;
                best = cp;
            }
        }
        return best;
    }
}
