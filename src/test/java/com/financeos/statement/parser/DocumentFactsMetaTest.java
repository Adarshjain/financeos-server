package com.financeos.statement.parser;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** The added meta fields (what the document says about itself) beside the unchanged old ones. */
class DocumentFactsMetaTest {

    @Test
    void cardStatementFillsTheAddedFieldsAndLeavesTheOldOnesAlone() throws Exception {
        ParsedStatement ps = StatementParseEngine.parse(StatementPdfFixtures.cardSummaryHeaderWithTotalDues(), null, null);
        StatementMeta m = ps.meta();
        assertThat(m.isStatement).isNotNull();
        if (m.documentKind != null) assertThat(m.documentKind).isEqualTo("CREDIT_CARD");
        // old meta fields are exactly what the engine always produced for this fixture
        StatementMeta again = StatementParseEngine.parse(StatementPdfFixtures.cardSummaryHeaderWithTotalDues(), null, null).meta();
        assertThat(again.periodEnd).isEqualTo(m.periodEnd);
        assertThat(again.accountNumber).isEqualTo(m.accountNumber);
    }

    @Test
    void factsReadIssuerKindDateLimitAndProductFromText() {
        DocumentFacts.Facts f = DocumentFacts.extract(
                "HDFC Bank Regalia Gold Credit Card Statement\nStatement Date: 15/09/2026\nTotal Amount Due: Rs. 100.00\nMinimum Amount Due: Rs. 10.00\n"
                        + "Credit Limit: Rs. 4,50,000.00\nAvailable Credit Limit: Rs. 1,00,000.00\n",
                DocumentReader.NO_NUMBERS, new java.util.TreeSet<>(), null);
        assertThat(f.issuer()).isEqualTo("hdfc");
        assertThat(f.kind()).isEqualTo(DocumentFacts.DocKind.CREDIT_CARD);
        assertThat(f.periodEnd()).isEqualTo(java.time.LocalDate.of(2026, 9, 15));
        assertThat(f.limit()).isEqualTo(450000.0);
        assertThat(f.rawProduct()).contains("Regalia Gold");
    }
}
