package com.financeos.domain.categorization.builtin;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/** Turns a counterparty or merchant name into the plain lowercase form the dictionary is matched on. */
final class NameNormalizer {

    /** Payment-gateway and channel prefixes such as {@code UPI_}, {@code RAZ*}, {@code FS *}. */
    private static final Pattern PREFIX = Pattern.compile(
            "^(?:(?:upi|pos|ecom|ib|www|raz|razorpay|payu|pg|bd|fs|sq|ppsl|tpsl|ccav|ebs|neft|imps)[\\s_*\\-]+)+");
    private static final Pattern NON_ALNUM = Pattern.compile("[^\\p{L}\\p{N}]+");
    private static final Set<String> SUFFIXES = Set.of("pvt", "private", "ltd", "limited", "india", "online", "llp", "inc",
            "corp", "corporation", "payments", "payment", "technologies", "technology", "tech", "solutions",
            "retail", "ecommerce", "commerce");

    private NameNormalizer() {}

    /** Lowercase, channel prefix, anything after a '/' or ' via ', punctuation, a leading "the" and trailing numbers removed. */
    static String merchant(String raw) {
        return join(tokens(raw), false);
    }

    /** As {@link #merchant} and also without trailing company words (Pvt, Ltd, India, Online...). */
    static String stripped(String raw) {
        return join(tokens(raw), true);
    }

    private static String join(List<String> tokens, boolean suffixes) {
        while (!tokens.isEmpty() && (isId(last(tokens)) || (suffixes && SUFFIXES.contains(last(tokens))))) {
            tokens.remove(tokens.size() - 1);
        }
        while (!tokens.isEmpty() && tokens.get(0).equals("the")) {
            tokens.remove(0);
        }
        return String.join(" ", tokens);
    }

    /** As {@link #merchant} but keeping the company words: what a person-name check needs to see. */
    static List<String> words(String raw) {
        return tokens(raw);
    }

    private static List<String> tokens(String raw) {
        if (raw == null) {
            return new ArrayList<>();
        }
        String s = raw.toLowerCase(Locale.ROOT).trim();
        int slash = s.indexOf('/');
        if (slash > 0) {
            s = s.substring(0, slash);
        }
        int via = s.indexOf(" via ");
        if (via > 0) {
            s = s.substring(0, via);
        }
        s = PREFIX.matcher(s).replaceFirst("");
        s = NON_ALNUM.matcher(s).replaceAll(" ").trim();
        List<String> out = new ArrayList<>();
        if (!s.isEmpty()) {
            for (String t : s.split(" ")) {
                out.add(t);
            }
        }
        return out;
    }

    private static String last(List<String> tokens) {
        return tokens.get(tokens.size() - 1);
    }

    /** A trailing reference number, not part of a name ("tata 1mg" keeps its 1mg). */
    private static boolean isId(String token) {
        return token.length() >= 4 && token.chars().allMatch(Character::isDigit);
    }
}
