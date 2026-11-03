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

    static final int MAX_PAGES = DocumentReader.MAX_PAGES;
    static final int HEADER_LINES = DocumentFacts.HEADER_LINES;

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
        return new Result(Status.OPENED, r.holder() == null ? c : c.withHolder(r.holder()), flags);
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
        return new StatementContent(source, bank, f.last4(), kind, period, variant, limit, f.addOnLast4(), f.closure(), f.periodEnd(), null, null, f.rawProduct(), limitAmount, null);
    }
}
