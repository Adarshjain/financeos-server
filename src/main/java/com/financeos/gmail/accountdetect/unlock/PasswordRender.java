package com.financeos.gmail.accountdetect.unlock;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Renders a rule ("NAME4U+DDMM") into passwords from a profile. Supported tokens: NAME2..NAME8 with an optional U / L
 * case suffix (an untagged one gives the upper form, then the lower one), DDMM, DDMMYY, DDMMYYYY (from the birth date)
 * CARD4 (one of the card numbers given) and CARD6 (one of the 6-digit card endings given, only through the
 * overload that takes them). A rule with any other token (CUSTID, CRN, CIF, PAN, MOBILE, ACCNO, ...) or
 * with a needed name / birth date missing renders nothing. Passwords are returned to the caller and never kept here.
 */
public final class PasswordRender {

    /** @param rule the rule with every name token tagged U or L, as it was rendered */
    public record Candidate(String password, String rule) {
        @Override
        public String toString() {
            return "Candidate[" + rule + "]";
        }
    }

    private static final Pattern NAME = Pattern.compile("NAME([2-8])([UL]?)");
    private static final Pattern SALUTATION = Pattern.compile("(?i)mr|mrs|ms|dr|miss|mx");
    private static final Pattern FULL_DATE = Pattern.compile("(\\d{4})-(\\d{2})-(\\d{2})");
    private static final Pattern PARTIAL_DATE = Pattern.compile("--(\\d{2})-(\\d{2})");

    private PasswordRender() {
    }

    /** True when every token of the rule is one this class can render (whatever the profile holds). */
    public static boolean renderable(String rule) {
        if (rule == null || rule.isBlank()) return false;
        for (String t : rule.split("\\+")) {
            if (!(NAME.matcher(t).matches() || Set.of("DDMM", "DDMMYY", "DDMMYYYY", "CARD4", "CARD6").contains(t))) return false;
        }
        return true;
    }

    /** The first name as the bank holds it: first word of the display name without salutations and dots, letters only. */
    public static String firstName(String displayName) {
        if (displayName == null) return null;
        for (String w : displayName.trim().split("\\s+")) {
            String bare = w.replace(".", "");
            if (bare.isEmpty() || SALUTATION.matcher(bare).matches()) continue;
            String letters = bare.replaceAll("[^\\p{L}]", "");
            return letters.isEmpty() ? null : letters;
        }
        return null;
    }

    /**
     * The name forms a bank may have used: the first name; when it has fewer than 4 letters, also the first 4 letters of the
     * full name with spaces and dots removed (initials). A single-letter first name is never used on its own.
     */
    static List<String> nameBases(String displayName) {
        List<String> out = new ArrayList<>();
        String first = firstName(displayName);
        if (first == null) return out;
        if (first.length() >= 2) out.add(first);
        if (first.length() < 4) {
            StringBuilder all = new StringBuilder();
            for (String w : displayName.trim().split("\\s+")) {
                String bare = w.replace(".", "");
                if (bare.isEmpty() || SALUTATION.matcher(bare).matches()) continue;
                all.append(bare.replaceAll("[^\\p{L}]", ""));
            }
            String joined = all.length() > 4 ? all.substring(0, 4) : all.toString();
            if (joined.length() >= 2 && !out.contains(joined)) out.add(joined);
        }
        return out;
    }

    /** All passwords the rule gives for this profile, in the order to try (empty when it cannot be rendered). */
    public static List<Candidate> render(String rule, String displayName, String birthDate, List<String> cardLast4) {
        return render(rule, displayName, birthDate, cardLast4, null);
    }

    /** As the 4-arg render, with the 6-digit card endings a CARD6 token reads (a CARD6 rule without any renders nothing). */
    public static List<Candidate> render(String rule, String displayName, String birthDate, List<String> cardLast4,
                                         List<String> cardLast6) {
        List<Candidate> out = new ArrayList<>();
        if (!renderable(rule)) return out;
        List<String> bases = nameBases(displayName);
        String[] tokens = rule.split("\\+");
        List<List<String[]>> options = new ArrayList<>();   // per token: {text, taggedToken}
        for (String t : tokens) {
            List<String[]> opts = new ArrayList<>();
            Matcher nm = NAME.matcher(t);
            if (nm.matches()) {
                if (bases.isEmpty()) return out;
                int n = Integer.parseInt(nm.group(1));
                String tag = nm.group(2);
                for (String base : bases) {
                    String part = base.length() <= n ? base : base.substring(0, n);
                    if (!tag.equals("L")) opts.add(new String[]{part.toUpperCase(Locale.ROOT), "NAME" + n + "U"});
                    if (!tag.equals("U")) opts.add(new String[]{part.toLowerCase(Locale.ROOT), "NAME" + n + "L"});
                }
            } else if (t.equals("CARD4")) {
                if (cardLast4 == null) return out;
                for (String c : cardLast4) if (c != null && c.matches("\\d{4}")) opts.add(new String[]{c, t});
            } else if (t.equals("CARD6")) {
                if (cardLast6 == null) return out;
                for (String c : cardLast6) if (c != null && c.matches("\\d{6}")) opts.add(new String[]{c, t});
            } else {
                String d = date(t, birthDate);
                if (d != null) opts.add(new String[]{d, t});
            }
            if (opts.isEmpty()) return out;
            options.add(opts);
        }
        product(options, 0, new StringBuilder(), new ArrayList<>(), out);
        return out;
    }

    /** The one password a stored (tagged) rule gives, or null: used when an account is created from a suggestion. */
    public static String renderOne(String rule, String displayName, String birthDate, String cardLast4) {
        List<Candidate> c = render(rule, displayName, birthDate, cardLast4 == null ? List.of() : List.of(cardLast4));
        return c.isEmpty() ? null : c.get(0).password();
    }

    private static void product(List<List<String[]>> options, int i, StringBuilder pwd, List<String> tags, List<Candidate> out) {
        if (i == options.size()) {
            out.add(new Candidate(pwd.toString(), String.join("+", tags)));
            return;
        }
        for (String[] o : options.get(i)) {
            int len = pwd.length();
            pwd.append(o[0]);
            tags.add(o[1]);
            product(options, i + 1, pwd, tags, out);
            tags.remove(tags.size() - 1);
            pwd.setLength(len);
        }
    }

    private static String date(String token, String birthDate) {
        if (birthDate == null) return null;
        String dd, mm, yyyy = null;
        Matcher f = FULL_DATE.matcher(birthDate.trim());
        Matcher p = PARTIAL_DATE.matcher(birthDate.trim());
        if (f.matches()) {
            yyyy = f.group(1);
            mm = f.group(2);
            dd = f.group(3);
        } else if (p.matches()) {
            mm = p.group(1);
            dd = p.group(2);
        } else {
            return null;
        }
        return switch (token) {
            case "DDMM" -> dd + mm;
            case "DDMMYY" -> yyyy == null ? null : dd + mm + yyyy.substring(2);
            case "DDMMYYYY" -> yyyy == null ? null : dd + mm + yyyy;
            default -> null;
        };
    }
}
