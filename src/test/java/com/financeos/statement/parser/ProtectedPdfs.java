package com.financeos.statement.parser;

import java.io.ByteArrayOutputStream;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.encryption.AccessPermission;
import org.apache.pdfbox.pdmodel.encryption.StandardProtectionPolicy;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;

/** One-page text PDF locked with a user password, for the password-case tests. */
public final class ProtectedPdfs {

    private ProtectedPdfs() {
    }

    public static byte[] pdf(String userPassword) throws Exception {
        try (PDDocument doc = new PDDocument(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            PDPage page = new PDPage();
            doc.addPage(page);
            try (PDPageContentStream cs = new PDPageContentStream(doc, page)) {
                cs.beginText();
                cs.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 11);
                cs.newLineAtOffset(50, 740);
                cs.showText("Statement of account 01/09/2026 to 30/09/2026");
                cs.endText();
            }
            StandardProtectionPolicy p = new StandardProtectionPolicy("owner-made-up", userPassword, new AccessPermission());
            p.setEncryptionKeyLength(128);
            doc.protect(p);
            doc.save(out);
            return out.toByteArray();
        }
    }
}
