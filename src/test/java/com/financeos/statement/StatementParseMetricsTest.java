package com.financeos.statement;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.financeos.domain.statement.StatementDraft;
import com.financeos.domain.statement.StatementImportGate;
import com.financeos.domain.statement.StatementVerdict;
import com.financeos.gmail.reconcile.ParsedStatementLine;
import com.financeos.gmail.reconcile.StatementExtractionResult;
import com.financeos.statement.parser.StatementParseException.Kind;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/** One count per parsed file, by outcome, with only closed-set tag values. */
class StatementParseMetricsTest {

    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();
    private final StatementParseMetrics metrics = new StatementParseMetrics(registry);

    private static StatementDraft draft(String type, StatementVerdict verdict) {
        return new StatementDraft(type, LocalDate.of(2026, 9, 1), LocalDate.of(2026, 9, 30), null, null, "Free Text Bank Name",
                "XXXX1234", 1, 0, BigDecimal.TEN, BigDecimal.ZERO, "heuristic", null, null, verdict, null);
    }

    private static StatementExtractionResult parsed(String type, StatementVerdict verdict, String issuer) {
        ParsedStatementLine line = new ParsedStatementLine(LocalDate.of(2026, 9, 5), BigDecimal.TEN, "DEBIT", "Txn", null, null);
        return StatementExtractionResult.success(List.of(line), "XXXX1234", LocalDate.of(2026, 9, 1), LocalDate.of(2026, 9, 30),
                draft(type, verdict)).withIssuerId(issuer);
    }

    private double count(String path, String outcome, String kind, String verdict, String issuer, String type) {
        var c = registry.find("financeos.statement.parse").tags("path", path, "outcome", outcome, "kind", kind,
                "verdict", verdict, "issuer", issuer, "type", type).counter();
        return c == null ? 0 : c.count();
    }

    private double total() {
        return registry.find("financeos.statement.parse").counters().stream().mapToDouble(c -> c.count()).sum();
    }

    @Test
    void aParsedFileCountsAsParsedWithItsVerdictIssuerAndType() {
        metrics.record("upload", parsed("credit_card", StatementVerdict.AUTO_INGEST, "hdfc"), Optional.empty());

        assertThat(count("upload", "parsed", "-", "AUTO_INGEST", "hdfc", "credit_card")).isEqualTo(1);
        assertThat(total()).isEqualTo(1);
    }

    @Test
    void theVerdictAndTypeTagsFollowTheDraft() {
        metrics.record("gmail", parsed("bank_account", StatementVerdict.NEEDS_REVIEW, "sbi"), Optional.empty());
        metrics.record("gmail", parsed("bank_account", StatementVerdict.REJECTED, "sbi"), Optional.empty());

        assertThat(count("gmail", "parsed", "-", "NEEDS_REVIEW", "sbi", "bank_account")).isEqualTo(1);
        assertThat(count("gmail", "parsed", "-", "REJECTED", "sbi", "bank_account")).isEqualTo(1);
    }

    @Test
    void aGateBlockCountsAsBlockedWithTheReasonAsKind() {
        StatementImportGate.Blocked blocked = new StatementImportGate.Blocked(StatementImportGate.Reason.CONTRADICTED, "m");
        metrics.record("upload", parsed("bank_account", StatementVerdict.REJECTED, "axis"), Optional.of(blocked));
        metrics.record("gmail", parsed("bank_account", StatementVerdict.AUTO_INGEST, "axis"),
                Optional.of(new StatementImportGate.Blocked(StatementImportGate.Reason.NOT_A_STATEMENT, "m")));

        assertThat(count("upload", "blocked", "CONTRADICTED", "REJECTED", "axis", "bank_account")).isEqualTo(1);
        assertThat(count("gmail", "blocked", "NOT_A_STATEMENT", "AUTO_INGEST", "axis", "bank_account")).isEqualTo(1);
    }

    @Test
    void aFileWithNoLinesCountsAsEmptyEvenWhenTheGateWouldBlockIt() {
        StatementExtractionResult noLines = StatementExtractionResult.success(List.of(), "XXXX1234", null, null,
                draft("credit_card", StatementVerdict.AUTO_INGEST)).withIssuerId("icici");

        metrics.record("upload", noLines, Optional.of(new StatementImportGate.Blocked(StatementImportGate.Reason.NOT_A_STATEMENT, "m")));

        assertThat(count("upload", "empty", "-", "AUTO_INGEST", "icici", "credit_card")).isEqualTo(1);
    }

    @Test
    void aParseFailureCountsAsFailedWithTheKindAndNoVerdictIssuerOrType() {
        metrics.record("upload", StatementExtractionResult.failure("ERROR: ...", Kind.NO_TABLE), Optional.empty());
        metrics.record("gmail", StatementExtractionResult.failure("ERROR: pw", Kind.LOCKED), Optional.empty());

        assertThat(count("upload", "failed", "NO_TABLE", "-", "unknown", "-")).isEqualTo(1);
        assertThat(count("gmail", "failed", "LOCKED", "-", "unknown", "-")).isEqualTo(1);
    }

    @Test
    void aFailureWithoutAKindIsOther() {
        metrics.record("upload", StatementExtractionResult.failure("who knows"), Optional.empty());

        assertThat(count("upload", "failed", "OTHER", "-", "unknown", "-")).isEqualTo(1);
    }

    @Test
    void aMissingOrUnknownIssuerIsUnknown() {
        metrics.record("upload", parsed("credit_card", StatementVerdict.AUTO_INGEST, null), Optional.empty());

        assertThat(count("upload", "parsed", "-", "AUTO_INGEST", "unknown", "credit_card")).isEqualTo(1);
    }

    @Test
    void anIssuerThatIsNotAShortIdIsNeverUsedAsATagValue() {
        for (String bad : new String[]{"HDFC Bank Ltd, Mumbai", "xxxx 1234", "", "  ", "a".repeat(25), "hdfc/../x"}) {
            metrics.record("upload", parsed("credit_card", StatementVerdict.AUTO_INGEST, bad), Optional.empty());
        }

        assertThat(count("upload", "parsed", "-", "AUTO_INGEST", "unknown", "credit_card")).isEqualTo(6);
    }

    @Test
    void anUpperCaseIdIsLowerCasedAndAnIdWithUnderscoreIsKept() {
        metrics.record("upload", parsed("credit_card", StatementVerdict.AUTO_INGEST, "HDFC"), Optional.empty());
        metrics.record("upload", parsed("credit_card", StatementVerdict.AUTO_INGEST, "idfc_first"), Optional.empty());

        assertThat(count("upload", "parsed", "-", "AUTO_INGEST", "hdfc", "credit_card")).isEqualTo(1);
        assertThat(count("upload", "parsed", "-", "AUTO_INGEST", "idfc_first", "credit_card")).isEqualTo(1);
    }

    @Test
    void aTypeOutsideTheTwoKnownOnesIsADash() {
        metrics.record("upload", parsed("something_else", StatementVerdict.AUTO_INGEST, "hdfc"), Optional.empty());

        assertThat(count("upload", "parsed", "-", "AUTO_INGEST", "hdfc", "-")).isEqualTo(1);
    }

    @Test
    void aDraftWithoutAVerdictOrAtAllCountsWithDashes() {
        StatementExtractionResult noVerdict = StatementExtractionResult.success(
                List.of(new ParsedStatementLine(LocalDate.of(2026, 9, 5), BigDecimal.TEN, "DEBIT", "Txn", null, null)),
                "1", null, null, draft("bank_account", null));
        StatementExtractionResult noDraft = StatementExtractionResult.success(
                List.of(new ParsedStatementLine(LocalDate.of(2026, 9, 5), BigDecimal.TEN, "DEBIT", "Txn", null, null)),
                "1", null, null, null);

        metrics.record("upload", noVerdict, Optional.empty());
        metrics.record("upload", noDraft, Optional.empty());

        assertThat(count("upload", "parsed", "-", "-", "unknown", "bank_account")).isEqualTo(1);
        assertThat(count("upload", "parsed", "-", "-", "unknown", "-")).isEqualTo(1);
    }

    @Test
    void anUnknownPathIsRecordedAsUpload() {
        metrics.record("whatever", parsed("credit_card", StatementVerdict.AUTO_INGEST, "hdfc"), Optional.empty());

        assertThat(count("upload", "parsed", "-", "AUTO_INGEST", "hdfc", "credit_card")).isEqualTo(1);
    }

    @Test
    void theTagsNeverCarryTheBankNameAccountNumberOrFileText() {
        metrics.record("upload", parsed("credit_card", StatementVerdict.AUTO_INGEST, "hdfc"), Optional.empty());

        registry.find("financeos.statement.parse").counters().forEach(c ->
                c.getId().getTags().forEach(t -> assertThat(t.getValue()).doesNotContain("Free Text").doesNotContain("1234")));
    }

    @Test
    void recordingNeverThrows() {
        assertThatCode(() -> metrics.record("upload", null, null)).doesNotThrowAnyException();
        assertThatCode(() -> metrics.record(null, parsed("credit_card", StatementVerdict.AUTO_INGEST, "hdfc"), null)).doesNotThrowAnyException();

        MeterRegistry broken = mock(MeterRegistry.class);
        when(broken.config()).thenThrow(new IllegalStateException("registry down"));
        assertThatCode(() -> new StatementParseMetrics(broken).record("upload",
                parsed("credit_card", StatementVerdict.AUTO_INGEST, "hdfc"), Optional.empty())).doesNotThrowAnyException();
    }
}
