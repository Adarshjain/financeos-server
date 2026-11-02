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
 * Column assignment must not depend on having two or more rows to compare: header
 * labels, x-positions and the opening/closing arithmetic decide it. Layout mirrors
 * the E2E generator (Debit / Credit / Balance, right-aligned). Made-up data only.
 */
class SingleRowColumnsTest {

	private record Row(String date, String desc, String debit, String credit, String balance) {
	}

	private static byte[] pdf(String opening, String closing, List<Row> rows, boolean balanceCol) throws Exception {
		return pdf(opening, closing, rows, balanceCol, false);
	}

	private static byte[] pdf(String opening, String closing, List<Row> rows, boolean balanceCol,
			boolean singleAmount) throws Exception {
		try (PDDocument doc = new PDDocument()) {
			PDType1Font reg = new PDType1Font(Standard14Fonts.FontName.HELVETICA);
			PDPage page = new PDPage(PDRectangle.A4);
			doc.addPage(page);
			try (PDPageContentStream cs = new PDPageContentStream(doc, page)) {
				float[] y = {780};
				text(cs, reg, "Test Bank", 50, y[0] -= 0);
				text(cs, reg, "Account No: 1234567890", 50, y[0] -= 15);
				text(cs, reg, "Statement Period: 01/04/2026 to 30/04/2026", 50, y[0] -= 15);
				if (opening != null) {
					text(cs, reg, "Opening Balance: " + opening, 50, y[0] -= 15);
				}
				y[0] -= 30;
				text(cs, reg, "Date", 50, y[0]);
				text(cs, reg, "Description", 125, y[0]);
				right(cs, reg, singleAmount ? "Amount" : balanceCol ? "Debit" : "Withdrawal Amt.", 385, y[0]);
				if (!singleAmount) {
					right(cs, reg, balanceCol ? "Credit" : "Deposit Amt.", 465, y[0]);
				}
				if (balanceCol) {
					right(cs, reg, "Balance", 550, y[0]);
				}
				y[0] -= 18;
				for (Row r : rows) {
					text(cs, reg, r.date(), 50, y[0]);
					text(cs, reg, r.desc(), 125, y[0]);
					if (r.debit() != null) {
						right(cs, reg, r.debit(), 385, y[0]);
					}
					if (r.credit() != null) {
						right(cs, reg, r.credit(), 465, y[0]);
					}
					if (balanceCol) {
						right(cs, reg, r.balance(), 550, y[0]);
					}
					y[0] -= 16;
				}
				if (closing != null) {
					y[0] -= 10;
					text(cs, reg, "Closing Balance: " + closing, 50, y[0]);
				}
			}
			ByteArrayOutputStream out = new ByteArrayOutputStream();
			doc.save(out);
			return out.toByteArray();
		}
	}

	private static void text(PDPageContentStream cs, PDType1Font f, String s, float x, float y) throws Exception {
		cs.beginText();
		cs.setFont(f, 9);
		cs.newLineAtOffset(x, y);
		cs.showText(s);
		cs.endText();
	}

	private static void right(PDPageContentStream cs, PDType1Font f, String s, float x, float y) throws Exception {
		text(cs, f, s, x - f.getStringWidth(s) / 1000 * 9, y);
	}

	private static ParsedStatement parse(byte[] b) {
		return StatementParseEngine.parse(b, null, null);
	}

	@Test
	void oneDebitRow() throws Exception {
		ParsedStatement p = parse(pdf("50,000.00", "49,550.00",
				List.of(new Row("05/04/2026", "UPI TO COFFEE", "450.00", null, "49,550.00")), true));
		assertEquals(1, p.transactions().size());
		RowResult r = p.transactions().get(0);
		assertEquals(-450.0, r.amount, 0.001);
		assertEquals(49550.0, r.balance, 0.001);
		assertTrue(r.chainValid);
	}

	@Test
	void oneCreditRow() throws Exception {
		ParsedStatement p = parse(pdf("50,000.00", "50,450.00",
				List.of(new Row("05/04/2026", "SALARY REFUND", null, "450.00", "50,450.00")), true));
		assertEquals(1, p.transactions().size());
		RowResult r = p.transactions().get(0);
		assertEquals(450.0, r.amount, 0.001);
		assertEquals(50450.0, r.balance, 0.001);
	}

	@Test
	void oneDebitRowWithoutPrintedOpening() throws Exception {
		ParsedStatement p = parse(pdf(null, null,
				List.of(new Row("05/04/2026", "UPI TO COFFEE", "450.00", null, "49,550.00")), true));
		RowResult r = p.transactions().get(0);
		assertEquals(-450.0, r.amount, 0.001);
	}

	@Test
	void oneRowWithoutPrintedOpeningOrClosingCredit() throws Exception {
		ParsedStatement p = parse(pdf(null, null,
				List.of(new Row("05/04/2026", "SALARY REFUND", null, "450.00", "50,450.00")), true));
		RowResult r = p.transactions().get(0);
		assertEquals(450.0, r.amount, 0.001);
	}

	@Test
	void allDebitRowsEmptyCreditColumn() throws Exception {
		ParsedStatement p = parse(pdf("50,000.00", "48,800.00", List.of(
				new Row("05/04/2026", "UPI TO COFFEE", "450.00", null, "49,550.00"),
				new Row("07/04/2026", "UPI TO GROCER", "750.00", null, "48,800.00")), true));
		assertEquals(2, p.transactions().size());
		assertEquals(-450.0, p.transactions().get(0).amount, 0.001);
		assertEquals(-750.0, p.transactions().get(1).amount, 0.001);
		assertEquals(48800.0, p.transactions().get(1).balance, 0.001);
	}

	@Test
	void twoRowsDebitAndCredit() throws Exception {
		ParsedStatement p = parse(pdf("50,000.00", "50,000.00", List.of(
				new Row("05/04/2026", "UPI TO COFFEE", "450.00", null, "49,550.00"),
				new Row("07/04/2026", "SALARY REFUND", null, "450.00", "50,000.00")), true));
		assertEquals(-450.0, p.transactions().get(0).amount, 0.001);
		assertEquals(450.0, p.transactions().get(1).amount, 0.001);
		assertEquals(50000.0, p.transactions().get(1).balance, 0.001);
	}

	@Test
	void oneRowDebitNoBalanceColumn() throws Exception {
		ParsedStatement p = parse(pdf(null, null,
				List.of(new Row("05/04/2026", "UPI TO COFFEE", "450.00", null, null)), false));
		assertEquals(-450.0, p.transactions().get(0).amount, 0.001);
	}

	@Test
	void oneRowCreditNoBalanceColumn() throws Exception {
		ParsedStatement p = parse(pdf(null, null,
				List.of(new Row("05/04/2026", "SALARY REFUND", null, "450.00", null)), false));
		assertEquals(450.0, p.transactions().get(0).amount, 0.001);
	}

	@Test
	void oneRowSingleAmountColumnPlusBalance() throws Exception {
		ParsedStatement p = parse(pdf("50,000.00", "49,550.00",
				List.of(new Row("05/04/2026", "UPI TO COFFEE", "450.00 Dr", null, "49,550.00")), true, true));
		RowResult r = p.transactions().get(0);
		assertEquals(-450.0, r.amount, 0.001);
		assertEquals(49550.0, r.balance, 0.001);
	}
}
