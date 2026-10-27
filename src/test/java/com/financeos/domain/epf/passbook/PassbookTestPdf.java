package com.financeos.domain.epf.passbook;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.encryption.AccessPermission;
import org.apache.pdfbox.pdmodel.encryption.StandardProtectionPolicy;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;

import java.io.ByteArrayOutputStream;
import java.util.List;

/** Builds small synthetic PDFs (one text line per entry) for passbook tests. */
public final class PassbookTestPdf {

    private PassbookTestPdf() {
    }

    public static byte[] pdf(List<String> lines, String userPassword) throws Exception {
        try (PDDocument doc = new PDDocument(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            PDType1Font font = new PDType1Font(Standard14Fonts.FontName.HELVETICA);
            PDPage page = null;
            PDPageContentStream cs = null;
            float y = 0;
            for (String line : lines) {
                if (page == null || y < 40) {
                    if (cs != null) cs.close();
                    page = new PDPage(new PDRectangle(900, 800));
                    doc.addPage(page);
                    cs = new PDPageContentStream(doc, page);
                    y = 760;
                }
                cs.beginText();
                cs.setFont(font, 9);
                cs.newLineAtOffset(20, y);
                cs.showText(line);
                cs.endText();
                y -= 14;
            }
            if (cs != null) cs.close();
            if (userPassword != null) {
                StandardProtectionPolicy policy = new StandardProtectionPolicy("owner-pw", userPassword, new AccessPermission());
                policy.setEncryptionKeyLength(128);
                doc.protect(policy);
            }
            doc.save(out);
            return out.toByteArray();
        }
    }

    public static byte[] pdf(List<String> lines) throws Exception {
        return pdf(lines, null);
    }
}
