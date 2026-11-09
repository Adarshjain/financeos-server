package com.financeos.statement.parser;

import java.io.ByteArrayOutputStream;
import java.util.List;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** Letter-spaced figures ("1" + ".00") must come out of the extractor as one amount word. */
class LetterSpacedAmountTest {

	private static byte[] pdf(String head, String tail, float gap, String after) throws Exception {
		try (PDDocument doc = new PDDocument()) {
			PDType1Font font = new PDType1Font(Standard14Fonts.FontName.HELVETICA);
			PDPage page = new PDPage(PDRectangle.A4);
			doc.addPage(page);
			try (PDPageContentStream cs = new PDPageContentStream(doc, page)) {
				cs.setFont(font, 9);
				show(cs, "Total", 50, 700);
				float x = 200;
				show(cs, head, x, 700);
				float w = font.getStringWidth(head) / 1000f * 9;
				show(cs, tail, x + w + gap, 700);
				if (after != null) {
					show(cs, after, x + w + gap + font.getStringWidth(tail) / 1000f * 9 + 20, 700);
				}
			}
			ByteArrayOutputStream out = new ByteArrayOutputStream();
			doc.save(out);
			return out.toByteArray();
		}
	}

	private static void show(PDPageContentStream cs, String text, float x, float y) throws Exception {
		cs.beginText();
		cs.newLineAtOffset(x, y);
		cs.showText(text);
		cs.endText();
	}

	private static List<String> words(byte[] pdf) {
		return new PdfWordExtractor().extract(pdf, null).get(0).words().stream().map(Word::text).toList();
	}

	@Test
	void unitDigitAndFractionJoinIntoOneAmount() throws Exception {
		assertEquals(List.of("Total", "1.00"), words(pdf("1", ".00", 1.5f, null)));
	}

	@Test
	void thousandsGroupPieceJoinsToo() throws Exception {
		assertEquals(List.of("Total", "2,430.00"), words(pdf("2", ",430.00", 1.5f, null)));
	}

	@Test
	void pieceThatDoesNotFormAnAmountStaysSeparate() throws Exception {
		assertEquals(List.of("Total", "12", "AB"), words(pdf("12", "AB", 1.5f, null)));
	}

	@Test
	void wideGapIsNotJoined() throws Exception {
		assertEquals(List.of("Total", "1", ".00"), words(pdf("1", ".00", 2.0f, null)));
	}
}
