package com.financeos.domain.lending;

import org.springframework.lang.Nullable;

import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Decides whether a bank transaction's text names a person: every name token of 3+ characters must
 * appear as a whole word (case-insensitive). Words are runs of letters/digits, so "Dad" does not
 * match "DADAR" and a name made only of short tokens never matches.
 */
public final class UnrecordedTransferMatcher {

    static final int MIN_TOKEN_LENGTH = 3;

    private UnrecordedTransferMatcher() {}

    /** Lowercased letter/digit runs of the text, every length. */
    static Set<String> words(@Nullable String text) {
        if (text == null || text.isBlank()) {
            return Set.of();
        }
        return new HashSet<>(Arrays.asList(text.toLowerCase(Locale.ROOT).split("[^\\p{L}\\p{N}]+")));
    }

    /** The name's matchable tokens: lowercased words of length {@value #MIN_TOKEN_LENGTH} or more. */
    static List<String> nameTokens(@Nullable String name) {
        return words(name).stream().filter(t -> t.length() >= MIN_TOKEN_LENGTH).sorted().toList();
    }

    /** True when every 3+ character token of {@code name} is a whole word in {@code textWords}. */
    static boolean matches(@Nullable String name, Set<String> textWords) {
        List<String> tokens = nameTokens(name);
        return !tokens.isEmpty() && textWords.containsAll(tokens);
    }

    /** Whole-word, all-tokens match of {@code name} against the joined text fields (null fields ignored). */
    public static boolean matches(@Nullable String name, @Nullable String... texts) {
        Set<String> all = new HashSet<>();
        for (String text : texts) {
            all.addAll(words(text));
        }
        return matches(name, all);
    }
}
