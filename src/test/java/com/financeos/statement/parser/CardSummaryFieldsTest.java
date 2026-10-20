package com.financeos.statement.parser;

import java.time.LocalDate;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Card summary figures read from label/value layouts, and a statement period
 * that is only ever read from the document, never inferred from the rows.
 */
class CardSummaryFieldsTest {

	@Test
	void totalDueIsReadFromTheRowOfFiguresUnderItsLabel() throws Exception {
		ParsedStatement ps = StatementParseEngine.parse(StatementPdfFixtures.cardSummaryBoxWithLabelsAboveValues(), null, null);
		assertEquals(4198.0, (Double) ps.summaryFields().get("total_amount_due"), 0.001);
		assertEquals(327.0, (Double) ps.summaryFields().get("minimum_amount_due"), 0.001);
		assertEquals(LocalDate.of(2026, 8, 6), ps.summaryFields().get("payment_due_date"));
		assertEquals(LocalDate.of(2026, 6, 18), ps.meta().periodStart);
		assertEquals(LocalDate.of(2026, 7, 17), ps.meta().periodEnd);
	}

	@Test
	void minimumDueIsInferredBesideTheDueDateWhenLabelsAreMissing() throws Exception {
		ParsedStatement ps = StatementParseEngine.parse(StatementPdfFixtures.cardSummaryWithoutLabels(), null, null);
		assertEquals(LocalDate.of(2026, 9, 19), ps.meta().periodStart);
		assertEquals(LocalDate.of(2026, 10, 18), ps.meta().periodEnd);
		assertEquals(LocalDate.of(2026, 11, 5), ps.summaryFields().get("payment_due_date"));
		assertEquals(447.71, (Double) ps.summaryFields().get("minimum_amount_due"), 0.001);
	}

	@Test
	void periodStaysNullWithoutALabelOrRange() throws Exception {
		ParsedStatement ps = StatementParseEngine.parse(StatementPdfFixtures.cardWithoutAnyPeriod(), null, null);
		assertFalse(ps.transactions().isEmpty());
		assertNull(ps.meta().periodStart);
		assertNull(ps.meta().periodEnd);
	}
}
