package com.financeos.statement.parser;

import java.time.LocalDate;
import java.util.List;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * ICICI's 2021-22 card statements print the statement and due dates left of the
 * table and a spends chart beside it. Those dates must not set the date column
 * (that switched on the HSBC block rules and dropped every row under a chart or
 * card-number line), and margin text must stay out of the descriptions. The
 * SerNo. and Reward columns are their own columns, not part of the narration.
 */
class IciciSpendsChartLayoutTest {

	@Test
	void everyRowBesideTheChartIsReadAndReconciles() throws Exception {
		ParsedStatement parsed = StatementParseEngine.parse(
				StatementPdfFixtures.iciciSpendsChartBesideTable(), null, null);
		List<RowResult> txns = parsed.transactions();

		assertEquals(8, txns.size(), describe(txns));
		assertRow(txns.get(0), LocalDate.of(2021, 11, 24), 1.00, "UPI Payment Received");
		assertRow(txns.get(1), LocalDate.of(2021, 11, 24), 2.00, "UPI Payment Received");
		assertRow(txns.get(2), LocalDate.of(2021, 11, 24), -237.00, "NEARBUY INDIA PVT LTD GURGAON IN");
		assertRow(txns.get(3), LocalDate.of(2021, 11, 26), -279.21, "Bigtree Entertainment MUMBAI IN");
		assertRow(txns.get(4), LocalDate.of(2021, 12, 5), -1178.82, "RAZ*RailTel Corporation Delhi IN");
		assertRow(txns.get(5), LocalDate.of(2021, 12, 9), -490.00, "AMAZON.IN - GROCERY");
		assertRow(txns.get(6), LocalDate.of(2021, 12, 10), -505.90, "K RATHINAVELU MUDALI");
		assertRow(txns.get(7), LocalDate.of(2021, 12, 12), 5.01, "Reversal of fuel Surcharge");

		assertEquals(LocalDate.of(2021, 11, 13), parsed.meta().periodStart);
		assertEquals(LocalDate.of(2021, 12, 12), parsed.meta().periodEnd);
		assertEquals("4315XXXXXXXX0005", parsed.meta().accountNumber);
		ParseInfo info = parsed.parseInfo();
		assertEquals("AUTO-INGEST", info.verdict);
		assertEquals(0, info.rowsDropped);
	}

	@Test
	void rewardPointsStayOutOfTheNarration() throws Exception {
		ParsedStatement parsed = StatementParseEngine.parse(StatementPdfFixtures.iciciRewardColumn(), null, null);
		List<RowResult> txns = parsed.transactions();

		assertEquals(2, txns.size(), describe(txns));
		assertRow(txns.get(0), LocalDate.of(2024, 7, 20), -8335.74, "IND*AMAZON HTTP://WWW.AM IN");
		assertRow(txns.get(1), LocalDate.of(2024, 8, 1), 2872.55, "UPI Payment Received");
	}

	@Test
	void yearlessDateNeedsAMonthName() {
		assertTrue(Tokens.isDateToken("05 Jun"));
		assertTrue(Tokens.isDateToken("5June"));
		assertTrue(Tokens.isDateToken("20SEP"));
		assertFalse(Tokens.isDateToken("33 the"));
		assertFalse(Tokens.isDateToken("12 items"));
	}

	private static void assertRow(RowResult row, LocalDate date, double amount, String description) {
		assertEquals(date, row.date, row.description);
		assertEquals(amount, row.amount, 0.001, row.description);
		assertEquals(description, row.description);
	}

	private static String describe(List<RowResult> txns) {
		StringBuilder sb = new StringBuilder();
		for (RowResult t : txns) {
			sb.append('\n').append(t.date).append(' ').append(t.amount).append(' ').append(t.description);
		}
		return sb.toString();
	}
}
