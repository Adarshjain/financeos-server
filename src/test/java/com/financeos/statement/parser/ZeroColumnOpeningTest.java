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

/**
 * A bank table that prints "0.00" in the unused Withdrawals/Deposits column of every row and
 * no opening balance before the rows: the first row must keep its real amount and the opening
 * balance is derived from it, not set to the first row's own balance. Made-up data only.
 */
class ZeroColumnOpeningTest {

	private static void right(PDPageContentStream cs, PDType1Font f, String s, float x, float y) throws Exception {
		cs.beginText();
		cs.newLineAtOffset(x - f.getStringWidth(s) / 1000 * 9, y);
		cs.showText(s);
		cs.endText();
	}

	private static byte[] pdf(String[][] rows) throws Exception {
		try (PDDocument doc = new PDDocument()) {
			PDType1Font f = new PDType1Font(Standard14Fonts.FontName.HELVETICA);
			PDPage page = new PDPage(PDRectangle.A4);
			doc.addPage(page);
			try (PDPageContentStream cs = new PDPageContentStream(doc, page)) {
				cs.setFont(f, 9);
				float y = 780;
				for (String h : new String[] {"Test Bank", "Statement From: 01/04/2025 To 30/04/2025"}) {
					cs.beginText();
					cs.newLineAtOffset(40, y);
					cs.showText(h);
					cs.endText();
					y -= 15;
				}
				y -= 15;
				cs.beginText();
				cs.newLineAtOffset(40, y);
				cs.showText("Txn Date");
				cs.newLineAtOffset(80, 0);
				cs.showText("Narration");
				cs.endText();
				right(cs, f, "Withdrawals", 380, y);
				right(cs, f, "Deposits", 460, y);
				right(cs, f, "Closing Balance", 550, y);
				y -= 15;
				for (String[] r : rows) {
					cs.beginText();
					cs.newLineAtOffset(40, y);
					cs.showText(r[0]);
					cs.newLineAtOffset(80, 0);
					cs.showText(r[1]);
					cs.endText();
					right(cs, f, r[2], 380, y);
					right(cs, f, r[3], 460, y);
					right(cs, f, r[4], 550, y);
					y -= 15;
				}
			}
			ByteArrayOutputStream out = new ByteArrayOutputStream();
			doc.save(out);
			return out.toByteArray();
		}
	}

	@Test
	void firstRowKeepsItsAmountWhenTheUnusedColumnPrintsZero() throws Exception {
		ParsedStatement ps = StatementParseEngine.parse(pdf(new String[][] {
				{"01/04/2025", "NEFT CREDIT ONE", "0.00", "1,000.00", "21,000.00"},
				{"04/04/2025", "CARD SHOP", "500.00", "0.00", "20,500.00"},
				{"07/04/2025", "NEFT CREDIT TWO", "0.00", "200.00", "20,700.00"},
				{"08/04/2025", "NEFT CREDIT THREE", "0.00", "50.00", "20,750.00"},
				{"09/04/2025", "CARD SHOP TWO", "100.00", "0.00", "20,650.00"}}), null, null);
		List<RowResult> t = ps.transactions();
		assertEquals(5, t.size());
		assertEquals(1000.0, t.get(0).amount, 0.001);
		assertEquals(-500.0, t.get(1).amount, 0.001);
		assertEquals(20000.0, ps.meta().openingBalance, 0.001);
		assertTrue(t.stream().allMatch(r -> r.amount != 0.0));
	}
}
