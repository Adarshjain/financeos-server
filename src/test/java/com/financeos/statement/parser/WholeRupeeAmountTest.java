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

/** Glyph-led whole-rupee figures are amounts only in whole-rupee documents or figure-only strips. */
class WholeRupeeAmountTest {

	private static Line line(double top, String... texts) {
		java.util.ArrayList<Word> ws = new java.util.ArrayList<>();
		double x = 10;
		for (String t : texts) {
			ws.add(new Word(t, x, x + 5 * t.length(), top, 1));
			x += 5 * t.length() + 4;
		}
		return new Line(ws);
	}

	private static boolean isAmount(List<Line> lines, int line, int word) {
		return Tokens.parseAmount(Tokens.norm(lines.get(line).words().get(word).text())) != null;
	}

	@Test
	void parseAmountStaysStrictForWholeFigures() {
		assertNull(Tokens.parseAmount("₹5,60,000"));
		assertNull(Tokens.parseAmount("Rs.2,000"));
		assertNull(Tokens.parseAmount("INR100"));
		assertNotNull(Tokens.parseAmount("₹5,60,000.00"));
	}

	@Test
	void creditLimitBesideDecimalRowsIsNotAnAmount() {
		List<Line> lines = List.of(
				line(10, "Credit", "Limit", "₹5,60,000", "Available", "Credit", "Limit", "₹4,10,250"),
				line(30, "21/03/2025", "AMAZON", "PAY", "1,234.50"));
		List<Line> out = Tokens.wholeRupeeAsAmounts(lines, false);
		assertFalse(isAmount(out, 0, 2));
		assertFalse(isAmount(out, 0, 6));
		assertEquals("₹5,60,000", out.get(0).words().get(2).text());
		assertTrue(isAmount(out, 1, 3));
	}

	@Test
	void figureOnlyStripInDecimalDocumentIsAmounts() {
		List<Line> lines = List.of(
				line(10, "₹0", "₹1,020", "₹1,000", "₹0", "₹20"),
				line(30, "13", "Feb", "SWIGGY", "500.00", "500.00"));
		List<Line> out = Tokens.wholeRupeeAsAmounts(lines, false);
		for (int i = 0; i < 5; i++) {
			assertTrue(isAmount(out, 0, i));
		}
		assertEquals(1020.0, Tokens.parseAmount(out.get(0).words().get(1).text()).value());
	}

	@Test
	void wholeRupeeDocumentReadsGlyphFiguresInRows() {
		List<Line> lines = List.of(
				line(10, "S39127543", "UPI", "IN/abc", "₹100", "₹100"),
				line(30, "S39127544", "UPI", "OUT/xyz", "₹40Dr", "₹60"));
		List<Line> out = Tokens.wholeRupeeAsAmounts(lines, false);
		assertTrue(isAmount(out, 0, 3));
		assertTrue(isAmount(out, 1, 3));
		assertEquals(-1, Tokens.parseAmount(out.get(1).words().get(3).text()).sign());
		assertEquals("S39127543", out.get(0).words().get(0).text());
	}

	@Test
	void forcedLayoutConvertsEvenWhenPaiseExist() {
		List<Line> lines = List.of(line(10, "Rs.2,000", "x", "12.50"));
		assertTrue(isAmount(Tokens.wholeRupeeAsAmounts(lines, true), 0, 0));
		assertFalse(isAmount(Tokens.wholeRupeeAsAmounts(lines, false), 0, 0));
	}

	private static void show(PDPageContentStream cs, String text, float x, float y) throws Exception {
		cs.beginText();
		cs.newLineAtOffset(x, y);
		cs.showText(text);
		cs.endText();
	}

	/** Savings-style statement whose every figure is whole rupees ("Rs.100"). */
	private static byte[] wholeRupeeSavings() throws Exception {
		try (PDDocument doc = new PDDocument()) {
			PDType1Font font = new PDType1Font(Standard14Fonts.FontName.HELVETICA);
			PDPage page = new PDPage(PDRectangle.A4);
			doc.addPage(page);
			try (PDPageContentStream cs = new PDPageContentStream(doc, page)) {
				cs.setFont(font, 9);
				show(cs, "Savings Account Statement", 50, 780);
				show(cs, "Account No 77012345678560", 50, 765);
				show(cs, "Statement Period 01 Apr 2021 to 31 Mar 2022", 50, 750);
				show(cs, "Date", 50, 720);
				show(cs, "Description", 120, 720);
				show(cs, "Amount", 400, 720);
				show(cs, "Balance", 480, 720);
				String[][] rows = {{"20 Apr 2021", "UPI IN/ref1", "Rs.100", "Rs.100"},
						{"21 Apr 2021", "UPI IN/ref2", "Rs.250", "Rs.350"},
						{"22 Apr 2021", "UPI OUT/ref3", "Rs.350", "Rs.0"}};
				float y = 700;
				for (String[] r : rows) {
					show(cs, r[0], 50, y);
					show(cs, r[1], 120, y);
					show(cs, r[2], 400, y);
					show(cs, r[3], 480, y);
					y -= 15;
				}
			}
			ByteArrayOutputStream out = new ByteArrayOutputStream();
			doc.save(out);
			return out.toByteArray();
		}
	}

	@Test
	void wholeRupeeSavingsDocumentStillParsesRows() throws Exception {
		ParsedStatement ps = StatementParseEngine.parse(wholeRupeeSavings(), null, s -> {
		});
		assertEquals(3, ps.transactions().size());
		assertEquals(100.0, ps.transactions().get(0).amount);
		assertEquals(-350.0, ps.transactions().get(2).amount);
	}
}
