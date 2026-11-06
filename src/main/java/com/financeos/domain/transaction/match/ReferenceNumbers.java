package com.financeos.domain.transaction.match;

import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Reference numbers a bank prints on an alert or a statement line: UPI / IMPS RRN (12 digits), NEFT / RTGS UTR and
 * cheque numbers. Only labelled or unmistakably shaped references are taken; anything unsure (a masked card or account
 * number, two different 12-digit numbers in one UPI narration) gives nothing, since two records whose references
 * differ are never paired.
 */
public final class ReferenceNumbers {

    private ReferenceNumbers() {
    }

    /** "UPI Ref No 412345678901", "RRN: 412345678901", "Ref No. 412345678901", "UTR 412345678901", "IMPS Ref 412345678901". */
    private static final Pattern LABELLED_12 = Pattern.compile(
            "(?:UPI\\s*REF(?:ERENCE)?(?:\\s*(?:NO|NUMBER|ID))?|IMPS\\s*REF(?:ERENCE)?(?:\\s*NO)?|RRN(?:\\s*NO)?"
                    + "|REF(?:ERENCE)?\\s*(?:NO|NUMBER|ID)|UTR(?:\\s*NO)?)[\\s.:#\\-]*(\\d{12})(?!\\d)");

    /** A UPI or IMPS narration ("UPI/DR/412345678901/SWIGGY", "UPI-ZOMATO-zomato@ptybl-YESB0PTMUPI-412345678901-Pay", "MMT/IMPS/412345678901/..."). */
    private static final Pattern UPI_OR_IMPS = Pattern.compile("(?<![A-Z0-9])(?:UPI|IMPS)(?![A-Z0-9])");

    /** A standalone 12-digit number. */
    private static final Pattern STANDALONE_12 = Pattern.compile("(?<![A-Z0-9])(\\d{12})(?![A-Z0-9])");

    /** "UTR: HDFCN52024101512345678", "UTR NO SBIN324123456789". */
    private static final Pattern LABELLED_UTR = Pattern.compile("UTR(?:\\s*NO)?[\\s.:#\\-]*([A-Z0-9]{16,22})(?![A-Z0-9])");

    private static final Pattern NEFT_OR_RTGS = Pattern.compile("(?<![A-Z0-9])(?:NEFT|RTGS)(?![A-Z0-9])");

    /** Bank UTR shapes on a NEFT / RTGS narration: IFSC bank code + N/R/H + digits, or N/H/R + 15 digits. */
    private static final Pattern UTR_SHAPE = Pattern.compile("(?<![A-Z0-9])([A-Z]{4}[NRH]\\d{9,17}|[NRH]\\d{15})(?![A-Z0-9])");

    /** "CHQ NO 004512", "Cheque No. 123456", "CHQ:123456". */
    private static final Pattern CHEQUE = Pattern.compile("(?:CHQ|CHEQUE)(?:\\s*NO)?[\\s.:#\\-]*(\\d{6})(?!\\d)");

    public static Set<String> extract(String text) {
        Set<String> refs = new LinkedHashSet<>();
        if (text == null || text.isBlank()) {
            return refs;
        }
        String s = text.toUpperCase(Locale.ROOT);

        Matcher m = LABELLED_12.matcher(s);
        while (m.find()) {
            refs.add(m.group(1));
        }
        if (refs.isEmpty() && UPI_OR_IMPS.matcher(s).find()) {
            Set<String> found = new LinkedHashSet<>();
            Matcher n = STANDALONE_12.matcher(s);
            while (n.find()) {
                found.add(n.group(1));
            }
            if (found.size() == 1) {
                refs.addAll(found);
            }
        }

        Matcher u = LABELLED_UTR.matcher(s);
        while (u.find()) {
            String utr = u.group(1);
            if (digits(utr) >= 8) {
                refs.add(utr);
            }
        }
        if (NEFT_OR_RTGS.matcher(s).find()) {
            Matcher shape = UTR_SHAPE.matcher(s);
            while (shape.find()) {
                refs.add(shape.group(1));
            }
        }

        Matcher c = CHEQUE.matcher(s);
        while (c.find()) {
            refs.add("CHQ:" + c.group(1).replaceFirst("^0+(?=\\d)", ""));
        }
        return refs;
    }

    private static int digits(String s) {
        int n = 0;
        for (int i = 0; i < s.length(); i++) {
            if (Character.isDigit(s.charAt(i))) n++;
        }
        return n;
    }
}
