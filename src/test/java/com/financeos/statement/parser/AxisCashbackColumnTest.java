package com.financeos.statement.parser;

import java.time.LocalDate;
import java.util.List;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Axis card tables end in a CASHBACK EARNED column whose cells read like amounts ("0.00 Dr",
 * "54.00 Cr"). Letter-spaced figures extract as two words ("5" + ",000.00"); when that amount
 * was not read, the cashback cell became the row's amount (0 on credit rows, a phantom credit
 * on a debit with cashback). The footer block under the table is not part of the last row.
 */
class AxisCashbackColumnTest {

	@Test
	void spacedAmountsAreReadAndTheCashbackColumnIsNeverTheAmount() throws Exception {
		ParsedStatement parsed = StatementParseEngine.parse(StatementPdfFixtures.axisCashbackColumn(), null, null);
		List<RowResult> txns = parsed.transactions();

		assertEquals(5, txns.size(), describe(txns));
		assertRow(txns.get(0), LocalDate.of(2022, 10, 21), 5000.00, "NEFT PAYMENT RECEIVED");
		assertRow(txns.get(1), LocalDate.of(2022, 10, 22), 15008.00, "NEFT PAYMENT RECEIVED");
		assertRow(txns.get(2), LocalDate.of(2022, 10, 29), -213.00, "HOTELSRIKRISHNAPRASAD RESTAURANTS");
		assertRow(txns.get(3), LocalDate.of(2022, 10, 30), -1089.00, "GRAND ELECTRONICS CHENNAI MISCELLANEOUS");
		assertRow(txns.get(4), LocalDate.of(2022, 11, 10), 1842.00, "CASHBACK CREDIT OCT 2022");

		assertEquals(21850.00, parsed.parseInfo().totalCredits, 0.001);
		assertEquals(1302.00, parsed.parseInfo().totalDebits, 0.001);
		assertEquals(0, parsed.parseInfo().rowsDropped);
	}

	@Test
	void aRowReadAsZeroWithItsAmountStillInTheTextIsDroppedAndFlagged() throws Exception {
		ParsedStatement parsed = StatementParseEngine.parse(StatementPdfFixtures.cardRowWithUnreadableAmount(), null, null);
		List<RowResult> txns = parsed.transactions();

		for (RowResult t : txns) {
			assertNotEquals(0.0, t.amount, 0.0, "no 0 line may be emitted: " + t.description);
		}
		assertTrue(parsed.parseInfo().rowsDropped >= 1, describe(txns));
		assertNotEquals("AUTO-INGEST", parsed.parseInfo().verdict);
	}

	@Test
	void aFiveLineWrappedNarrationSurvivesWhole() throws Exception {
		ParsedStatement parsed = StatementParseEngine.parse(StatementPdfFixtures.bankRowWithFiveLineNarration(), null, null);
		List<RowResult> txns = parsed.transactions();

		assertEquals(2, txns.size(), describe(txns));
		assertEquals(-213.00, txns.get(0).amount, 0.001);
		assertEquals("NEFT FROM ACME", txns.get(1).description);
		for (String part : new String[]{"NARRL1", "NARRL2", "NARRL3", "NARRL4", "NARRL5", "NARRL6"}) {
			assertTrue(txns.get(0).description.contains(part), part + " lost: " + txns.get(0).description);
		}
	}

	@Test
	void aLoneCashbackWordIsNotASideColumn() throws Exception {
		ParsedStatement parsed = StatementParseEngine.parse(StatementPdfFixtures.bankHeaderWithLoneCashbackWord(), null, null);
		List<RowResult> txns = parsed.transactions();

		assertEquals(2, txns.size(), describe(txns));
		assertRow(txns.get(0), LocalDate.of(2026, 3, 5), 1000.00, "NEFT FROM ACME");
		assertRow(txns.get(1), LocalDate.of(2026, 3, 6), -213.00, "UPI TO SHOP");
	}

	@Test
	void aSplitFigureInANarrationNextToAnAmountColumnStaysText() throws Exception {
		ParsedStatement parsed = StatementParseEngine.parse(StatementPdfFixtures.bankBlockWithSplitFigureInNarration(), null, null);
		List<RowResult> txns = parsed.transactions();

		assertEquals(1, txns.size(), describe(txns));
		assertEquals(-213.00, txns.get(0).amount, 0.001, describe(txns));
		for (String part : new String[]{"CARD SWIPE", "FOREIGN", "SHOP"}) {
			assertTrue(txns.get(0).description.contains(part), part + " lost: " + txns.get(0).description);
		}
	}

	@Test
	void aZeroRowWithADateOrARateInItsTextIsKept() throws Exception {
		for (String narration : new String[]{"FEE WAIVER 12.05.2024", "OFFER RATE 12.50% P.A."}) {
			ParsedStatement parsed = StatementParseEngine.parse(StatementPdfFixtures.cardZeroRowWithText(narration), null, null);
			List<RowResult> txns = parsed.transactions();
			assertTrue(txns.stream().anyMatch(t -> t.amount == 0.0 && t.description.equals(narration)),
					narration + describe(txns));
		}
	}

	@Test
	void aZeroRowWithASplitAmountInItsTextIsDropped() throws Exception {
		ParsedStatement parsed = StatementParseEngine.parse(StatementPdfFixtures.cardZeroRowWithSplitFigure(), null, null);
		for (RowResult t : parsed.transactions()) {
			assertNotEquals(0.0, t.amount, 0.0, "no 0 line may be emitted: " + t.description);
		}
		assertTrue(parsed.parseInfo().rowsDropped >= 1, describe(parsed.transactions()));
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
