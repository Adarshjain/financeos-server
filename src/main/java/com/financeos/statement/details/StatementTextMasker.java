package com.financeos.statement.details;

import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Deterministic allowlist redaction of statement text before it is sent to an LLM. Lines are kept only when they hold a
 * summary keyword, plus the {@value #VALUE_LINES_AFTER_KEYWORD} lines right after (tabular values under labels). Within a
 * kept line only summary words, numbers, dates and amounts survive: every other word is dropped, so a name or address
 * that the PDF text merged into a summary line never leaves. Email lines are dropped, and every run of 6+ digits is
 * masked keeping its last 4 ({@code XXXXXX1234}). Decimal amounts (a digit run touching a '.' or ',' group) are kept.
 */
public final class StatementTextMasker {

    static final int VALUE_LINES_AFTER_KEYWORD = 2;
    private static final Pattern EMAIL = Pattern.compile("[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}");
    private static final Pattern KEYWORD = Pattern.compile(
            "statement|period|billing|cycle|date|due|payment|total|minimum|minimal|amount|outstanding|balance|\\bfrom\\b|\\bto\\b",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern SPACED_NUMBER = Pattern.compile("(?<!\\d)(\\d{4})[ -](\\d{4})[ -](\\d{4})[ -](\\d{4})(?!\\d)");
    private static final Pattern LONG_DIGITS = Pattern.compile("(?<![\\d.,])\\d{6,}(?!\\d|[.,]\\d)");
    private static final Pattern DIGIT = Pattern.compile("\\d");
    private static final Pattern NO_LETTERS_OR_DIGITS = Pattern.compile("[^\\p{L}\\d]*");
    /** Words a statement summary is written in; any other word is dropped from a kept line. */
    private static final Set<String> SUMMARY_WORDS = Set.of(
            "statement", "period", "billing", "bill", "cycle", "date", "dated", "due", "dues", "payment", "payments", "pay",
            "paid", "received", "total", "minimum", "min", "minimal", "amount", "amt", "outstanding", "balance", "from", "to",
            "till", "credit", "credits", "debit", "debits", "limit", "available", "cash", "previous", "opening", "closing",
            "purchases", "charges", "finance", "interest", "fee", "fees", "card", "number", "no", "new", "current", "last",
            "net", "other", "summary", "account", "generation", "days", "of", "the", "and", "by", "on", "before", "rs", "inr",
            "cr", "dr", "jan", "january", "feb", "february", "mar", "march", "apr", "april", "may", "jun", "june", "jul",
            "july", "aug", "august", "sep", "sept", "september", "oct", "october", "nov", "november", "dec", "december");

    private StatementTextMasker() {
    }

    public static String mask(String text) {
        return mask(text, List.of());
    }

    /** nameTokens: the account holder's name parts (3+ letters); a word matching one is always dropped. */
    public static String mask(String text, List<String> nameTokens) {
        if (text == null || text.isEmpty()) {
            return "";
        }
        Set<String> names = new HashSet<>();
        for (String t : nameTokens) {
            if (t != null && t.length() >= 3) {
                names.add(t.toLowerCase(Locale.ROOT));
            }
        }
        StringBuilder out = new StringBuilder();
        int carry = 0;
        for (String line : text.split("\\R", -1)) {
            boolean keyword = KEYWORD.matcher(line).find();
            boolean keep = keyword || (carry > 0 && !line.isBlank());
            if (keyword) {
                carry = VALUE_LINES_AFTER_KEYWORD;
            } else if (carry > 0 && !line.isBlank()) {
                carry--;
            }
            if (!keep || EMAIL.matcher(line).find()) {
                continue;
            }
            String kept = summaryWordsOnly(maskDigits(line), names);
            if (!kept.isEmpty()) {
                out.append(kept).append('\n');
            }
        }
        return out.toString().stripTrailing();
    }

    // Keeps numbers, dates, amounts, punctuation and summary words; drops every other word.
    private static String summaryWordsOnly(String line, Set<String> names) {
        StringBuilder kept = new StringBuilder();
        for (String token : line.trim().split("\\s+")) {
            String word = token.replaceAll("[^\\p{L}]", "").toLowerCase(Locale.ROOT);
            boolean value = DIGIT.matcher(token).find() || NO_LETTERS_OR_DIGITS.matcher(token).matches();
            boolean summaryWord = !word.isEmpty() && SUMMARY_WORDS.contains(word) && !names.contains(word);
            if ((value && !names.contains(word)) || summaryWord) {
                if (kept.length() > 0) {
                    kept.append(' ');
                }
                kept.append(token);
            }
        }
        return kept.toString();
    }

    static String maskDigits(String line) {
        String s = SPACED_NUMBER.matcher(line).replaceAll("XXXXXXXXXXXX$4");
        return LONG_DIGITS.matcher(s).replaceAll(m -> {
            String d = m.group();
            return "X".repeat(d.length() - 4) + d.substring(d.length() - 4);
        });
    }
}
