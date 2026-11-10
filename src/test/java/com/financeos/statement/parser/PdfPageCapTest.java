package com.financeos.statement.parser;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.IOException;

import static org.junit.jupiter.api.Assertions.*;

class PdfPageCapTest {

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
    void moreThanTheCapFailsBeforeExtraction() throws IOException {
        StatementParseException e = assertThrows(StatementParseException.class,
                () -> new PdfWordExtractor().extract(emptyPdf(601), null));
        assertEquals("ERROR: too many pages (601)", e.getMessage());
    }

    @Test
    void exactlyTheCapIsNotRejectedForPageCount() throws IOException {
        StatementParseException e = assertThrows(StatementParseException.class,
                () -> new PdfWordExtractor().extract(emptyPdf(600), null));
        assertFalse(e.getMessage().toLowerCase().contains("too many pages"), e.getMessage());
        assertTrue(e.getMessage().toLowerCase().contains("no text layer"), e.getMessage());
    }
}
