package com.financeos.gmail.reconcile;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayOutputStream;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.junit.jupiter.api.Test;

/** A card statement whose due date cell says "Nil" hands the draft that field as stated none. */
class StatementParserStatedNoneTest {

    private final StatementParser parser = new StatementParser();

    @Test
    void nilDueDateReachesTheDraftAsStatedNone() throws Exception {
        StatementExtractionResult result = parser.parse(card("Nil"), null);
        assertThat(result.success()).isTrue();
        assertThat(result.draft().cardFields()).doesNotContainKey("payment_due_date");
        assertThat(result.draft().statedNone()).containsExactly("payment_due_date");
    }

    @Test
    void printedDueDateIsNotStatedNone() throws Exception {
        StatementExtractionResult result = parser.parse(card("06/11/2025"), null);
        assertThat(result.success()).isTrue();
        assertThat(result.draft().cardFields()).containsKey("payment_due_date");
        assertThat(result.draft().statedNone()).isEmpty();
    }

    // HDFC-card-like first page: summary labels over their values, then the transaction table.
    private static byte[] card(String dueCell) throws Exception {
        Object[][] lines = {
                {760f, 40f, "Credit Card Statement"},
                {745f, 40f, "Credit Card No. 652926XXXXXX7358"},
                {730f, 40f, "Statement Date 17/10/2025"},
                {700f, 40f, "TOTAL AMOUNT DUE", 300f, "MINIMUM DUE", 420f, "DUE DATE"},
                {688f, 40f, "-38.74", 300f, "0.00", 425f, dueCell},
                {650f, 40f, "Date", 110f, "Transaction Description", 420f, "Amount"},
                {636f, 40f, "01/10/2025", 110f, "AMAZON PAY INDIA", 420f, "200.70"},
                {622f, 40f, "05/10/2025", 110f, "REFUND MERCHANT", 420f, "239.44 Cr"},
                {608f, 40f, "09/10/2025", 110f, "SWIGGY BANGALORE", 420f, "0.00"},
        };
        try (PDDocument doc = new PDDocument()) {
            PDType1Font font = new PDType1Font(Standard14Fonts.FontName.HELVETICA);
            PDPage page = new PDPage(PDRectangle.A4);
            doc.addPage(page);
            try (PDPageContentStream cs = new PDPageContentStream(doc, page)) {
                for (Object[] ln : lines) {
                    for (int i = 1; i < ln.length; i += 2) {
                        cs.beginText();
                        cs.setFont(font, 9);
                        cs.newLineAtOffset((Float) ln[i], (Float) ln[0]);
                        cs.showText((String) ln[i + 1]);
                        cs.endText();
                    }
                }
            }
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            doc.save(out);
            return out.toByteArray();
        }
    }
}
