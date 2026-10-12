package com.financeos.gmail.reconcile;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Masked account numbers a bank prints around a statement it password-protects, e.g. "customer ID
 * XXX1277" in the body, "XXX1277.pdf" as the attachment name, "407186******2165", or a masked
 * customer ID such as "043-XXXXXX". Only masked tokens count: a bare number in an email is far more
 * often a phone number, amount or date than an account.
 */
final class MaskedNumberHints {

    private static final Pattern TOKEN = Pattern.compile("(?<![A-Za-z0-9])[0-9Xx*•-]{5,}(?![A-Za-z0-9])");
    private static final Pattern MASK = Pattern.compile("[Xx*•]");
    private static final Pattern TRAILING_DIGITS = Pattern.compile("[Xx*•][-\\s]?(\\d{3,})$");
    private static final Pattern LEADING_DIGITS = Pattern.compile("^(\\d{3,4})-?[Xx*•]");

    private MaskedNumberHints() {}

    /** The visible digits of each masked token (at most the last four), in order of first appearance. */
    static List<String> find(String... texts) {
        Set<String> found = new LinkedHashSet<>();
        for (String text : texts) {
            if (text == null) {
                continue;
            }
            Matcher token = TOKEN.matcher(text);
            while (token.find()) {
                String digits = visibleDigits(token.group());
                if (digits != null) {
                    found.add(digits);
                }
            }
        }
        return new ArrayList<>(found);
    }

    private static String visibleDigits(String token) {
        if (MASK.matcher(token).results().count() < 2) {
            return null;
        }
        Matcher trailing = TRAILING_DIGITS.matcher(token);
        if (trailing.find()) {
            String digits = trailing.group(1);
            return digits.length() > 4 ? digits.substring(digits.length() - 4) : digits;
        }
        Matcher leading = LEADING_DIGITS.matcher(token);
        return leading.find() ? leading.group(1) : null;
    }
}
