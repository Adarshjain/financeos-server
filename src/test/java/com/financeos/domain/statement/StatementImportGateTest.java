package com.financeos.domain.statement;

import static org.assertj.core.api.Assertions.assertThat;

import com.financeos.gmail.reconcile.StatementExtractionResult;
import com.financeos.gmail.reconcile.StatementParser;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class StatementImportGateTest {

    private static final LocalDate END = LocalDate.of(2026, 9, 30);

    private static StatementDraft draft(StatementVerdict verdict, String parseMode, Boolean checksumOk, Boolean cardChecksOk) {
        return new StatementDraft("bank_account", END.minusDays(29), END, null, null, "Bank", "1234", 2, 0,
                BigDecimal.TEN, BigDecimal.ZERO, parseMode, null, checksumOk, verdict, null, cardChecksOk);
    }

    private static StatementExtractionResult result(String number, LocalDate end, StatementDraft draft) {
        return StatementExtractionResult.success(List.of(), number, end == null ? null : end.minusDays(29), end, draft);
    }

    private static Optional<StatementImportGate.Blocked> check(StatementDraft draft) {
        return StatementImportGate.check(result("1234", END, draft));
    }

    @Test
    void neitherNumberNorPeriodEndIsNotAStatement() {
        Optional<StatementImportGate.Blocked> b = StatementImportGate.check(result(null, null, draft(StatementVerdict.AUTO_INGEST, "heuristic", null, null)));

        assertThat(b).isPresent();
        assertThat(b.get().reason()).isEqualTo(StatementImportGate.Reason.NOT_A_STATEMENT);
        assertThat(b.get().message()).isEqualTo("This file doesn't look like an account statement, so nothing was imported.");
    }

    @Test
    void aBlankNumberCountsAsNoNumber() {
        assertThat(StatementImportGate.check(result("  ", null, null))).isPresent();
    }

    @Test
    void aNumberAloneIsEnough() {
        assertThat(StatementImportGate.check(result("1234", null, draft(StatementVerdict.NEEDS_REVIEW, "heuristic", null, null)))).isEmpty();
    }

    @Test
    void aPeriodEndAloneIsEnough() {
        assertThat(StatementImportGate.check(result(null, END, draft(StatementVerdict.NEEDS_REVIEW, "heuristic", null, null)))).isEmpty();
    }

    @Test
    void notAStatementComesBeforeContradicted() {
        Optional<StatementImportGate.Blocked> b = StatementImportGate.check(
                result(null, null, draft(StatementVerdict.REJECTED, "balance_chain", Boolean.FALSE, Boolean.FALSE)));

        assertThat(b.get().reason()).isEqualTo(StatementImportGate.Reason.NOT_A_STATEMENT);
    }

    @Test
    void rejectedWithAFailedChecksumIsContradicted() {
        Optional<StatementImportGate.Blocked> b = check(draft(StatementVerdict.REJECTED, "opening_closing", Boolean.FALSE, null));

        assertThat(b.get().reason()).isEqualTo(StatementImportGate.Reason.CONTRADICTED);
        assertThat(b.get().message()).isEqualTo("This statement's rows don't add up to its own totals, so nothing was imported.");
    }

    @Test
    void rejectedInBalanceChainModeIsContradicted() {
        assertThat(check(draft(StatementVerdict.REJECTED, StatementParser.MODE_BALANCE_CHAIN, null, null)).get().reason())
                .isEqualTo(StatementImportGate.Reason.CONTRADICTED);
    }

    @Test
    void aFailedCardCheckAloneIsNotContradiction() {
        // The parser reports cardChecksOk FALSE for any card parse without a passing check, so it is weak evidence.
        assertThat(check(draft(StatementVerdict.REJECTED, "heuristic", null, Boolean.FALSE))).isEmpty();
    }

    @Test
    void rejectedWithNothingToCheckPasses() {
        assertThat(check(draft(StatementVerdict.REJECTED, "heuristic", null, null))).isEmpty();
        assertThat(check(draft(StatementVerdict.REJECTED, "opening_closing", null, null))).isEmpty();
    }

    @Test
    void rejectedWithPassingChecksOnlyPasses() {
        assertThat(check(draft(StatementVerdict.REJECTED, "heuristic", Boolean.TRUE, Boolean.TRUE))).isEmpty();
    }

    @Test
    void needsReviewAndAutoIngestAlwaysPassEvenWithContradictingFigures() {
        assertThat(check(draft(StatementVerdict.NEEDS_REVIEW, "balance_chain", Boolean.FALSE, Boolean.FALSE))).isEmpty();
        assertThat(check(draft(StatementVerdict.AUTO_INGEST, "balance_chain", Boolean.FALSE, Boolean.FALSE))).isEmpty();
    }

    @Test
    void aResultWithoutADraftPasses() {
        assertThat(check(null)).isEmpty();
    }
}
