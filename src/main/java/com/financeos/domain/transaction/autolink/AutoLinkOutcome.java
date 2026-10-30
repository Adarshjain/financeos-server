package com.financeos.domain.transaction.autolink;

import java.util.EnumMap;
import java.util.Map;

/** What one linker did in one pass, per suggestion kind. */
public record AutoLinkOutcome(Map<SuggestionKind, Counts> byKind, boolean partial) {

    public record Counts(int linked, int suggested) {
        public static final Counts ZERO = new Counts(0, 0);

        public Counts plus(Counts other) {
            return new Counts(linked + other.linked, suggested + other.suggested);
        }
    }

    public static AutoLinkOutcome of(Map<SuggestionKind, Counts> byKind) {
        return new AutoLinkOutcome(Map.copyOf(byKind), false);
    }

    /** {@code partial}: some pairing failed and was skipped, so the pass was not clean. */
    public static AutoLinkOutcome of(Map<SuggestionKind, Counts> byKind, boolean partial) {
        return new AutoLinkOutcome(Map.copyOf(byKind), partial);
    }

    public static Map<SuggestionKind, Counts> merge(Map<SuggestionKind, Counts> a, Map<SuggestionKind, Counts> b) {
        Map<SuggestionKind, Counts> out = new EnumMap<>(SuggestionKind.class);
        out.putAll(a);
        b.forEach((k, v) -> out.merge(k, v, Counts::plus));
        return out;
    }

    public int linked() {
        return byKind.values().stream().mapToInt(Counts::linked).sum();
    }

    public int suggested() {
        return byKind.values().stream().mapToInt(Counts::suggested).sum();
    }
}
