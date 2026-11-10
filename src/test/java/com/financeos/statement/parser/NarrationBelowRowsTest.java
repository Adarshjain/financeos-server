package com.financeos.statement.parser;

import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
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
 * Which row a wrapped narration line belongs to. HDFC savings prints each row's narration
 * (ending "Value Dt dd/mm/yyyy Ref N") below its dated row at a 9pt pitch, with a wider gap
 * before the next row, so a block's last line can sit nearer the next row than its own.
 */
class NarrationBelowRowsTest {

	// HDFC savings columns: date, narration, right edges of withdrawals / deposits / balance
	private static final float DATE = 52;
	private static final float NARR = 111;
	private static final float WDL = 330;
	private static final float DEP = 420;
	private static final float BAL = 530;
	private static final float PITCH = 9;
	private static final float BLOCK_GAP = 12.3f;

	@Test
	void narrationPrintedBelowEachRowStaysWithThatRow() throws Exception {
		Pdf p = new Pdf();
		p.y = 700;
		p.line(l(300, "Statement of account"));
		p.gap(20);
		p.line(l(DATE, "Txn Date"), l(NARR, "Narration"), l(WDL - 50, "Withdrawals"), l(DEP - 40, "Deposits"),
				l(BAL - 60, "Closing Balance"));
		p.gap(BLOCK_GAP);
		p.line(l(DATE, "02/02/2025"), l(NARR, "POS 416021XXXXXX3003 CCBBPSNO"), r(WDL, "1,000.00"), r(DEP, "0.00"),
				r(BAL, "9,000.00"));
		p.gap(PITCH);
		p.line(l(NARR, "Value Dt 02/02/2025 Ref 503311969906"));
		p.gap(BLOCK_GAP);
		p.line(l(DATE, "09/02/2025"), l(NARR, "UPI-RAJADURAI"), r(WDL, "300.00"), r(DEP, "0.00"), r(BAL, "8,700.00"));
		p.gap(PITCH);
		p.line(l(NARR, "DESINGU-wwwduraivishnu@"));
		p.gap(PITCH);
		p.line(l(NARR, "oksbi-SBIN0000823-504081518454-UPI"));
		p.gap(PITCH);
		p.line(l(NARR, "Value Dt 09/02/2025 Ref 504081518454"));
		p.gap(BLOCK_GAP);
		p.line(l(DATE, "11/02/2025"), l(NARR, "UPI-Mrs VINODHINI M-vinojaswa2013-2@"), r(WDL, "300.00"), r(DEP, "0.00"),
				r(BAL, "8,400.00"));
		p.gap(PITCH);
		p.line(l(NARR, "okhdfcbank-MAHB0001984-504284727859-"));
		p.gap(PITCH);
		p.line(l(NARR, "UPI Value Dt 11/02/2025 Ref 504284727859"));
		p.gap(BLOCK_GAP);
		p.line(l(DATE, "11/02/2025"), l(NARR, "UPI-ABINESH"), r(WDL, "500.00"), r(DEP, "0.00"), r(BAL, "7,900.00"));
		p.gap(PITCH);
		p.line(l(NARR, "VELMURUGAN-abineshap649-2@"));
		p.gap(PITCH);
		p.line(l(NARR, "okicici-BARB0THICHE-504288070613-UPI"));
		p.gap(PITCH);
		p.line(l(NARR, "Value Dt 11/02/2025 Ref 504288070613"));
		p.gap(BLOCK_GAP);
		p.line(l(DATE, "17/02/2025"), l(NARR, "UPI-ADARSH JAIN A-adarshjain9628@"), r(WDL, "0.00"), r(DEP, "100.00"),
				r(BAL, "8,000.00"));
		p.gap(PITCH);
		p.line(l(NARR, "okicici-ESFB0001001-504824581996-Self"));
		p.gap(PITCH);
		p.line(l(NARR, "transfer Value Dt 17/02/2025 Ref"));
		p.gap(BLOCK_GAP);
		p.line(l(DATE, "18/02/2025"), l(NARR, "NEFT Cr-YESB0000001-ZERODHA"), r(WDL, "0.00"), r(DEP, "42.32"),
				r(BAL, "8,042.32"));
		p.gap(PITCH);
		p.line(l(NARR, "BROKING LTD-DSCNB"));
		p.gap(PITCH);
		p.line(l(NARR, "Value Dt 18/02/2025 Ref NEFTREF0001"));
		p.gap(BLOCK_GAP);
		// (lines under the table's last row are outside the table, so it ends on a one-line row)
		p.line(l(DATE, "19/02/2025"), l(NARR, "ACH D- LAST ROW"), r(WDL, "42.32"), r(DEP, "0.00"), r(BAL, "8,000.00"));

		ParsedStatement parsed = StatementParseEngine.parse(p.save(), null, null);
		List<RowResult> txns = parsed.transactions();
		assertEquals(7, txns.size(), describe(txns));

		// a one-line row's "Value Dt ... Ref" line is its narration, not a dropped dated row
		assertOwn(txns.get(0), "503311969906", "504081518454");
		assertTrue(txns.get(0).description.contains("Value Dt 02/02/2025"), txns.get(0).description);
		assertOwn(txns.get(1), "504081518454", "503311969906", "504284727859");
		// the reported case: the ₹300 row's last line sits nearer the ₹500 row
		assertOwn(txns.get(2), "504284727859", "504081518454", "504288070613");
		assertOwn(txns.get(3), "504288070613", "504284727859", "504824581996");
		assertOwn(txns.get(4), "504824581996", "504288070613", "NEFTREF0001");
		assertOwn(txns.get(5), "NEFTREF0001", "504824581996");
		assertTrue(txns.get(1).description.startsWith("UPI-RAJADURAI"), txns.get(1).description);
		assertTrue(txns.get(3).description.startsWith("UPI-ABINESH"), txns.get(3).description);

		assertEquals(-500.00, txns.get(3).amount, 0.001);
		assertEquals("AUTO-INGEST", parsed.parseInfo().verdict);
	}

	@Test
	void narrationPrintedAboveEachRowKeepsTheNearestRow() throws Exception {
		Pdf p = new Pdf();
		p.y = 700;
		p.line(l(300, "Statement of account"));
		p.gap(20);
		p.line(l(DATE, "Txn Date"), l(NARR, "Narration"), l(WDL - 50, "Withdrawals"), l(DEP - 40, "Deposits"),
				l(BAL - 60, "Closing Balance"));
		String[] refs = {"ABOVE0001", "ABOVE0002", "ABOVE0003", "ABOVE0004", "ABOVE0005"};
		double bal = 10000;
		for (int i = 0; i < refs.length; i++) {
			p.gap(BLOCK_GAP);
			p.line(l(NARR, "UPI Ref " + refs[i]));
			p.gap(PITCH);
			bal -= 100;
			p.line(l(DATE, String.format("%02d/03/2025", i + 1)), l(NARR, "PAYEE" + i), r(WDL, "100.00"), r(DEP, "0.00"),
					r(BAL, String.format("%,.2f", bal)));
		}

		List<RowResult> txns = StatementParseEngine.parse(p.save(), null, null).transactions();
		assertEquals(5, txns.size(), describe(txns));
		// (the first row's line sits above the table's first dated row, outside the table)
		for (int i = 1; i < refs.length; i++) {
			assertTrue(txns.get(i).description.contains(refs[i]), txns.get(i).description);
			assertFalse(txns.get(i - 1).description.contains(refs[i]), txns.get(i - 1).description);
		}
	}

	@Test
	void evenlySpacedLinesKeepTheNearestRow() throws Exception {
		// no wider gap between blocks: the document gives no side, the nearest row decides
		Pdf p = new Pdf();
		p.y = 700;
		p.line(l(300, "Statement of account"));
		p.gap(20);
		p.line(l(DATE, "Txn Date"), l(NARR, "Narration"), l(WDL - 50, "Withdrawals"), l(DEP - 40, "Deposits"),
				l(BAL - 60, "Closing Balance"));
		double bal = 10000;
		for (int i = 0; i < 5; i++) {
			p.gap(PITCH);
			bal -= 100;
			p.line(l(DATE, String.format("%02d/03/2025", i + 1)), l(NARR, "PAYEE" + i), r(WDL, "100.00"), r(DEP, "0.00"),
					r(BAL, String.format("%,.2f", bal)));
			p.gap(PITCH);
			p.line(l(NARR, "TAIL" + i));
			p.gap(PITCH);
			p.line(l(NARR, "HEAD" + (i + 1)));
		}
		List<RowResult> txns = StatementParseEngine.parse(p.save(), null, null).transactions();
		assertEquals(5, txns.size(), describe(txns));
		for (int i = 1; i < 5; i++) {
			assertTrue(txns.get(i).description.contains("HEAD" + i), txns.get(i).description);
		}
		// (lines under the table's last row are outside the table)
		for (int i = 0; i < 4; i++) {
			assertTrue(txns.get(i).description.contains("TAIL" + i), txns.get(i).description);
		}
	}

	private static void assertOwn(RowResult r, String own, String... others) {
		assertTrue(r.description.contains(own), r.description);
		for (String o : others) {
			assertFalse(r.description.contains(o), o + " in: " + r.description);
		}
	}

	private static String describe(List<RowResult> txns) {
		StringBuilder sb = new StringBuilder();
		for (RowResult r : txns) {
			sb.append(r.date).append(' ').append(r.amount).append(" | ").append(r.description).append('\n');
		}
		return sb.toString();
	}

	private record Cell(float x, String text, boolean rightAligned) {
	}

	private static Cell l(float x, String text) {
		return new Cell(x, text, false);
	}

	private static Cell r(float rightEdge, String text) {
		return new Cell(rightEdge, text, true);
	}

	/** One page; each line is drawn at the current baseline, {@link #gap} moves down by exact points. */
	private static final class Pdf {
		private static final float SIZE = 8;
		private final List<Object[]> lines = new ArrayList<>();
		float y;

		void line(Cell... cells) {
			lines.add(new Object[]{y, cells});
		}

		void gap(float pt) {
			y -= pt;
		}

		byte[] save() throws Exception {
			try (PDDocument doc = new PDDocument()) {
				PDType1Font font = new PDType1Font(Standard14Fonts.FontName.HELVETICA);
				PDPage page = new PDPage(PDRectangle.A4);
				doc.addPage(page);
				try (PDPageContentStream cs = new PDPageContentStream(doc, page)) {
					for (Object[] ln : lines) {
						float at = (Float) ln[0];
						for (Cell c : (Cell[]) ln[1]) {
							float x = c.rightAligned() ? c.x() - font.getStringWidth(c.text()) / 1000 * SIZE : c.x();
							cs.beginText();
							cs.setFont(font, SIZE);
							cs.newLineAtOffset(x, at);
							cs.showText(c.text());
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
}
