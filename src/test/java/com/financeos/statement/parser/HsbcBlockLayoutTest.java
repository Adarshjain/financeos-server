package com.financeos.statement.parser;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * HSBC prints a date once per day and spreads each transaction over several
 * lines (narration first, amount + balance on the last). These pin down the
 * block layout: same-day rows split out, page breaks crossed, narration figures
 * and dates kept out of the amount and date columns.
 */
class HsbcBlockLayoutTest {

	@Test
	void sameDayRowsPageBreakAndTrailingUndatedRowAreAllRead() throws Exception {
		List<String> debug = new ArrayList<>();
		ParsedStatement parsed = StatementParseEngine.parse(
				StatementPdfFixtures.hsbcSameDayRowsAcrossPageBreak(), null, debug::add);
		List<RowResult> txns = parsed.transactions();

		assertEquals(5, txns.size(), describe(txns));
		assertRow(txns.get(0), LocalDate.of(2026, 2, 23), 1.00, 1.00);
		assertRow(txns.get(1), LocalDate.of(2026, 2, 23), 33000.00, 33001.00);
		assertRow(txns.get(2), LocalDate.of(2026, 2, 25), -10000.00, 23001.00);
		assertRow(txns.get(3), LocalDate.of(2026, 2, 27), -1015.85, 21985.15);
		assertRow(txns.get(4), LocalDate.of(2026, 2, 27), 305705.74, 327690.89);

		// each undated sibling keeps its own narration
		assertTrue(txns.get(0).description.contains("605409990907"), txns.get(0).description);
		assertFalse(txns.get(0).description.contains("605409994425"), txns.get(0).description);
		assertTrue(txns.get(1).description.contains("605409994425"), txns.get(1).description);
		assertTrue(txns.get(2).description.contains("ONE MOBIKWIK SYSTEMS"), txns.get(2).description);
		assertTrue(txns.get(3).description.contains("RAZ*ZUELPAY SERVICES PRI"), txns.get(3).description);
		assertTrue(txns.get(4).description.contains("NEFT FROM ACME CORP Salary"), txns.get(4).description);
		assertFalse(txns.get(4).description.contains("RAZ*ZUELPAY"), txns.get(4).description);

		assertEquals(0.0, parsed.meta().openingBalance, 0.001);
		assertEquals(327690.89, parsed.meta().closingBalance, 0.001);
		ParseInfo info = parsed.parseInfo();
		assertEquals("balance-chain", info.mode);
		assertEquals(Boolean.TRUE, info.checksumOk);
		assertEquals("AUTO-INGEST", info.verdict);
		assertEquals(0, info.rowsDropped);
		assertTrue(debug.contains("[debug] dropped dated line above the table header: "
				+ "06Mar2026 HSBC PREMIER PAYMENT 5120 4299 9443 7106"), debug.toString());
	}

	@Test
	void narrationDatesAndCardLikeReferencesDoNotBreakRows() throws Exception {
		ParsedStatement parsed = StatementParseEngine.parse(
				StatementPdfFixtures.hsbcNarrationDatesAndCardLikeReferences(), null, null);
		List<RowResult> txns = parsed.transactions();

		assertEquals(8, txns.size(), describe(txns));
		assertRow(txns.get(0), LocalDate.of(2026, 7, 2), 305706.74, 310329.48);
		assertRow(txns.get(1), LocalDate.of(2026, 7, 2), -49302.18, 261027.30);
		assertRow(txns.get(2), LocalDate.of(2026, 7, 2), -150000.00, 111027.30);
		assertRow(txns.get(3), LocalDate.of(2026, 7, 13), -100.00, 110927.30);
		assertRow(txns.get(4), LocalDate.of(2026, 7, 13), -35000.00, 75927.30);
		assertRow(txns.get(5), LocalDate.of(2026, 7, 18), 1000000.00, 1075927.30);
		assertRow(txns.get(6), LocalDate.of(2026, 7, 18), -1000000.00, 75927.30);
		assertRow(txns.get(7), LocalDate.of(2026, 7, 21), -60000.00, 15927.30);

		// narration dates stay in the narration of the row they belong to
		assertTrue(txns.get(0).description.contains("NEFT FROM ACME CORP"), txns.get(0).description);
		assertTrue(txns.get(5).description.contains("HDFCR52026071885088286"), txns.get(5).description);
		assertTrue(txns.get(5).description.contains("181304"), txns.get(5).description);
		assertTrue(txns.get(3).description.contains("Credit card bill payment"), txns.get(3).description);

		assertEquals(4622.74, parsed.meta().openingBalance, 0.001);
		assertEquals(15927.30, parsed.meta().closingBalance, 0.001);
		assertEquals(Boolean.TRUE, parsed.parseInfo().checksumOk);
		assertEquals("AUTO-INGEST", parsed.parseInfo().verdict);
		assertEquals(0, parsed.parseInfo().rowsDropped);
	}

	@Test
	void prunedChainOutlierIsCountedAsDropped() throws Exception {
		ParsedStatement parsed = StatementParseEngine.parse(
				StatementPdfFixtures.singleLineRowsWithChainOutlier(), null, null);
		List<RowResult> txns = parsed.transactions();

		assertEquals(5, txns.size(), describe(txns));
		assertRow(txns.get(0), LocalDate.of(2026, 4, 1), -1000.00, 49000.00);
		assertRow(txns.get(1), LocalDate.of(2026, 4, 5), 25000.00, 74000.00);
		assertRow(txns.get(4), LocalDate.of(2026, 4, 15), 500.00, 52000.00);
		assertEquals("balance-chain", parsed.parseInfo().mode);
		assertEquals(1, parsed.parseInfo().rowsDropped);
		assertEquals(Boolean.TRUE, parsed.parseInfo().checksumOk);
	}

	private static void assertRow(RowResult row, LocalDate date, double amount, double balance) {
		assertEquals(date, row.date, row.description);
		assertEquals(amount, row.amount, 0.001, row.description);
		assertEquals(balance, row.balance, 0.001, row.description);
		assertTrue(row.chainValid, row.description);
	}

	private static String describe(List<RowResult> txns) {
		StringBuilder sb = new StringBuilder();
		for (RowResult r : txns) {
			sb.append('\n').append(r.date).append(' ').append(r.amount).append(' ').append(r.balance)
					.append(' ').append(r.description);
		}
		return sb.toString();
	}
}
