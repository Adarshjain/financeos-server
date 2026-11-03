package com.financeos.gmail.accountdetect;

import com.financeos.gmail.accountdetect.StatementPdfReader.Result;
import com.financeos.gmail.accountdetect.StatementPdfReader.Status;
import com.financeos.gmail.accountdetect.Vocab.Kind;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.encryption.AccessPermission;
import org.apache.pdfbox.pdmodel.encryption.StandardProtectionPolicy;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.time.YearMonth;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** Statement content from made-up PDFs built in the test; encrypted PDFs are skipped, never opened with a password. */
class StatementPdfReaderTest {

    private static final List<String> LINES = List.of(
            "HDFC Bank Regalia Gold Credit Card Statement",
            "Card No: XXXX XXXX XXXX 4381",
            "Statement Date: 15/09/2026",
            "Credit Limit: Rs. 4,50,000.00",
            "Total Amount Due: Rs. 12,345.00   Minimum Amount Due: Rs. 620.00",
            "Add-on Card: XXXX XXXX XXXX 8735",
            "Transactions",
            "02/09/2026  SAMPLE STORE  1,200.00");

    private static byte[] pdf(List<String> lines, String userPassword, String ownerPassword) throws Exception {
        try (PDDocument doc = new PDDocument(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            PDPage page = new PDPage();
            doc.addPage(page);
            try (PDPageContentStream cs = new PDPageContentStream(doc, page)) {
                cs.beginText();
                cs.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 11);
                cs.newLineAtOffset(50, 740);
                for (String l : lines) {
                    cs.showText(l);
                    cs.newLineAtOffset(0, -18);
                }
                cs.endText();
            }
            if (ownerPassword != null) {
                StandardProtectionPolicy p = new StandardProtectionPolicy(ownerPassword, userPassword, new AccessPermission());
                p.setEncryptionKeyLength(128);
                doc.protect(p);
            }
            doc.save(out);
            return out.toByteArray();
        }
    }

    @Test
    void unprotectedStatementGivesDerivedFieldsOnly() throws Exception {
        Result r = StatementPdfReader.read(pdf(LINES, null, null), "PDF_MAIL", "hdfc", YearMonth.of(2026, 10));
        assertThat(r.status()).isEqualTo(Status.OPENED);
        StatementContent c = r.content();
        assertThat(c.bank()).isEqualTo("hdfc");
        assertThat(c.last4()).isEqualTo("4381");
        assertThat(c.kind()).isEqualTo(Kind.CREDIT_CARD);
        assertThat(c.variant()).isEqualTo("Regalia Gold");
        assertThat(c.period()).isEqualTo(YearMonth.of(2026, 9));
        assertThat(c.limitBucket()).isEqualTo("3-5L");
        assertThat(c.addOnLast4()).containsExactly("8735");
        assertThat(c.mentionsClosure()).isFalse();
    }

    @Test
    void folderStatementMapsItsBankFromTheHeader() throws Exception {
        Result r = StatementPdfReader.read(pdf(LINES, null, null), "PDF_FOLDER", null, null);
        assertThat(r.status()).isEqualTo(Status.OPENED);
        assertThat(r.content().bank()).isEqualTo("hdfc");
    }

    @Test
    void closureLineIsFlagged() throws Exception {
        List<String> l = new java.util.ArrayList<>(LINES);
        l.add("Your Credit Card account has been closed.");
        assertThat(StatementPdfReader.read(pdf(l, null, null), "PDF_MAIL", "hdfc", null).content().mentionsClosure()).isTrue();
    }

    @Test
    void passwordProtectedStatementIsSkipped() throws Exception {
        Result r = StatementPdfReader.read(pdf(LINES, "secret", "owner"), "PDF_MAIL", "hdfc", null);
        assertThat(r.status()).isEqualTo(Status.PROTECTED);
        assertThat(r.content()).isNull();
    }

    @Test
    void ownerOnlyEncryptionIsAlsoSkipped() throws Exception {
        Result r = StatementPdfReader.read(pdf(LINES, "", "owner"), "PDF_MAIL", "hdfc", null);
        assertThat(r.status()).isEqualTo(Status.PROTECTED);
    }

    @Test
    void garbageIsFailedNotProtected() {
        assertThat(StatementPdfReader.read(new byte[]{1, 2, 3}, "PDF_MAIL", "hdfc", null).status()).isEqualTo(Status.FAILED);
    }
}
