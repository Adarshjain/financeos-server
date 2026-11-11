package com.financeos.domain.instrument.corporateaction.detect;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Reads the "purpose" text of an exchange corporate-action row. The text is a list of parts joined by "/" or "And"
 * ("Annual General Meeting/ Dividend - Rs 3/- Per Share / Bonus - 1:2"). Dividends, meetings, interest, rights and
 * buybacks never change the share count and are ignored; bonus, split, consolidation, demerger, merger and
 * restructuring parts are structural. A subject is {@code clean} only when it holds exactly one bonus or one split
 * and nothing it does not understand.
 */
public final class NseSubjectParser {

    /** {@code ratioFrom}/{@code ratioTo} are units held before and after (a 1:1 bonus is 1 to 2); null when not readable. */
    public record Parsed(SuggestionKind type, Integer ratioFrom, Integer ratioTo, boolean clean) {
    }

    private static final String NUM = "(\\d+(?:\\.\\d+)?)";
    private static final String RUPEE = "r[se]?\\.?\\s*";

    private static final Pattern BONUS = Pattern.compile("^bonus\\s*-?\\s*(\\d+)\\s*:\\s*(\\d+)$");
    private static final Pattern SPLIT = Pattern.compile("^face value split(?:\\s*\\(sub-?\\s*division\\))?\\s*-?\\s*from\\s+"
            + RUPEE + NUM + "\\s*(?:per share)?\\s*to\\s+" + RUPEE + NUM + "\\s*(?:per share)?$");
    private static final Pattern CONSOLIDATION = Pattern.compile("consolidation.*?from\\s+" + RUPEE + NUM
            + ".*?to\\s+" + RUPEE + NUM);

    private NseSubjectParser() {
    }

    /** The structural reading of {@code subject}, or null when it has no structural part (nothing to store). */
    public static Parsed parse(String subject) {
        if (subject == null || subject.isBlank()) {
            return null;
        }
        String text = subject.toLowerCase(Locale.ROOT).replaceAll("\\s*/-", "").replaceAll("\\s+", " ").trim();
        List<Parsed> structural = new ArrayList<>();
        boolean unrecognised = false;
        for (String raw : text.split("\\s*/\\s*|\\s+and\\s+|\\s*&\\s*")) {
            String part = raw.trim();
            if (part.isEmpty()) {
                continue;
            }
            Parsed parsed = structural(part);
            if (parsed != null) {
                structural.add(parsed);
            } else if (!ignorable(part)) {
                unrecognised = true;
            }
        }
        if (structural.isEmpty()) {
            return null;
        }
        if (structural.size() > 1) {
            return new Parsed(SuggestionKind.other, null, null, false);
        }
        Parsed only = structural.get(0);
        return unrecognised ? new Parsed(only.type(), only.ratioFrom(), only.ratioTo(), false) : only;
    }

    private static boolean ignorable(String part) {
        return part.contains("dividend") || part.contains("annual general meeting") || part.equals("agm")
                || part.contains("interest") || part.startsWith("rights") || part.contains("buyback")
                || part.contains("buy back") || part.contains("buy-back");
    }

    /** The part as a structural event, or null when it is not one. */
    private static Parsed structural(String part) {
        if (part.contains("bonus")) {
            Matcher m = BONUS.matcher(part);
            if (m.matches()) {
                try {
                    int newShares = Integer.parseInt(m.group(1));
                    int held = Integer.parseInt(m.group(2));
                    if (newShares > 0 && held > 0 && (long) newShares + held <= Integer.MAX_VALUE) {
                        return new Parsed(SuggestionKind.bonus, held, newShares + held, true);
                    }
                } catch (NumberFormatException e) {
                    // digits beyond int range: not a readable ratio
                }
            }
            return new Parsed(SuggestionKind.other, null, null, false);
        }
        if (part.contains("consolidation")) {
            Matcher m = CONSOLIDATION.matcher(part);
            Parsed ratio = m.find() ? faceValueRatio(m.group(1), m.group(2), false) : null;
            return ratio != null ? ratio : new Parsed(SuggestionKind.other, null, null, false);
        }
        if (part.contains("face value split") || part.contains("sub-division") || part.contains("sub division")) {
            Matcher m = SPLIT.matcher(part);
            Parsed ratio = m.matches() ? faceValueRatio(m.group(1), m.group(2), true) : null;
            return ratio != null ? ratio : new Parsed(SuggestionKind.other, null, null, false);
        }
        if (part.contains("demerger")) {
            return new Parsed(SuggestionKind.demerger, null, null, false);
        }
        if (part.contains("amalgamation") || part.contains("merger")) {
            return new Parsed(SuggestionKind.merger, null, null, false);
        }
        if (part.contains("scheme of arrangement") || part.contains("capital reduction") || part.contains("reduction of capital")) {
            return new Parsed(SuggestionKind.other, null, null, false);
        }
        return null;
    }

    /** Old face value x to new face value y: units go from y/g to x/g (g = gcd); null when the values are unusable or equal. */
    private static Parsed faceValueRatio(String oldFace, String newFace, boolean clean) {
        BigDecimal x = new BigDecimal(oldFace);
        BigDecimal y = new BigDecimal(newFace);
        int scale = Math.max(Math.max(x.scale(), y.scale()), 0);
        BigInteger xi = x.movePointRight(scale).toBigIntegerExact();
        BigInteger yi = y.movePointRight(scale).toBigIntegerExact();
        if (xi.signum() <= 0 || yi.signum() <= 0 || xi.equals(yi)) {
            return null;
        }
        BigInteger g = xi.gcd(yi);
        BigInteger from = yi.divide(g);
        BigInteger to = xi.divide(g);
        if (from.bitLength() > 30 || to.bitLength() > 30) {
            return null;
        }
        return new Parsed(SuggestionKind.split, from.intValue(), to.intValue(), clean);
    }
}
