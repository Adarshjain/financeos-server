package com.financeos.gmail.reconcile;

import static org.assertj.core.api.Assertions.assertThat;

import com.financeos.statement.parser.ParseSnapshot;
import com.financeos.statement.parser.ProtectedPdfs;
import com.financeos.statement.parser.StatementParseEngine;
import com.financeos.statement.parser.StatementParseException.Kind;
import com.financeos.statement.parser.StatementPdfFixtures;
import org.junit.jupiter.api.Test;

/** StatementParser hands the typed failure, the parser version, the parse snapshot and the issuer on to its callers. */
class StatementParserRecordTest {

    private final StatementParser parser = new StatementParser();

    @Test
    void aParseCarriesTheParserVersionAndTheSnapshotOfWhatWasRead() throws Exception {
        byte[] bytes = StatementPdfFixtures.cardWithPeriodAndNumberButNoSummary();

        StatementExtractionResult result = parser.parse(bytes, null);

        assertThat(result.success()).isTrue();
        assertThat(result.failureKind()).isNull();
        assertThat(result.draft().parserVersion()).isEqualTo(StatementParseEngine.PARSER_VERSION);
        assertThat(result.draft().parseSnapshot()).isEqualTo(ParseSnapshot.json(StatementParseEngine.parse(bytes, null, null)));
        assertThat(result.draft().parseSnapshot()).contains("TEST MERCHANT ONE");
    }

    @Test
    void theResultExposesTheParsersIssuerId() throws Exception {
        byte[] bytes = StatementPdfFixtures.cardWithPeriodAndNumberButNoSummary();

        assertThat(parser.parse(bytes, null).issuerId()).isEqualTo(StatementParseEngine.parse(bytes, null, null).meta().issuerId);
    }

    @Test
    void parserVersionStartsAtOne() {
        assertThat(StatementParseEngine.PARSER_VERSION).isEqualTo(1);
    }

    @Test
    void aLockedFileFailsWithKindLockedAndItsUnchangedMessage() throws Exception {
        StatementExtractionResult result = parser.parse(ProtectedPdfs.pdf("secret1"), null);

        assertThat(result.success()).isFalse();
        assertThat(result.failureKind()).isEqualTo(Kind.LOCKED);
        assertThat(result.failureReason()).isEqualTo("ERROR: PDF is password-protected and the password is missing or wrong.");
        assertThat(result.draft()).isNull();
        assertThat(result.issuerId()).isNull();
    }

    @Test
    void otherParseFailuresCarryTheirOwnKind() {
        assertThat(parser.parse("plain text file".getBytes(), null).failureKind()).isEqualTo(Kind.UNSUPPORTED_TYPE);
        assertThat(parser.parse(new byte[0], null).failureKind()).isEqualTo(Kind.EMPTY_FILE);
        assertThat(parser.parse(null, null).failureKind()).isEqualTo(Kind.EMPTY_FILE);
    }

    @Test
    void aCorruptXlsxIsKindUnreadableWithTheExcelMessage() {
        byte[] corruptXlsx = {'P', 'K', 3, 4, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12};

        StatementExtractionResult result = parser.parse(corruptXlsx, null);

        assertThat(result.success()).isFalse();
        assertThat(result.failureKind()).isEqualTo(Kind.UNREADABLE);
        assertThat(result.failureReason()).isEqualTo("ERROR: failed to read Excel file.");
    }

    @Test
    void theOldFailureFactoryAndConstructorLeaveTheKindAndIssuerUnset() {
        StatementExtractionResult f = StatementExtractionResult.failure("x");
        assertThat(f.failureKind()).isNull();
        assertThat(f.issuerId()).isNull();
        StatementExtractionResult legacy = new StatementExtractionResult(false, java.util.List.of(), null, null, null, null, "y", null);
        assertThat(legacy.failureKind()).isNull();
        assertThat(StatementExtractionResult.failure("x", Kind.NO_TABLE).failureKind()).isEqualTo(Kind.NO_TABLE);
    }

    @Test
    void kindAndIssuerSurviveTheWithMethods() {
        StatementExtractionResult r = StatementExtractionResult.failure("x", Kind.NO_TABLE).withIssuerId("hdfc").withSummaryText("s");
        assertThat(r.failureKind()).isEqualTo(Kind.NO_TABLE);
        assertThat(r.issuerId()).isEqualTo("hdfc");
        assertThat(r.summaryText()).isEqualTo("s");
    }
}
