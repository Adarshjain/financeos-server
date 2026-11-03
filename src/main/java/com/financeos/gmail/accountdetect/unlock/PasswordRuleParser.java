package com.financeos.gmail.accountdetect.unlock;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Maps the "password" sentences of a statement mail to rule tokens ("NAME4U+DDMM", "CARD4"). Only the tokens leave this
 * class, never the sentence text. Ported from the dev-only eval hint parser.
 */
public final class PasswordRuleParser {

    /** The stored hint: rules joined by this separator, at most {@link #HINT_MAX} characters. */
    public static final String SEPARATOR = "|";
    public static final int HINT_MAX = 200;
    private static final int MAX_SENTENCES = 20;
    private static final int MAX_SENTENCE_LENGTH = 800;

    private PasswordRuleParser() {
    }

    private static final String N = "(4|four|5|five|6|six|8|eight|3|three|2|two)";
    private static final String DIG = "(digits?|numbers?|characters?|chars?|alphabets?|letters?)";
    private static final String UP = "(capital|upper ?case|block|caps)";
    private static final String LOW = "(small|lower ?case)";
    private static final Pattern UP_P = Pattern.compile(UP, Pattern.CASE_INSENSITIVE);
    private static final Pattern LOW_P = Pattern.compile(LOW, Pattern.CASE_INSENSITIVE);
    private static final Pattern SPLIT = Pattern.compile("(?<![a-z])or(?![a-z])|\\bif +(you|your|the)\\b|\\bin +case\\b|\\bfor +(all +)?(credit|debit|savings|current|corporate|business|joint|primary|add-on|supplementary)\\b|\\b[a-d]\\)|\\(([ivx]+|[a-d])\\)|\\be\\.?g\\b|\\bfor example\\b|\\balternatively\\b|\\balternate\\b|;|\\bwhichever\\b");

    private record Rule(Pattern p, String token) {
    }

    private static Rule r(String rx, String token) {
        return new Rule(Pattern.compile(rx, Pattern.CASE_INSENSITIVE), token);
    }

    // order matters: specific before generic; overlapping later matches are dropped
    private static final List<Rule> RULES = List.of(
            r("(first|1st) +" + N + " +" + DIG + " +(of +)?(the +|your +|his +|her +|account ?holder'?s? +|primary +|card ?holder'?s? +|customer'?s? +)*(first +|full +)?(name|title)", "NAME@"),
            r("(first|1st) +" + N + " +(letters?|characters?|alphabets?)\\b(?! +(of +)?(your +|the +)?(pan|permanent))", "NAME@"),
            r("(first|1st) +" + N + " +" + DIG + " +of +(your +|the +)?(pan|permanent account)", "PAN@"),
            r("(last|final) +" + N + " +" + DIG + " +of +(your +|the +|his +|her +)*(registered +|primary +)?(mobile|phone|cell|contact)", "MOBILE@"),
            r("(last|final) +" + N + " +" + DIG + " +of +(your +|the +|his +|her +)*(primary +|primary +)?(credit +|debit +)?card", "CARD@"),
            r("(last|final) +" + N + " +" + DIG + " +of +(your +|the +|his +|her +)*(savings +|current +)?(bank +)?(account|a/c|acc)", "ACC@"),
            r("(last|final) +" + N + " +" + DIG + " +of +(your +|the +)?(customer|cust|cif|crn|relationship)", "ID@"),
            r("(last|final) +" + N + " +" + DIG, "LAST@"),
            r("dd ?/? ?-? ?mm ?/? ?-? ?yyyy|ddmmyyyy", "DDMMYYYY"),
            r("ddmmyy\\b", "DDMMYY"),
            r("ddmm", "DDMM"),
            r("customer relationship n(o|umber)|\\bcrn\\b", "CRN"),
            r("\\bcif\\b", "CIF"),
            r("customer +id|cust\\.? +id|customer +no|customer +number|netbanking +id|user +id", "CUSTID"),
            r("\\bpan\\b|permanent account number", "PAN"),
            r("(account|a/c|acct) +(no|number|num)", "ACCNO"),
            r("(date of birth|dob|birth ?date|birthday)", "DOB"),
            r("\\bmobile( +(no|number))?\\b|\\bphone\\b", "MOBILE"),
            r("\\bcard +(no|number|num)\\b", "CARDNO"),
            r("\\bname\\b", "NAME"));

    private static String num(String s) {
        return switch (s.toLowerCase(Locale.ROOT)) {
            case "two", "2" -> "2";
            case "three", "3" -> "3";
            case "four", "4" -> "4";
            case "five", "5" -> "5";
            case "six", "6" -> "6";
            case "eight", "8" -> "8";
            default -> s;
        };
    }

    /**
     * The hint for a mail: the rules of every sentence of its body that mentions "password", deduplicated, joined by
     * {@link #SEPARATOR} and cut at {@link #HINT_MAX} characters on a rule boundary. Null when there is none.
     */
    public static String hintFromBody(String body) {
        Set<String> rules = new LinkedHashSet<>();
        for (String sentence : passwordSentences(body)) rules.addAll(parse(sentence));
        StringBuilder out = new StringBuilder();
        for (String rule : rules) {
            int add = (out.length() == 0 ? 0 : SEPARATOR.length()) + rule.length();
            if (out.length() + add > HINT_MAX) break;
            if (out.length() > 0) out.append(SEPARATOR);
            out.append(rule);
        }
        return out.length() == 0 ? null : out.toString();
    }

    /** The rules stored in a hint, in order. */
    public static List<String> rulesOf(String hint) {
        List<String> out = new ArrayList<>();
        if (hint == null || hint.isBlank()) return out;
        for (String s : hint.split("\\|")) if (!s.isBlank()) out.add(s.trim());
        return out;
    }

    static List<String> passwordSentences(String body) {
        List<String> out = new ArrayList<>();
        if (body == null || body.isBlank()) return out;
        for (String s : body.split("(?<=[.!?])\\s+|[\\r\\n]+")) {
            if (s.length() <= MAX_SENTENCE_LENGTH && s.toLowerCase(Locale.ROOT).contains("password")) {
                out.add(s);
                if (out.size() >= MAX_SENTENCES) break;
            }
        }
        return out;
    }

    /** Distinct rules found in one sentence; empty if no token. */
    public static Set<String> parse(String sentence) {
        Set<String> out = new LinkedHashSet<>();
        if (sentence == null) return out;
        String s = sentence.toLowerCase(Locale.ROOT).replace('’', '\'');
        for (String seg : SPLIT.split(s)) {
            String p = parseSegment(seg);
            if (!p.isEmpty()) out.add(p);
        }
        return out;
    }

    private static String parseSegment(String seg) {
        TreeMap<Integer, String> found = new TreeMap<>();
        List<int[]> used = new ArrayList<>();
        for (Rule rule : RULES) {
            Matcher m = rule.p.matcher(seg);
            while (m.find()) {
                boolean overlap = false;
                for (int[] u : used) if (m.start() < u[1] && m.end() > u[0]) overlap = true;
                if (overlap) continue;
                used.add(new int[]{m.start(), m.end()});
                String t = rule.token;
                if (t.endsWith("@")) {
                    String n = null;
                    for (int g = 1; g <= m.groupCount(); g++) {
                        String gv = m.group(g);
                        if (gv != null && gv.matches("(?i)(2|3|4|5|6|8|two|three|four|five|six|eight)")) {
                            n = num(gv);
                            break;
                        }
                    }
                    t = t.substring(0, t.length() - 1) + (n == null ? "" : n);
                    if (t.equals("LAST") || t.startsWith("LAST")) t = "LAST" + (n == null ? "" : n);
                    if (t.startsWith("ID")) t = "CUSTID";
                }
                found.put(m.start(), t);
            }
        }
        if (found.isEmpty()) return "";
        boolean dateFmt = found.containsValue("DDMM") || found.containsValue("DDMMYY") || found.containsValue("DDMMYYYY");
        if (dateFmt) found.values().removeIf(t -> t.equals("DOB"));
        boolean upper = UP_P.matcher(seg).find();
        boolean lower = LOW_P.matcher(seg).find();
        List<String> toks = new ArrayList<>();
        for (String t : found.values()) {
            if (t.startsWith("NAME")) t = t + (upper && !lower ? "U" : lower && !upper ? "L" : "");
            toks.add(t);
        }
        // drop generic tokens when a specific one of the same family exists; keep first occurrence only
        boolean nameN = toks.stream().anyMatch(t -> t.matches("NAME\\d.*"));
        boolean cardN = toks.stream().anyMatch(t -> t.matches("CARD\\d"));
        boolean accN = toks.stream().anyMatch(t -> t.matches("ACC\\d"));
        boolean panN = toks.stream().anyMatch(t -> t.matches("PAN\\d"));
        boolean mobN = toks.stream().anyMatch(t -> t.matches("MOBILE\\d"));
        Set<String> dedup = new LinkedHashSet<>();
        for (String t : toks) {
            if (nameN && t.matches("NAME[UL]?")) continue;
            if (cardN && t.equals("CARDNO")) continue;
            if (accN && t.equals("ACCNO")) continue;
            if (panN && t.equals("PAN")) continue;
            if (mobN && t.equals("MOBILE")) continue;
            if (t.equals("CUSTID") && toks.contains("CRN")) continue;
            dedup.add(t);
        }
        return String.join("+", dedup);
    }
}
