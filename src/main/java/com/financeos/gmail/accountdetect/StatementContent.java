package com.financeos.gmail.accountdetect;

import com.financeos.gmail.accountdetect.Vocab.Kind;

import java.time.LocalDate;
import java.time.YearMonth;
import java.util.List;

/**
 * Derived fields read from inside one statement (an unprotected PDF, or a statement the app already parsed and
 * stored). No text, amounts or names: limit is a bucket only.
 *
 * @param source     PDF_MAIL, PDF_FOLDER or DB
 * @param addOnLast4 add-on card numbers listed in the statement (they belong to {@code last4} as primary)
 * @param kind       null when the document carries no statement markers (not a statement)
 * @param periodEnd  the statement date / period end read from the document, or null (then {@code period} may be a
 *                   fallback from the mail date)
 * @param rawProduct the product phrase as printed in the title/header ("Regalia Gold Credit Card"), or null; the
 *                   catalogue name (if any) is {@code variant}
 * @param originCluster for a PDF taken from a mail: that mail's layout cluster (hashed); null otherwise
 * @param originDate    for a PDF taken from a mail: that mail's date; null otherwise
 */
public record StatementContent(String source, String bank, String last4, Kind kind, YearMonth period, String variant,
                               String limitBucket, List<String> addOnLast4, boolean mentionsClosure, LocalDate periodEnd,
                               String originCluster, LocalDate originDate, String rawProduct) {
    public StatementContent {
        addOnLast4 = addOnLast4 == null ? List.of() : List.copyOf(addOnLast4);
    }

    public StatementContent(String source, String bank, String last4, Kind kind, YearMonth period, String variant,
                            String limitBucket, List<String> addOnLast4, boolean mentionsClosure, LocalDate periodEnd,
                            String originCluster, LocalDate originDate) {
        this(source, bank, last4, kind, period, variant, limitBucket, addOnLast4, mentionsClosure, periodEnd, originCluster, originDate, null);
    }

    public StatementContent(String source, String bank, String last4, Kind kind, YearMonth period, String variant,
                            String limitBucket, List<String> addOnLast4, boolean mentionsClosure) {
        this(source, bank, last4, kind, period, variant, limitBucket, addOnLast4, mentionsClosure, null, null, null);
    }

    public StatementContent(String source, String bank, String last4, Kind kind, YearMonth period, String variant,
                            String limitBucket, List<String> addOnLast4, boolean mentionsClosure, LocalDate periodEnd) {
        this(source, bank, last4, kind, period, variant, limitBucket, addOnLast4, mentionsClosure, periodEnd, null, null);
    }

    /** The same document, tagged with the mail it came from. */
    public StatementContent withOrigin(String cluster, LocalDate date) {
        return new StatementContent(source, bank, last4, kind, period, variant, limitBucket, addOnLast4, mentionsClosure, periodEnd, cluster, date, rawProduct);
    }

    public boolean fromMail() {
        return originCluster != null;
    }

    /** The same statement linked to an own number found outside the document. */
    public StatementContent withLast4(String l4) {
        return new StatementContent(source, bank, l4, kind, period, variant, limitBucket, addOnLast4, mentionsClosure, periodEnd,
                originCluster, originDate, rawProduct);
    }

    /** Credit limit as a bucket: <1L, 1-3L, 3-5L, 5-10L, >10L. */
    public static String bucket(double limit) {
        if (limit <= 0) return null;
        if (limit < 100_000) return "<1L";
        if (limit < 300_000) return "1-3L";
        if (limit < 500_000) return "3-5L";
        if (limit <= 1_000_000) return "5-10L";
        return ">10L";
    }
}
