package com.financeos.gmail.accountdetect;

import com.financeos.gmail.accountdetect.Vocab.Kind;
import com.financeos.gmail.accountdetect.Vocab.LabelKind;
import com.financeos.statement.parser.DocumentFacts;
import com.financeos.statement.parser.DocumentReader;

import java.time.YearMonth;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Pattern;

/**
 * Derived fields from a statement PDF that opens WITHOUT a password. The reading itself (issuer, kind, period, numbers,
 * limit, product phrase, closure) lives in the app's statement parser ({@link DocumentReader}); this adapter supplies the
 * detector's number vocabulary and maps the result to {@link StatementContent}. Never supplies, derives or guesses a
 * password; only {@link StatementContent} fields leave this class.
 */
public final class StatementPdfReader {

    public enum Status { OPENED, PROTECTED, FAILED, NO_TEXT, NO_BANK }

    /**
     * @param flags shape-only diagnostics (which header fields were found), never text: e.g. "own:none",
     *              "header-numbers:plain-only", "period:fallback"
     */
    public record Result(Status status, StatementContent content, Set<String> flags) {
        public Result(Status status, StatementContent content) {
            this(status, content, Set.of());
        }
    }

    /**
     * Version of what this reader makes of a PDF (kind, last4, period, limit). Stored with each mail's facts; a PDF fact stored
     * with an older version (absent = 0) is read again by the account-detect run. Bump it whenever a change to this class or to
     * {@link DocumentReader} / DocumentFacts can change what a stored PDF reads as.
     * 1: loan documents (loan-statement heading or loan number) are no card or account statement.
     * 2: parse-engine hints (period end, limits) read with the 11-09 parser fixes.
     * 3: a Jupiter / Fi statement reads as its real partner bank (IFSC, named bank, only partner); only brand mails are
     *    stale at 3 ({@link com.financeos.gmail.accountdetect.job.FactsCodec.Facts#readerStale()}).
     */
    public static final int READER_VERSION = 3;
    /** Version 3 changed only how brand-mail statements read: older (but version 2) facts are stale for brand mails alone. */
    public static final int BRAND_ONLY_SINCE = 3;

    static final int MAX_PAGES = DocumentReader.MAX_PAGES;
    static final int HEADER_LINES = DocumentFacts.HEADER_LINES;

    private static final Pattern RUPAY_TXNS = Pattern.compile("(?i)\\brupay transactions\\s*-\\s*(\\d{4})\\b");
    private static final Pattern ADDON_LINE = Pattern.compile("(?i)add-?on|add on card|additional card|supplementary");

    private StatementPdfReader() {}

    /** The detector's reading of numbers: labels, placeholders and closure phrases. */
    private static final DocumentFacts.Vocabulary VOCAB = new DocumentFacts.Vocabulary() {
        @Override
        public List<DocumentFacts.DocNumber> scan(String line) {
            List<DocumentFacts.DocNumber> out = new ArrayList<>();
            for (FeatureExtractor.Num n : FeatureExtractor.numbersIn(line)) {
                LabelKind k = n.label();
                out.add(new DocumentFacts.DocNumber(n.last4(), n.masked(), k != LabelKind.NONE,
                        k == LabelKind.CREDIT_CARD || k == LabelKind.DEBIT_CARD || k == LabelKind.CARD, k == LabelKind.ACCOUNT, k.rejected(), n.start()));
            }
            return out;
        }

        @Override
        public boolean placeholder(String last4) {
            return Detector.placeholder(last4);
        }

        @Override
        public boolean closure(String line) {
            return FeatureExtractor.eventsIn(line).contains(Vocab.EventType.CLOSED);
        }
    };

    public static Result read(byte[] pdf, String source, String bankHint, YearMonth fallbackPeriod) {
        return read(pdf, source, bankHint, fallbackPeriod, null);
    }

    /**
     * Same, for a protected PDF the caller has the password of (the caller found it; it is used for this call only and
     * never kept). A null password reads the PDF as the plain overload does.
     */
    public static Result read(byte[] pdf, String source, String bankHint, YearMonth fallbackPeriod, String password) {
        return read(pdf, source, bankHint, fallbackPeriod, password, null);
    }

    /**
     * Same, with the user's holder-name check ({@link HolderCheck#forName}; may be null): its answer, Y / N / null, is kept on
     * the content as {@link StatementContent#holder()}.
     */
    public static Result read(byte[] pdf, String source, String bankHint, YearMonth fallbackPeriod, String password,
                              java.util.function.UnaryOperator<String> holderCheck) {
        Set<String> flags = new TreeSet<>();
        DocumentReader.Read r = DocumentReader.read(pdf, password, VOCAB, flags, holderCheck);
        switch (r.outcome()) {
            case PROTECTED: return new Result(Status.PROTECTED, null);
            case FAILED: return new Result(Status.FAILED, null);
            case NO_TEXT: return new Result(Status.NO_TEXT, null);
            default: break;
        }
        StatementContent c = map(r.facts(), source, bankHint, fallbackPeriod, flags);
        if (c == null) return new Result(Status.NO_BANK, null, flags);
        if (BankPartners.isMailBrand(bankHint) && c.last4() == null && c.kind() == null) {
            // a Jupiter Edge card statement prints no issuer, number or title as text: only "Rupay Transactions - 2183" above its rows
            String l4 = rupayTransactionsNumber(pdf, password);
            if (l4 != null) {
                c = c.withLast4(l4).withKind(Kind.CREDIT_CARD);
                flags.add("number:rupay-transactions");
            }
        }
        return new Result(Status.OPENED, r.holder() == null ? c : c.withHolder(r.holder()), flags);
    }

    /** The number in the "Rupay Transactions - NNNN" heading of the first pages, or null (also when the PDF cannot be read). */
    private static String rupayTransactionsNumber(byte[] pdf, String password) {
        try (org.apache.pdfbox.pdmodel.PDDocument doc = password == null ? org.apache.pdfbox.Loader.loadPDF(pdf) : org.apache.pdfbox.Loader.loadPDF(pdf, password)) {
            org.apache.pdfbox.text.PDFTextStripper stripper = new org.apache.pdfbox.text.PDFTextStripper();
            stripper.setSortByPosition(true);
            stripper.setEndPage(Math.min(MAX_PAGES, doc.getNumberOfPages()));
            java.util.regex.Matcher m = RUPAY_TXNS.matcher(stripper.getText(doc));
            return m.find() ? m.group(1) : null;
        } catch (java.io.IOException | RuntimeException e) {
            return null;
        }
    }

    /** Derived fields from statement text; null when the bank cannot be told. */
    static StatementContent fromText(String text, String source, String bankHint, YearMonth fallbackPeriod) {
        return fromText(text, source, bankHint, fallbackPeriod, new TreeSet<>());
    }

    static StatementContent fromText(String text, String source, String bankHint, YearMonth fallbackPeriod, Set<String> flags) {
        return map(DocumentReader.fromText(text, VOCAB, flags, null), source, bankHint, fallbackPeriod, flags);
    }

    private static StatementContent map(DocumentFacts.Facts f, String source, String bankHint, YearMonth fallbackPeriod, Set<String> flags) {
        String bank = bankHint;
        if (bank == null || BankDirectory.isApp(bank)) {
            bank = f.issuer();
            if (bank == null) return null;
        } else {
            flags.removeIf(x -> x.startsWith("bank:"));
            if (f.issuer() != null && !f.issuer().equals(bank)) flags.add("bank:hint-differs");
        }
        YearMonth period = f.periodEnd() == null ? null : YearMonth.from(f.periodEnd());
        if (period == null && fallbackPeriod != null) {
            flags.remove("period:none");
            flags.add("period:fallback");
            period = fallbackPeriod;
        }
        Kind kind = f.kind() == null ? null : f.kind() == DocumentFacts.DocKind.CREDIT_CARD ? Kind.CREDIT_CARD : Kind.ACCOUNT;
        // the issuer's catalogue name for the title region's product (the raw phrase is kept as printed)
        Set<String> products = new LinkedHashSet<>();
        for (String l : f.top()) if (!ADDON_LINE.matcher(l).find()) products.addAll(FeatureExtractor.productPhrases(l, bank));
        String variant = products.size() == 1 ? products.iterator().next() : null;

        String limit = f.limit() == null ? null : StatementContent.bucket(f.limit());
        java.math.BigDecimal limitAmount = f.limit() == null || f.limit() <= 0 ? null : java.math.BigDecimal.valueOf(f.limit()).setScale(2, java.math.RoundingMode.HALF_UP);
        String real = BankPartners.resolveBank(bank, f.top() == null ? null : String.join("\n", f.top()));
        if (!real.equals(bank)) flags.add("bank:partner");
        return new StatementContent(source, real, f.last4(), kind, period, variant, limit, f.addOnLast4(), f.closure(), f.periodEnd(), null, null, f.rawProduct(), limitAmount, null);
    }
}
