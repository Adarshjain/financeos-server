package com.financeos.statement.parser;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import java.io.ByteArrayOutputStream;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

/**
 * No-regression baseline for the live statement parser. Off unless -Dbaseline.out=FILE is given. Runs the engine over
 * every parser-test fixture, the account-detector corpora (rendered) and, with -Dbaseline.own=DIR, a folder of real
 * PDFs, and writes ONLY "doc, field, sha256|NULL" lines: never a value, an amount, a name or a text.
 */
@EnabledIfSystemProperty(named = "baseline.out", matches = ".+")
class ParserBaselineHarness {

    private static String sha(String s) {
        try {
            byte[] d = MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (byte b : d) sb.append(String.format("%02x", b));
            return sb.toString().substring(0, 16);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static void put(List<String> out, String doc, String field, Object v) {
        out.add(doc + "\t" + field + "\t" + (v == null ? "NULL" : sha(String.valueOf(v))));
    }

    static void record(List<String> out, String doc, byte[] bytes, String password) {
        ParsedStatement ps;
        try {
            ps = StatementParseEngine.parse(bytes, password, null);
        } catch (Throwable e) {
            out.add(doc + "\tEXC\t" + sha(e.getClass().getName()));
            return;
        }
        StatementMeta m = ps.meta();
        put(out, doc, "meta.bank", m.bank);
        put(out, doc, "meta.accountNumber", m.accountNumber);
        put(out, doc, "meta.ifsc", m.ifsc);
        put(out, doc, "meta.holderName", m.holderName);
        put(out, doc, "meta.periodStart", m.periodStart);
        put(out, doc, "meta.periodEnd", m.periodEnd);
        put(out, doc, "meta.openingBalance", m.openingBalance);
        put(out, doc, "meta.closingBalance", m.closingBalance);
        put(out, doc, "statementType", ps.statementType());
        ParseInfo pi = ps.parseInfo();
        put(out, doc, "info", pi.mode + "|" + pi.rowsChainValidated + "|" + pi.chainValidationPct + "|" + pi.totalCredits + "|" + pi.totalDebits + "|"
                + pi.checksumOk + "|" + pi.crossDebits + "|" + pi.crossCredits + "|" + pi.cardChecks + "|" + pi.verdict + "|" + pi.rowsDropped);
        Derived d = ps.derived();
        put(out, doc, "derived", d.transactionCount + "|" + d.debitCount + "|" + d.creditCount + "|" + d.activeDays + "|" + d.avgDebit + "|" + d.avgDailySpend
                + "|" + d.topMerchants + "|" + row(d.largestDebit) + "|" + row(d.largestCredit));
        for (Map.Entry<String, Object> e : ps.summaryFields().entrySet()) put(out, doc, "summary." + e.getKey(), e.getValue());
        put(out, doc, "summary.keys", ps.summaryFields().keySet());
        put(out, doc, "summaryText", ps.summaryText());
        put(out, doc, "txn.count", ps.transactions().size());
        for (int i = 0; i < ps.transactions().size(); i++) put(out, doc, "txn." + i, row(ps.transactions().get(i)));
    }

    private static String row(RowResult r) {
        return r == null ? null : r.date + "|" + r.description + "|" + r.amount + "|" + r.balance + "|" + r.chainValid + "|" + r.signSource + "|" + r.cardLast4;
    }

    static byte[] render(String text) throws Exception {
        try (PDDocument doc = new PDDocument(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            String[] lines = text.split("\\R", -1);
            int i = 0;
            do {
                PDPage page = new PDPage();
                doc.addPage(page);
                try (PDPageContentStream cs = new PDPageContentStream(doc, page)) {
                    cs.beginText();
                    cs.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 9);
                    cs.newLineAtOffset(30, 780);
                    for (int n = 0; n < 60 && i < lines.length; n++, i++) {
                        cs.showText(lines[i].replaceAll("[^\\x20-\\x7E]", "?"));
                        cs.newLineAtOffset(0, -12);
                    }
                    cs.endText();
                }
            } while (i < lines.length);
            doc.save(out);
            return out.toByteArray();
        }
    }

    @Test
    void writeBaseline() throws Exception {
        List<String> out = new ArrayList<>();
        // (a) every public fixture of the parser tests, and the private builders the smoke tests use
        for (Method m : StatementPdfFixtures.class.getDeclaredMethods()) {
            if (java.lang.reflect.Modifier.isPublic(m.getModifiers()) && m.getParameterCount() == 0 && m.getReturnType() == byte[].class) {
                record(out, "fx:" + m.getName(), (byte[]) m.invoke(null), null);
            }
        }
        Method pdfSmoke = PdfStatementSmokeTest.class.getDeclaredMethod("generateSyntheticPdf");
        pdfSmoke.setAccessible(true);
        record(out, "smoke:pdf", (byte[]) pdfSmoke.invoke(new PdfStatementSmokeTest()), null);
        Method xl = ExcelStatementSmokeTest.class.getDeclaredMethod("generateSyntheticExcel");
        xl.setAccessible(true);
        record(out, "smoke:xlsx", (byte[]) xl.invoke(new ExcelStatementSmokeTest()), null);
        Method pre = PdfPreambleTest.class.getDeclaredMethod("statementPdf", String.class);
        pre.setAccessible(true);
        record(out, "preamble:plain", (byte[]) pre.invoke(null, (Object) null), null);
        record(out, "preamble:secret", (byte[]) pre.invoke(null, "secret"), "secret");
        // account-detector corpora, rendered as PDFs
        ObjectMapper om = new ObjectMapper();
        Path corp = Path.of("src/test/resources/accountdetect");
        if (Files.isDirectory(corp)) {
            List<Path> files;
            try (Stream<Path> s = Files.walk(corp)) {
                files = s.filter(p -> p.toString().endsWith(".json")).sorted().toList();
            }
            for (Path f : files) {
                JsonNode r = om.readTree(f.toFile());
                int n = 0;
                for (JsonNode mail : r.path("mails")) {
                    for (JsonNode at : mail.path("attachments")) {
                        if (!at.hasNonNull("pdfText") || at.path("protected").asBoolean(false)) continue;
                        record(out, "cp:" + corp.relativize(f) + "#" + (n++), render(at.path("pdfText").asText()), null);
                    }
                }
            }
        }
        // (b) the owner's unlocked PDFs, by file number only
        String own = System.getProperty("baseline.own");
        if (own != null && Files.isDirectory(Path.of(own))) {
            List<Path> pdfs;
            try (Stream<Path> s = Files.list(Path.of(own))) {
                pdfs = s.filter(p -> p.getFileName().toString().matches("\\d+\\.pdf")).sorted().toList();
            }
            for (Path p : pdfs) record(out, "own:" + p.getFileName().toString().replace(".pdf", ""), Files.readAllBytes(p), null);
        }
        Path dest = Path.of(System.getProperty("baseline.out"));
        Files.createDirectories(dest.getParent());
        Files.write(dest, out);
        System.out.println("BASELINE lines=" + out.size());
    }
}
