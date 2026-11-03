package com.financeos.gmail.accountdetect;

import java.text.Normalizer;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;
import java.util.function.UnaryOperator;
import java.util.regex.Pattern;

/**
 * Does a statement's first page carry the user's name? Only the answer leaves this class: "Y" when a word of the name matches
 * a word of the page, "N" when none does, null when there is no name or no text. The name and the page text are never stored
 * or logged.
 *
 * <p>Matching only ever adds Y (never a new N): both sides are lower-cased with diacritics removed and split on non-letters;
 * name words are at least 3 letters (salutations dropped); a name word with an apostrophe or hyphen ("D'Souza") is also tried
 * joined ("dsouza") against adjacent page words joined; a name word of 5 or more letters also matches a page word with the same
 * first 5 letters or one edit away.
 */
public final class HolderCheck {

    public static final String YES = "Y";
    public static final String NO = "N";
    private static final int MIN_LETTERS = 3;
    private static final int PREFIX = 5;
    private static final Pattern SALUTATION = Pattern.compile("mr|mrs|ms|dr|miss|mx|shri|smt");
    private static final Pattern MARKS = Pattern.compile("\\p{M}+");
    private static final Pattern NON_LETTERS = Pattern.compile("[^\\p{L}]+");

    private HolderCheck() {
    }

    private static String fold(String s) {
        return MARKS.matcher(Normalizer.normalize(s, Normalizer.Form.NFD)).replaceAll("").toLowerCase(Locale.ROOT);
    }

    /** The folded words of a display name worth looking for, joined forms of apostrophe / hyphen words included. */
    public static Set<String> nameWords(String displayName) {
        Set<String> out = new LinkedHashSet<>();
        if (displayName == null) return out;
        for (String part : fold(displayName).trim().split("[\\s.]+")) {
            if (part.isEmpty()) continue;
            for (String w : NON_LETTERS.split(part)) add(out, w);
            if (part.matches(".*[\\p{L}]['’`\\-][\\p{L}].*")) add(out, part.replaceAll("[^\\p{L}]", ""));
        }
        return out;
    }

    private static void add(Set<String> out, String w) {
        if (w.length() >= MIN_LETTERS && !SALUTATION.matcher(w).matches()) out.add(w);
    }

    /** The check for one user: statement first-page text to Y, N or null. Null when the user has no usable name. */
    public static UnaryOperator<String> forName(String displayName) {
        Set<String> words = nameWords(displayName);
        if (words.isEmpty()) return null;
        return text -> check(words, text);
    }

    /** {@code words} as returned by {@link #nameWords}. */
    public static String check(Set<String> words, String text) {
        if (words == null || words.isEmpty() || text == null || text.isBlank()) return null;
        java.util.List<String> tokens = new java.util.ArrayList<>();
        for (String w : NON_LETTERS.split(fold(text))) if (!w.isEmpty()) tokens.add(w);
        Set<String> page = new HashSet<>(tokens);
        for (int i = 0; i + 1 < tokens.size(); i++) page.add(tokens.get(i) + tokens.get(i + 1));   // "d" "souza" -> "dsouza"
        for (String w : words) {
            if (page.contains(w)) return YES;
            if (w.length() >= PREFIX) {
                String prefix = w.substring(0, PREFIX);
                for (String p : page) {
                    if (p.length() >= PREFIX && p.startsWith(prefix)) return YES;
                    if (Math.abs(p.length() - w.length()) <= 1 && withinOneEdit(w, p)) return YES;
                }
            }
        }
        return NO;
    }

    /** Edit distance at most 1 (one substitution, insertion or deletion). */
    static boolean withinOneEdit(String a, String b) {
        if (a.equals(b)) return true;
        int la = a.length(), lb = b.length();
        if (Math.abs(la - lb) > 1) return false;
        int i = 0;
        while (i < la && i < lb && a.charAt(i) == b.charAt(i)) i++;
        if (la == lb) return a.substring(i + 1).equals(b.substring(i + 1));
        if (la < lb) return a.substring(i).equals(b.substring(i + 1));
        return a.substring(i + 1).equals(b.substring(i));
    }
}
