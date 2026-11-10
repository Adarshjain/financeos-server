package com.financeos.domain.statement;

import static org.assertj.core.api.Assertions.assertThat;

import com.financeos.gmail.reconcile.StatementExtractionResult;
import com.financeos.gmail.reconcile.StatementParser;
import com.financeos.statement.parser.StatementPdfFixtures;
import org.junit.jupiter.api.Test;

/** The gate on the parser's real output, not a hand-built draft. */
class StatementImportGateRealParseTest {

    @Test
    void aCardStatementWithNothingToCheckAgainstPassesTheGate() throws Exception {
        StatementExtractionResult result = new StatementParser().parse(StatementPdfFixtures.cardWithPeriodAndNumberButNoSummary(), null);

        assertThat(result.success()).isTrue();
        assertThat(result.lines()).hasSize(2);
        assertThat(result.draft().statementType()).isEqualTo("credit_card");
        // The shape that made a card-check rule refuse such statements: REJECTED, no checksum, card check FALSE.
        assertThat(result.draft().verdict()).isEqualTo(StatementVerdict.REJECTED);
        assertThat(result.draft().checksumOk()).isNull();
        assertThat(result.draft().cardChecksOk()).isFalse();
        assertThat(result.periodEnd()).isNotNull();
        assertThat(StatementImportGate.check(result)).isEmpty();
    }
}
