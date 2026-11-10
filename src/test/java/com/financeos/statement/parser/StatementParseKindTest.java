package com.financeos.statement.parser;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.financeos.statement.parser.StatementParseException.Kind;
import java.io.ByteArrayOutputStream;
import java.io.ByteArrayInputStream;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.apache.poi.openxml4j.opc.OPCPackage;
import org.apache.poi.poifs.crypt.EncryptionInfo;
import org.apache.poi.poifs.crypt.EncryptionMode;
import org.apache.poi.poifs.crypt.Encryptor;
import org.apache.poi.poifs.filesystem.POIFSFileSystem;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;

/** Every way a file fails to parse carries its own Kind, from the real trigger, with the message text unchanged. */
class StatementParseKindTest {

    private static StatementParseException failure(byte[] bytes, String password) {
        Throwable t = catchThrowable(() -> StatementParseEngine.parse(bytes, password, null));
        if (!(t instanceof StatementParseException)) {
            throw new AssertionError("not a parse exception", t);
        }
        return (StatementParseException) t;
    }

    private static Throwable catchThrowable(Runnable r) {
        try {
            r.run();
        } catch (Throwable t) {
            return t;
        }
        throw new AssertionError("expected the parse to fail");
    }

    /** One-page PDF; each entry is {x, yFromTop, text}. */
    private static byte[] pdf(Object[]... items) throws Exception {
        try (PDDocument doc = new PDDocument(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            PDPage page = new PDPage(PDRectangle.A4);
            doc.addPage(page);
            try (PDPageContentStream cs = new PDPageContentStream(doc, page)) {
                cs.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 10);
                for (Object[] it : items) {
                    cs.beginText();
                    cs.newLineAtOffset(((Number) it[0]).floatValue(), 842 - ((Number) it[1]).floatValue());
                    cs.showText((String) it[2]);
                    cs.endText();
                }
            }
            doc.save(out);
            return out.toByteArray();
        }
    }

    private static Object[] at(float x, float y, String text) {
        return new Object[]{x, y, text};
    }

    private static byte[] blankPdf(int pages) throws Exception {
        try (PDDocument doc = new PDDocument(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            for (int i = 0; i < pages; i++) {
                doc.addPage(new PDPage());
            }
            doc.save(out);
            return out.toByteArray();
        }
    }

    @Test
    void aLockedPdfWithNoPasswordIsLocked() throws Exception {
        StatementParseException e = failure(ProtectedPdfs.pdf("secret1"), null);
        assertThat(e.kind()).isEqualTo(Kind.LOCKED);
        assertThat(e.getMessage()).isEqualTo("ERROR: PDF is password-protected and the password is missing or wrong.");
        assertThat(e.isPasswordProblem()).isTrue();
    }

    @Test
    void aLockedPdfWithAWrongPasswordIsLocked() throws Exception {
        assertThat(failure(ProtectedPdfs.pdf("secret1"), "not-it").kind()).isEqualTo(Kind.LOCKED);
    }

    @Test
    void aLockedExcelIsLocked() throws Exception {
        byte[] encrypted;
        try (XSSFWorkbook wb = new XSSFWorkbook(); ByteArrayOutputStream plain = new ByteArrayOutputStream()) {
            wb.createSheet().createRow(0).createCell(0).setCellValue("x");
            wb.write(plain);
            POIFSFileSystem fs = new POIFSFileSystem();
            EncryptionInfo info = new EncryptionInfo(EncryptionMode.agile);
            Encryptor enc = info.getEncryptor();
            enc.confirmPassword("xl-secret");
            try (OPCPackage opc = OPCPackage.open(new ByteArrayInputStream(plain.toByteArray()));
                 java.io.OutputStream os = enc.getDataStream(fs)) {
                opc.save(os);
            }
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            fs.writeFilesystem(out);
            encrypted = out.toByteArray();
        }
        StatementParseException e = failure(encrypted, null);
        assertThat(e.kind()).isEqualTo(Kind.LOCKED);
        assertThat(e.getMessage()).isEqualTo("ERROR: Excel is password-protected and the password is missing or wrong.");
    }

    private static byte[] lockedXls(String password) throws Exception {
        org.apache.poi.hssf.record.crypto.Biff8EncryptionKey.setCurrentUserPassword(password);
        try (org.apache.poi.hssf.usermodel.HSSFWorkbook wb = new org.apache.poi.hssf.usermodel.HSSFWorkbook();
             ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            wb.createSheet().createRow(0).createCell(0).setCellValue("x");
            wb.write(out);
            return out.toByteArray();
        } finally {
            org.apache.poi.hssf.record.crypto.Biff8EncryptionKey.setCurrentUserPassword(null);
        }
    }

    @Test
    void aPasswordProtectedLegacyXlsIsLockedWithAndWithoutAWrongPassword() throws Exception {
        byte[] xls = lockedXls("xls-secret");

        for (String typed : new String[]{null, "wrong-one"}) {
            StatementParseException e = failure(xls, typed);
            assertThat(e.kind()).as("typed: %s", typed).isEqualTo(Kind.LOCKED);
            assertThat(e.getMessage()).isEqualTo("ERROR: Excel is password-protected and the password is missing or wrong.");
            assertThat(e.isPasswordProblem()).isTrue();
        }
    }

    @Test
    void bytesThatAreNeitherPdfNorExcelAreUnsupported() {
        StatementParseException e = failure("hello world, this is plain text".getBytes(), null);
        assertThat(e.kind()).isEqualTo(Kind.UNSUPPORTED_TYPE);
        assertThat(e.getMessage()).isEqualTo("ERROR: unsupported file type (expected PDF or Excel)");
    }

    @Test
    void emptyNullAndTinyFilesAreEmpty() {
        for (byte[] bytes : new byte[][]{new byte[0], null, new byte[7]}) {
            StatementParseException e = failure(bytes, null);
            assertThat(e.kind()).isEqualTo(Kind.EMPTY_FILE);
            assertThat(e.getMessage()).isEqualTo("ERROR: file too small or empty");
        }
    }

    @Test
    void morePagesThanTheCapIsTooManyPages() throws Exception {
        StatementParseException e = failure(blankPdf(601), null);
        assertThat(e.kind()).isEqualTo(Kind.TOO_MANY_PAGES);
        assertThat(e.getMessage()).isEqualTo("ERROR: too many pages (601)");
    }

    @Test
    void aPdfWithoutTextIsNoTextLayer() throws Exception {
        StatementParseException e = failure(blankPdf(1), null);
        assertThat(e.kind()).isEqualTo(Kind.NO_TEXT_LAYER);
        assertThat(e.getMessage()).isEqualTo("ERROR: no text layer found (scanned/image PDF?).");
    }

    @Test
    void aCorruptPdfIsUnreadable() {
        StatementParseException e = failure(("%PDF-1.4\n" + "garbage, not a pdf at all ".repeat(20)).getBytes(), null);
        assertThat(e.kind()).isEqualTo(Kind.UNREADABLE);
        assertThat(e.getMessage()).isEqualTo("ERROR: failed to read PDF.");
    }

    @Test
    void aCorruptOldExcelIsUnreadable() {
        byte[] ole = {(byte) 0xD0, (byte) 0xCF, 0x11, (byte) 0xE0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12};
        StatementParseException e = failure(ole, null);
        assertThat(e.kind()).isEqualTo(Kind.UNREADABLE);
        assertThat(e.getMessage()).isEqualTo("ERROR: failed to read Excel file.");
    }

    @Test
    void aCorruptNewExcelIsUnreadableNotAnUntypedPoiException() {
        byte[] zipHeaderOnly = {'P', 'K', 3, 4, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12};
        StatementParseException e = failure(zipHeaderOnly, null);
        assertThat(e.kind()).isEqualTo(Kind.UNREADABLE);
        assertThat(e.getMessage()).isEqualTo("ERROR: failed to read Excel file.");
        assertThat(e.getCause()).isNotNull();
    }

    @Test
    void textWithoutAnyDatedRowIsNoTable() throws Exception {
        StatementParseException e = failure(pdf(at(40, 60, "Hello there"), at(40, 80, "nothing dated in here")), null);
        assertThat(e.kind()).isEqualTo(Kind.NO_TABLE);
        assertThat(e.getMessage()).startsWith("ERROR: no date-anchored rows found");
    }

    @Test
    void datedRowsNoDateFormatCanReadAreDateFormat() throws Exception {
        StatementParseException e = failure(pdf(at(40, 60, "Date Description Amount"),
                at(40, 80, "99/99/2026 FOO 100.00"), at(40, 94, "88/77/2026 BAR 200.00")), null);
        assertThat(e.kind()).isEqualTo(Kind.DATE_FORMAT);
        assertThat(e.getMessage()).isEqualTo("ERROR: no transaction rows conform to the inferred date format.");
    }

    // ---- explicitly empty statements still come back as a statement (decided by the kind, not the wording)

    private static byte[] emptyMonth(boolean withSentence) throws Exception {
        java.util.List<Object[]> items = new java.util.ArrayList<>(java.util.List.of(
                at(40, 60, "Account Statement 1 November 2021 to 30 November 2021"),
                at(300, 100, "SAVINGS A/C NO"), at(440, 100, "IFSC"),
                at(300, 112, "55550000111122"), at(440, 112, "TEST0000001")));
        if (withSentence) {
            items.add(at(120, 200, "It looks like you have not made"));
            items.add(at(120, 212, "any transactions on Fi between"));
            items.add(at(120, 224, "1 November 2021 to 30 November"));
            items.add(at(120, 236, "2021"));
        }
        return pdf(items.toArray(new Object[0][]));
    }

    @Test
    void anExplicitlyEmptyStatementIsStillAStatementWithNoRows() throws Exception {
        ParsedStatement ps = StatementParseEngine.parse(emptyMonth(true), null, null);
        assertThat(ps.transactions()).isEmpty();
        assertThat(ps.meta().accountNumber).isEqualTo("55550000111122");
    }

    @Test
    void theSameStatementWithoutTheNoTransactionsSentenceStillFailsAsNoTable() throws Exception {
        assertThat(failure(emptyMonth(false), null).kind()).isEqualTo(Kind.NO_TABLE);
    }

    @Test
    void aDifferentKindOfFailureIsNeverTurnedIntoAnEmptyStatement() throws Exception {
        // The no-transactions sentence only rescues a missing table, never a failed date format.
        assertThatThrownBy(() -> StatementParseEngine.parse(pdf(at(40, 60, "Account Statement 1 November 2021 to 30 November 2021"),
                at(40, 80, "99/99/2026 FOO 100.00"), at(40, 94, "88/77/2026 BAR 200.00"),
                at(120, 200, "It looks like you have not made"), at(120, 212, "any transactions on Fi between")), null, null))
                .isInstanceOfSatisfying(StatementParseException.class, e -> assertThat(e.kind()).isEqualTo(Kind.DATE_FORMAT));
    }

    // ---- constructors and the password check

    @Test
    void theOldConstructorsDefaultToOther() {
        assertThat(new StatementParseException("x").kind()).isEqualTo(Kind.OTHER);
        assertThat(new StatementParseException("x", new RuntimeException()).kind()).isEqualTo(Kind.OTHER);
        assertThat(new StatementParseException(null, "x").kind()).isEqualTo(Kind.OTHER);
    }

    @Test
    void aTypedConstructorKeepsItsKindMessageAndCause() {
        RuntimeException cause = new RuntimeException("c");
        StatementParseException e = new StatementParseException(Kind.NO_TABLE, "m", cause);
        assertThat(e.kind()).isEqualTo(Kind.NO_TABLE);
        assertThat(e.getMessage()).isEqualTo("m");
        assertThat(e.getCause()).isSameAs(cause);
        assertThat(new StatementParseException(Kind.EMPTY_FILE, "m").kind()).isEqualTo(Kind.EMPTY_FILE);
    }

    @Test
    void passwordProblemFollowsTheKindAndStillRecognisesAnUntypedPasswordMessage() {
        assertThat(new StatementParseException(Kind.LOCKED, "anything").isPasswordProblem()).isTrue();
        assertThat(new StatementParseException(Kind.UNREADABLE, "ERROR: password-protected").isPasswordProblem()).isFalse();
        assertThat(new StatementParseException("ERROR: PDF is password-protected and the password is missing or wrong.").isPasswordProblem()).isTrue();
        assertThat(new StatementParseException("ERROR: failed to read PDF.").isPasswordProblem()).isFalse();
        assertThat(StatementParseException.isPasswordMessage("password-protected")).isTrue();
        assertThat(StatementParseException.isPasswordMessage(null)).isFalse();
    }
}
