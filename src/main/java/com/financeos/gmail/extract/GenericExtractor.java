package com.financeos.gmail.extract;

import com.financeos.gmail.classify.EmailText;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Bank-agnostic fallback: finds the amount next to a transaction verb, the direction from the verb, and
 * the card/account tail, counterparty and date from common phrasings. Reports only what the text states.
 */
final class GenericExtractor {

    private static final String CURRENCY_CODES = "inr|usd|eur|gbp|aed|sgd|idr|thb|jpy|aud|cad|chf|myr|lkr|npr|hkd|cny|sar|qar|kwd|omr|bhd|nzd|zar|vnd|krw|php";
    private static final int FLAGS = Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE;

    private static final Pattern AMOUNT = Pattern.compile(
            "(?:\\b(" + CURRENCY_CODES + ")|([$€£]))\\s?(\\d[\\d,]{0,15}(?:\\.\\d{1,2})?)", FLAGS);
    private static final Pattern LABELLED_AMOUNT = Pattern.compile(
            "\\b(?:transaction amount|txn amount|txn amt|transaction value|amount|amt)\\s*[:\\-]\\s*(?:\\(inr\\)\\s*)?"
                    + "(?:\\b(" + CURRENCY_CODES + ")|([$€£]))\\s?(\\d[\\d,]{0,15}(?:\\.\\d{1,2})?)", FLAGS);
    private static final Pattern NOT_AN_AMOUNT_BEFORE = Pattern.compile(
            "(?:balance|bal|limit|minimum|min|outstanding|due|available|avl)\\b[^.]{0,30}$", FLAGS);

    private static final Pattern DEBIT_VERB = Pattern.compile(
            "\\b(?:debited|spent|paid|sent|withdrawn|charged|purchase|used)\\b|thank you for using", FLAGS);
    private static final Pattern CREDIT_VERB = Pattern.compile(
            "\\b(?:credited|received|deposited|refunded|refund|reversed|reversal|added)\\b", FLAGS);
    private static final Pattern REVERSAL = Pattern.compile("\\b(?:reversed|reversal|refunded|refund)\\b", FLAGS);

    private static final Pattern LAST4 = Pattern.compile(
            "(?:\\bending(?: with| in)?|\\bcard(?: no\\.?| number)?|\\ba/c(?: no\\.?)?|\\baccount(?: no\\.?| number)?|\\bacct)"
                    + "[\\s:#]*[xX*•]*\\s?(\\d{3,6})\\b|[xX*•]{2,}\\s?(\\d{3,6})\\b", FLAGS);
    private static final Pattern VPA = Pattern.compile("\\b(?:vpa|upi id)\\s*:?\\s*([\\w.\\-]+@[\\w.\\-]+)", FLAGS);
    private static final Pattern PARTY = Pattern.compile(
            "\\b(?:at|towards|to|from|by)\\s+(?!(?:your|you|the|a/c|account|acct|card|credit|debit|vpa|upi|report|check|know|avoid|block|call|visit|click|login|log|contact|view|get|be|make|do|see|use|us|me)\\b)"
                    + "([A-Za-z0-9][A-Za-z0-9 &'.*/@_\\-]{1,50}?)"
                    + "(?=\\s+(?:on|dated|using|via|ref|for|from|with|ending|has|is|was|through|at)\\b|[.;,(]|\\s*$)", FLAGS);

    /** A "party" that is really an action or an amount ("withdraw INR 101", "INR 5"): try the next match. */
    private static final Pattern NOT_A_PARTY = Pattern.compile(
            "^(?:withdraw|pay|paying|spend|spending|transfer|transferring|send|sending|make|making|purchase|buy)\\b|\\b(?:" + CURRENCY_CODES + ")\\s?\\d|[$€£]\\s?\\d", FLAGS);

    private static final String MONTH = "(?:jan|feb|mar|apr|may|jun|jul|aug|sep|oct|nov|dec)[a-z]*";
    private static final Pattern DATE_NUMERIC = Pattern.compile("\\b(\\d{1,2})[-/.](\\d{1,2})[-/.](\\d{4}|\\d{2})\\b");
    private static final Pattern DATE_DMY_TEXT = Pattern.compile(
            "\\b(\\d{1,2})(?:st|nd|rd|th)?[-/ ]+(" + MONTH + ")[-/ ,.]*(\\d{4}|\\d{2})?\\b(?![:\\d])", FLAGS);
    private static final Pattern DATE_MDY_TEXT = Pattern.compile("\\b(" + MONTH + ")\\s+(\\d{1,2})(?:st|nd|rd|th)?,?\\s+(\\d{4})\\b", FLAGS);
    private static final Pattern DATE_ISO = Pattern.compile("\\b(\\d{4})-(\\d{2})-(\\d{2})\\b");

    private static final Pattern SENTENCE_SPLIT = Pattern.compile("(?<!(?i:\\b(?:no|ref|txn|amt)\\.))(?<=[.?])\\s+(?=[A-Z])|\\s*" + EmailText.SECTION + "\\s*");

    private GenericExtractor() {}

    static ExtractedTransaction extract(String text, LocalDate sentOn) {
        if (text == null || text.isBlank()) {
            return null;
        }
        String[] sentences = SENTENCE_SPLIT.split(text);
        String chosen = null;
        Matcher amountMatch = null;
        for (String s : sentences) {
            Matcher a = firstAmount(s);
            if (a != null && (DEBIT_VERB.matcher(s).find() || CREDIT_VERB.matcher(s).find())) {
                chosen = s;
                amountMatch = a;
                break;
            }
        }
        if (chosen == null) {
            Matcher labelled = LABELLED_AMOUNT.matcher(text);
            if (!labelled.find()) {
                return null;
            }
            chosen = text;
            amountMatch = labelled;
        }
        BigDecimal amount = ExtractParsing.parseAmount(amountMatch.group(3));
        if (amount == null) {
            return null;
        }
        String currency = amountMatch.group(1) != null
                ? ExtractParsing.parseCurrency(amountMatch.group(1), "INR")
                : ExtractParsing.parseCurrency(amountMatch.group(2), "INR");
        Direction direction = direction(chosen);
        if (direction == null && chosen != text) {
            direction = direction(text);
        }
        return new ExtractedTransaction(amount, currency, direction, date(chosen, text, sentOn),
                counterparty(chosen, text), last4(chosen, text), Tier.GENERIC, null,
                ExtractedTransaction.GENERIC_CONFIDENCE);
    }

    private static Matcher firstAmount(String sentence) {
        Matcher m = AMOUNT.matcher(sentence);
        while (m.find()) {
            String before = sentence.substring(Math.max(0, m.start() - 40), m.start());
            if (!NOT_AN_AMOUNT_BEFORE.matcher(before).find()) {
                return m;
            }
        }
        return null;
    }

    static Direction direction(String s) {
        if (REVERSAL.matcher(s).find()) {
            return Direction.CREDIT;
        }
        Matcher d = DEBIT_VERB.matcher(s);
        Matcher c = CREDIT_VERB.matcher(s);
        boolean hasD = d.find();
        boolean hasC = c.find();
        if (hasD && hasC) {
            return d.start() <= c.start() ? Direction.DEBIT : Direction.CREDIT;
        }
        return hasD ? Direction.DEBIT : hasC ? Direction.CREDIT : null;
    }

    private static String last4(String sentence, String text) {
        for (String scope : new String[]{sentence, text}) {
            Matcher m = LAST4.matcher(scope);
            if (m.find()) {
                return ExtractParsing.parseLast4(m.group(1) != null ? m.group(1) : m.group(2));
            }
        }
        return null;
    }

    private static String counterparty(String sentence, String text) {
        for (String scope : new String[]{sentence}) {
            Matcher v = VPA.matcher(scope);
            if (v.find()) {
                return ExtractParsing.cleanCounterparty(v.group(1), null);
            }
            Matcher m = PARTY.matcher(scope);
            while (m.find()) {
                String cp = ExtractParsing.cleanCounterparty(m.group(1), null);
                if (cp != null && !NOT_A_PARTY.matcher(cp).find()) {
                    return cp;
                }
            }
        }
        return null;
    }

    private static LocalDate date(String sentence, String text, LocalDate sentOn) {
        for (String scope : new String[]{sentence, text}) {
            LocalDate d = firstDate(scope, sentOn);
            if (d != null) {
                return d;
            }
        }
        return null;
    }

    private static LocalDate firstDate(String s, LocalDate sentOn) {
        LocalDate best = null;
        int bestAt = Integer.MAX_VALUE;
        Matcher m = DATE_ISO.matcher(s);
        if (m.find() && m.start() < bestAt) {
            LocalDate d = ExtractParsing.parseDate(m.group(), List.of("yyyy-MM-dd"), sentOn);
            if (d != null) {
                best = d;
                bestAt = m.start();
            }
        }
        m = DATE_NUMERIC.matcher(s);
        if (m.find() && m.start() < bestAt) {
            String pattern = m.group(3).length() == 4 ? "d-M-yyyy" : "d-M-yy";
            LocalDate d = ExtractParsing.parseDate(m.group(1) + "-" + m.group(2) + "-" + m.group(3), List.of(pattern), sentOn);
            if (d != null) {
                best = d;
                bestAt = m.start();
            }
        }
        m = DATE_DMY_TEXT.matcher(s);
        if (m.find() && m.start() < bestAt) {
            String mon = m.group(2).substring(0, 3).toLowerCase(Locale.ROOT);
            String year = m.group(3);
            String raw = m.group(1) + " " + mon + (year == null ? "" : " " + year);
            String pattern = year == null ? "d MMM" : year.length() == 4 ? "d MMM yyyy" : "d MMM yy";
            LocalDate d = ExtractParsing.parseDate(raw, List.of(pattern), sentOn);
            if (d != null) {
                best = d;
                bestAt = m.start();
            }
        }
        m = DATE_MDY_TEXT.matcher(s);
        if (m.find() && m.start() < bestAt) {
            String raw = m.group(1).substring(0, 3) + " " + m.group(2) + " " + m.group(3);
            LocalDate d = ExtractParsing.parseDate(raw, List.of("MMM d yyyy"), sentOn);
            if (d != null) {
                best = d;
            }
        }
        return best;
    }
}
