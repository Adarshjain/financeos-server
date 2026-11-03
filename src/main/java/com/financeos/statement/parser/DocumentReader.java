package com.financeos.statement.parser;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.encryption.InvalidPasswordException;
import org.apache.pdfbox.text.PDFTextStripper;

import java.io.IOException;
import java.time.LocalDate;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Reads a statement PDF that opens WITHOUT a password and returns {@link DocumentFacts}: no password is supplied,
 * derived or guessed, and an encrypted document (even one that loads) is PROTECTED. When the text rules leave a
 * gap, the transaction parser's own metadata and summary fields fill it. Text stays in memory.
 */
public final class DocumentReader {

    private DocumentReader() {}

    public enum Outcome { OK, PROTECTED, FAILED, NO_TEXT }

    public record Read(Outcome outcome, DocumentFacts.Facts facts) {}

    public static final int MAX_PAGES = 8;

    /** A vocabulary that reads no numbers (the parser itself has no card-number labelling rules of the detector). */
    static final DocumentFacts.Vocabulary NO_NUMBERS = new DocumentFacts.Vocabulary() {
        @Override public List<DocumentFacts.DocNumber> scan(String line) { return List.of(); }
        @Override public boolean placeholder(String last4) { return false; }
        @Override public boolean closure(String line) { return false; }
    };

    public static Read read(byte[] pdf, DocumentFacts.Vocabulary vocab, Set<String> flags) {
        if (pdf == null || pdf.length == 0) return new Read(Outcome.FAILED, null);
        String text;
        try (PDDocument doc = Loader.loadPDF(pdf)) {
            if (doc.isEncrypted()) return new Read(Outcome.PROTECTED, null);
            PDFTextStripper stripper = new PDFTextStripper();
            stripper.setSortByPosition(true);
            stripper.setStartPage(1);
            stripper.setEndPage(Math.min(MAX_PAGES, doc.getNumberOfPages()));
            text = stripper.getText(doc);
        } catch (InvalidPasswordException e) {
            return new Read(Outcome.PROTECTED, null);
        } catch (IOException | RuntimeException e) {
            String blob = (e.getClass().getSimpleName() + " " + e.getMessage()).toLowerCase(Locale.ROOT);
            if (blob.contains("password") || blob.contains("decrypt") || blob.contains("encrypt")) return new Read(Outcome.PROTECTED, null);
            return new Read(Outcome.FAILED, null);
        }
        if (text == null || text.isBlank()) return new Read(Outcome.NO_TEXT, null);
        return new Read(Outcome.OK, fromText(text, vocab, flags, () -> engineHints(pdf)));
    }

    /** Facts from statement text; {@code engine} may be null. */
    public static DocumentFacts.Facts fromText(String text, DocumentFacts.Vocabulary vocab, Set<String> flags,
                                               java.util.function.Supplier<DocumentFacts.Hints> engine) {
        return DocumentFacts.extract(text, vocab, flags, engine);
    }

    private static DocumentFacts.Hints engineHints(byte[] pdf) {
        try {
            ParsedStatement ps = StatementParseEngine.parse(pdf, null, null);
            StatementMeta meta = ps.meta();
            String acct = meta.accountNumber;
            String masked4 = null;
            if (acct != null && acct.matches(".*[Xx*].*") && acct.replaceAll("[^0-9]", "").length() >= 4) {
                String digits = acct.replaceAll("[^0-9]", "");
                String tail = acct.replaceAll("[^0-9Xx*]", "");
                if (tail.length() >= 4 && tail.substring(tail.length() - 4).matches("\\d{4}")) masked4 = digits.substring(digits.length() - 4);
            }
            Object lim = ps.summaryFields() == null ? null : ps.summaryFields().get("credit_limit");
            Double smaller = null;
            for (String k : List.of("available_credit_limit", "available_cash_limit", "cash_limit")) {
                Object v = ps.summaryFields() == null ? null : ps.summaryFields().get(k);
                if (v instanceof Number n && (smaller == null || n.doubleValue() > smaller)) smaller = n.doubleValue();
            }
            return new DocumentFacts.Hints(meta.periodEnd, masked4, lim instanceof Number n ? n.doubleValue() : null, smaller);
        } catch (RuntimeException | StackOverflowError e) {
            return null;
        }
    }
}
