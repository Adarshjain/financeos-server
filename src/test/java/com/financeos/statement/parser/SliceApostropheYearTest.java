package com.financeos.statement.parser;

import java.time.LocalDate;
import java.util.List;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * slice prints dates as "02 Sep '26". Without the apostrophe-year format the
 * row date fell back to the yearless "02 Sep" (year 1900), "'26" leaked into the
 * description and the narration's own "31 Aug" was eaten as a value date.
 */
class SliceApostropheYearTest {

	// --- date tokens ---

	@Test
	void apostropheYearIsOneDateToken() {
		assertTrue(Tokens.isDateToken("02 Sep '26"));
		assertEquals(LocalDate.of(2026, 9, 2), Dates.parse("02 Sep '26", "%d %b '%y"));
	}

	@Test
	void apostropheFormatOnlyParsesTheApostropheForm() {
		assertNull(Dates.parse("02 Sep 26", "%d %b '%y"));
		assertNull(Dates.parse("02 Sep 2026", "%d %b '%y"));
		assertNull(Dates.parse("02 Sep", "%d %b '%y"));
		assertNull(Dates.parse("02 Sep '26", "%d %b"), "yearless format must not swallow the year");
		assertFalse(Tokens.isDateToken("02 Sep '2026"));
	}

	@Test
	void inferredFormatForApostropheRowsHasAYear() {
		assertEquals("%d %b '%y", Dates.inferDateFormat(List.of("01 Sep '26", "02 Sep '26", "30 Sep '26")));
	}

	@Test
	void existingFormatsStillWinTheirOwnRows() {
		assertEquals("%d %b %y", Dates.inferDateFormat(List.of("01 Sep 26", "02 Sep 26")));
		assertEquals("%d %b", Dates.inferDateFormat(List.of("01 Sep", "02 Sep")));
	}

	// --- amounts ---

	@Test
	void minusBeforeRupeeIsADebit() {
		Amount a = Tokens.parseAmount("-₹2,20,000.00");
		assertEquals(220000.0, a.value(), 0.001);
		assertEquals(-1, a.sign());
	}

	@Test
	void unsignedRupeeAmountIsUnchanged() {
		Amount a = Tokens.parseAmount("₹2,20,000.84");
		assertEquals(220000.84, a.value(), 0.001);
		assertEquals(0, a.sign());
	}

	@Test
	void minusAfterRupeeIsStillADebit() {
		Amount a = Tokens.parseAmount("₹-2,20,000.00");
		assertEquals(220000.0, a.value(), 0.001);
		assertEquals(-1, a.sign());
	}

	// --- statement period ---

	@Test
	void bareMastheadRangeSetsThePeriod() {
		StatementMeta meta = MetadataExtractor.extract(List.of(line("01", "Sep", "'26", "-", "30", "Sep", "'26")), null);
		assertEquals(LocalDate.of(2026, 9, 1), meta.periodStart);
		assertEquals(LocalDate.of(2026, 9, 30), meta.periodEnd);
	}

	@Test
	void labelledPeriodWinsOverAnEarlierBareRange() {
		StatementMeta meta = MetadataExtractor.extract(List.of(
				line("01/08/2026", "-", "31/08/2026"),
				line("Statement", "Period:", "01/09/2026", "to", "30/09/2026")), null);
		assertEquals(LocalDate.of(2026, 9, 1), meta.periodStart);
		assertEquals(LocalDate.of(2026, 9, 30), meta.periodEnd);
	}

	@Test
	void rangeWithOtherTextIsNotAPeriod() {
		StatementMeta meta = MetadataExtractor.extract(
				List.of(line("Offer", "valid", "01", "Sep", "'26", "-", "30", "Sep", "'26")), null);
		assertNull(meta.periodStart);
	}

	// --- end to end ---

	@Test
	void parsesSliceStatementWithRealYearAndCleanDescriptions() throws Exception {
		ParsedStatement ps = StatementParseEngine.parse(StatementPdfFixtures.sliceApostropheYear(), null, null);

		assertEquals(LocalDate.of(2026, 9, 1), ps.meta().periodStart);
		assertEquals(LocalDate.of(2026, 9, 30), ps.meta().periodEnd);

		List<RowResult> txns = ps.transactions();
		assertEquals(3, txns.size());

		assertEquals(LocalDate.of(2026, 9, 1), txns.get(0).date);
		assertEquals(70.84, txns.get(0).amount, 0.001);
		assertEquals("Interest Cr. for 31 Aug 2026", txns.get(0).description);

		assertEquals(LocalDate.of(2026, 9, 2), txns.get(1).date);
		assertEquals(220000.0, txns.get(1).amount, 0.001);
		assertEquals(220071.68, txns.get(1).balance, 0.001);
		assertEquals("RTGS Credit-TEST USER-HSBC040000 2-XX9759-HSBCR22026090238615010", txns.get(1).description);

		assertEquals(LocalDate.of(2026, 9, 2), txns.get(2).date);
		assertEquals(-220000.0, txns.get(2).amount, 0.001);
		assertEquals(71.68, txns.get(2).balance, 0.001);
		assertEquals("IMPS-Debit-REF 624509173023-TO", txns.get(2).description);

		assertEquals("balance-chain", ps.parseInfo().mode);
	}

	private static Line line(String... words) {
		List<Word> ws = new java.util.ArrayList<>();
		double x = 0;
		for (String w : words) {
			ws.add(new Word(w, x, x + 10, 100, 0));
			x += 20;
		}
		return new Line(ws);
	}
}
