package com.financeos.statement.parser;

import java.time.LocalDate;
import java.util.List;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * ICICI's 2021-22 card statements print the statement and due dates left of the
 * table and a spends chart beside it. Those dates must not set the date column
 * (that switched on the HSBC block rules and dropped every row under a chart or
 * card-number line), and margin text must stay out of the descriptions.
 */
class IciciSpendsChartLayoutTest {

	@Test
	void everyRowBesideTheChartIsReadAndReconciles() throws Exception {
		ParsedStatement parsed = StatementParseEngine.parse(
				StatementPdfFixtures.iciciSpendsChartBesideTable(), null, null);
		List<RowResult> txns = parsed.transactions();

		assertEquals(7, txns.size(), describe(txns));
		assertRow(txns.get(0), LocalDate.of(2021, 11, 24), 1.00, "UPI Payment Received");
		assertRow(txns.get(1), LocalDate.of(2021, 11, 24), 2.00, "UPI Payment Received");
		assertRow(txns.get(2), LocalDate.of(2021, 11, 24), -237.00, "NEARBUY INDIA PVT LTD");
		assertRow(txns.get(3), LocalDate.of(2021, 11, 26), -279.21, "Bigtree Entertainment");
		assertRow(txns.get(4), LocalDate.of(2021, 12, 5), -1178.82, "RAZ*RailTel Corporation");
		assertRow(txns.get(5), LocalDate.of(2021, 12, 9), -490.00, "AMAZON.IN GROCERY");
		assertRow(txns.get(6), LocalDate.of(2021, 12, 10), -505.90, "RATHINAVELU MUDALI");

		for (RowResult t : txns) {
			assertFalse(t.description.contains("22091/50000"), t.description);
			assertFalse(t.description.contains("%"), t.description);
		}
		ParseInfo info = parsed.parseInfo();
		assertEquals("AUTO-INGEST", info.verdict);
		assertEquals(0, info.rowsDropped);
	}

	@Test
	void yearlessDateNeedsAMonthName() {
		assertTrue(Tokens.isDateToken("05 Jun"));
		assertTrue(Tokens.isDateToken("5June"));
		assertTrue(Tokens.isDateToken("20SEP"));
		assertFalse(Tokens.isDateToken("33 the"));
		assertFalse(Tokens.isDateToken("12 items"));
	}

	private static void assertRow(RowResult row, LocalDate date, double amount, String descPart) {
		assertEquals(date, row.date, row.description);
		assertEquals(amount, row.amount, 0.001, row.description);
		assertTrue(row.description.contains(descPart), row.description);
	}

	private static String describe(List<RowResult> txns) {
		StringBuilder sb = new StringBuilder();
		for (RowResult t : txns) {
			sb.append('\n').append(t.date).append(' ').append(t.amount).append(' ').append(t.description);
		}
		return sb.toString();
	}
}
