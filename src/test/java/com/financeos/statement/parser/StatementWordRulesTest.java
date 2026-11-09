package com.financeos.statement.parser;

import java.util.List;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** Word/line-level parser rules: bare ".dd" amounts, repeated-furniture stripping, lone-row filtering. */
class StatementWordRulesTest {

	private static Word w(String text, double x0, double x1, double top, int page) {
		return new Word(text, x0, x1, top, page);
	}

	@Test
	void bareFractionIsAZeroAmountOnlyForLooseReads() {
		assertEquals(0.0, Tokens.looseAmount(".00"), 0.0);
		assertEquals(0.5, Tokens.looseAmount(".50"), 0.0);
		assertEquals(0.0, Tokens.looseAmount("₹.00"), 0.0);
		assertEquals(0.0, Tokens.looseAmount("₹ .00DR"), 0.0);
		assertNull(Tokens.parseAmount(".00"));
		assertNull(Tokens.looseAmount(".005"));
	}

	@Test
	void datedLineWithAmountRepeatedOnALaterPageIsKept() {
		Line header1 = new Line(List.of(w("Statement", 10, 60, 20, 1), w("Summary", 65, 110, 20, 1)));
		Line txn1 = new Line(List.of(w("05/01/2026", 10, 70, 100, 1), w("TEST", 80, 100, 100, 1),
				w("SHOP", 105, 130, 100, 1), w("1,200.00", 300, 350, 100, 1)));
		Line header2 = new Line(List.of(w("Statement", 10, 60, 20, 2), w("Summary", 65, 110, 20, 2)));
		Line txn2 = new Line(List.of(w("05/01/2026", 10, 70, 100, 2), w("TEST", 80, 100, 100, 2),
				w("SHOP", 105, 130, 100, 2), w("1,200.00", 300, 350, 100, 2)));
		List<Line> kept = WordSource.stripRepeatedFurniture(List.of(header1, txn1, header2, txn2));
		assertEquals(List.of(header1, txn1, txn2), kept);
	}

	@Test
	void loneDatedRowOffTheDateColumnIsNotATransaction() {
		java.util.ArrayList<Line> lines = new java.util.ArrayList<>();
		lines.add(new Line(List.of(w("Date", 40, 60, 90, 1), w("Description", 100, 160, 90, 1),
				w("Amount", 400, 440, 90, 1))));
		lines.add(txn("05/01/2026", "1,100.00", 100, 1));
		lines.add(txn("06/01/2026", "2,200.00", 112, 1));
		// page 2: an EMI summary row whose date sits in another column
		lines.add(new Line(List.of(w("Loan", 40, 70, 90, 2), w("Summary", 75, 120, 90, 2))));
		lines.add(new Line(List.of(w("1234", 40, 70, 100, 2), w("07/01/2026", 220, 280, 100, 2),
				w("9,000.00", 400, 450, 100, 2))));
		CollectedRows rows = RowCollector.collect(lines, s -> { });
		assertEquals(2, newTxnCount(rows));
	}

	@Test
	void loneRowThatStartsWithItsDateOnAShiftedPageIsKept() {
		java.util.ArrayList<Line> lines = new java.util.ArrayList<>();
		lines.add(new Line(List.of(w("Date", 40, 60, 90, 1), w("Description", 100, 160, 90, 1),
				w("Amount", 400, 440, 90, 1))));
		lines.add(txn("05/01/2026", "1,100.00", 100, 1));
		lines.add(txn("06/01/2026", "2,200.00", 112, 1));
		lines.add(new Line(List.of(w("Date", 20, 40, 90, 2), w("Description", 80, 140, 90, 2),
				w("Amount", 400, 440, 90, 2))));
		lines.add(new Line(List.of(w("07/01/2026", 20, 80, 100, 2), w("TEST", 90, 120, 100, 2),
				w("9,000.00", 400, 450, 100, 2))));
		assertEquals(3, newTxnCount(RowCollector.collect(lines, s -> { })));
	}

	@Test
	void loneOffColumnRowWithDescriptionAndLeadingCellIsKept() {
		java.util.ArrayList<Line> lines = new java.util.ArrayList<>();
		lines.add(new Line(List.of(w("Date", 40, 60, 90, 1), w("Description", 100, 160, 90, 1),
				w("Amount", 400, 440, 90, 1))));
		lines.add(txn("05/01/2026", "1,100.00", 100, 1));
		lines.add(txn("06/01/2026", "2,200.00", 112, 1));
		lines.add(new Line(List.of(w("7", 20, 26, 100, 2), w("07/01/2026", 220, 280, 100, 2),
				w("TEST", 290, 320, 100, 2), w("SHOP", 325, 355, 100, 2), w("9,000.00", 400, 450, 100, 2))));
		assertEquals(3, newTxnCount(RowCollector.collect(lines, s -> { })));
	}

	@Test
	void repeatedOpeningBalanceLineIsStillStripped() {
		Line open1 = new Line(List.of(w("Opening", 10, 50, 50, 1), w("Balance", 55, 95, 50, 1),
				w("as", 100, 110, 50, 1), w("on", 112, 124, 50, 1), w("01-04-2024", 130, 190, 50, 1),
				w("5,000.00", 300, 350, 50, 1)));
		Line open2 = new Line(List.of(w("Opening", 10, 50, 50, 2), w("Balance", 55, 95, 50, 2),
				w("as", 100, 110, 50, 2), w("on", 112, 124, 50, 2), w("01-04-2024", 130, 190, 50, 2),
				w("5,000.00", 300, 350, 50, 2)));
		assertEquals(List.of(open1), WordSource.stripRepeatedFurniture(List.of(open1, open2)));
	}

	private static long newTxnCount(CollectedRows rows) {
		return rows.rows().stream().filter(r -> Boolean.TRUE.equals(r.isNewTxn())).count();
	}

	private static Line txn(String date, String amount, double top, int page) {
		return new Line(List.of(w(date, 40, 100, top, page), w("TEST", 110, 140, top, page),
				w("SHOP", 145, 175, top, page), w(amount, 400, 450, top, page)));
	}
}
