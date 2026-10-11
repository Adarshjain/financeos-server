package com.financeos.gmail.reconcile;

import com.financeos.domain.statement.StatementVerdict;
import com.financeos.statement.parser.StatementPdfFixtures;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class StatementParserSkippedRowsTest {

    private final StatementParser parser = new StatementParser();

    @Test
    void rowsTheEngineDiscardsAreCountedAsSkipped() throws Exception {
        StatementExtractionResult result = parser.parse(StatementPdfFixtures.singleLineRowsWithChainOutlier(), null);

        assertThat(result.success()).isTrue();
        assertThat(result.lines()).hasSize(5);
        assertThat(result.draft().transactionCount()).isEqualTo(5);
        assertThat(result.draft().linesSkipped()).isEqualTo(1);
    }

    @Test
    void fullyReadHsbcStatementHasNothingSkippedAndReconciles() throws Exception {
        StatementExtractionResult result = parser.parse(StatementPdfFixtures.hsbcSameDayRowsAcrossPageBreak(), null);

        assertThat(result.success()).isTrue();
        assertThat(result.lines()).hasSize(5);
        assertThat(result.lines()).extracting(ParsedStatementLine::direction)
                .containsExactly("CREDIT", "CREDIT", "DEBIT", "DEBIT", "CREDIT");
        assertThat(result.draft().linesSkipped()).isZero();
        assertThat(result.draft().checksumOk()).isTrue();
        assertThat(result.draft().verdict()).isEqualTo(StatementVerdict.AUTO_INGEST);
    }
}
