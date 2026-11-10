package com.financeos.statement.parser;

import com.financeos.statement.StatementParserSettings;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.IOException;

import static org.junit.jupiter.api.Assertions.*;

class ParserLimitsTest {

    @AfterEach
    void restoreDefault() {
        ParserLimits.setMaxPdfPages(ParserLimits.DEFAULT_MAX_PDF_PAGES);
    }

    private static byte[] emptyPdf(int pages) throws IOException {
        try (PDDocument doc = new PDDocument(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            for (int i = 0; i < pages; i++) {
                doc.addPage(new PDPage());
            }
            doc.save(out);
            return out.toByteArray();
        }
    }

    @Test
    void defaultIs600() {
        assertEquals(600, ParserLimits.maxPdfPages());
    }

    @Test
    void loweredCapRefusesPdf() throws IOException {
        ParserLimits.setMaxPdfPages(3);
        StatementParseException e = assertThrows(StatementParseException.class,
                () -> new PdfWordExtractor().extract(emptyPdf(4), null));
        assertEquals("ERROR: too many pages (4)", e.getMessage());
    }

    @Test
    void raisedCapAllowsPdfOverDefault() throws IOException {
        ParserLimits.setMaxPdfPages(700);
        StatementParseException e = assertThrows(StatementParseException.class,
                () -> new PdfWordExtractor().extract(emptyPdf(601), null));
        assertFalse(e.getMessage().toLowerCase().contains("too many pages"), e.getMessage());
    }

    @Test
    void invalidValuesRejected() {
        assertThrows(IllegalArgumentException.class, () -> ParserLimits.setMaxPdfPages(0));
        assertThrows(IllegalArgumentException.class, () -> ParserLimits.setMaxPdfPages(-5));
        assertEquals(600, ParserLimits.maxPdfPages());
    }

    @Test
    void settingsComponentAppliesProperty() {
        new StatementParserSettings(10000);
        assertEquals(10000, ParserLimits.maxPdfPages());
    }
}
