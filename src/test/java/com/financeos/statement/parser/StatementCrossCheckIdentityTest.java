package com.financeos.statement.parser;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import org.junit.jupiter.api.Test;

/**
 * A statement with no balance column is auto-ingested on a printed-total match alone only when it also names its
 * account and its period: a total matching "some number on the page" is weak evidence for an anonymous document.
 */
class StatementCrossCheckIdentityTest {

	@Test
	void debitTotalMatchingAPrintedNumberWithAccountAndPeriodIsAutoIngested() throws Exception {
		ParsedStatement ps = StatementParseEngine.parse(StatementPdfFixtures.bankNoBalancePrintedDebitTotal(true), null, null);

		assertNull(ps.parseInfo().checksumOk);
		assertEquals(Boolean.TRUE, ps.parseInfo().crossDebits);
		assertNotNull(ps.meta().accountNumber);
		assertNotNull(ps.meta().periodEnd);
		assertEquals("AUTO-INGEST", ps.parseInfo().verdict);
	}

	@Test
	void sameMatchWithNeitherAccountNorPeriodIsOnlyNeedsReview() throws Exception {
		ParsedStatement ps = StatementParseEngine.parse(StatementPdfFixtures.bankNoBalancePrintedDebitTotal(false), null, null);

		assertNull(ps.parseInfo().checksumOk);
		assertEquals(Boolean.TRUE, ps.parseInfo().crossDebits);
		assertNull(ps.meta().accountNumber);
		assertNull(ps.meta().periodEnd);
		assertEquals("NEEDS REVIEW", ps.parseInfo().verdict);
	}
}
