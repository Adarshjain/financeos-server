package com.financeos.domain.transaction;

import com.financeos.gmail.reconcile.ParsedStatementLine;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.HashSet;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Bank narration vs bank narration must match exactly (same date, same text ignoring case/whitespace);
 * any pair involving alert or manual text keeps the loose amount/date/similarity rules.
 */
class TransactionMatcherTest {

    private static final LocalDate DAY = LocalDate.of(2026, 10, 2);
    private static final String RAMESH_1 = "UPI DR 412345678901 RAMESH KUMAR SBIN ramesh@okaxis PAYMENT FROM PHONE";
    private static final String RAMESH_2 = "UPI DR 498765432109 RAMESH KUMAR SBIN ramesh@okaxis PAYMENT FROM PHONE";

    private final TransactionMatcher matcher = new TransactionMatcher();

    private static Transaction txn(TransactionSource source, LocalDate date, String sourcedDescription) {
        Transaction t = new Transaction();
        t.setId(UUID.randomUUID());
        t.setSource(source);
        t.setDate(date);
        t.setAmount(new BigDecimal("500.00"));
        t.setType(TransactionType.DEBIT);
        t.setSourcedDescription(sourcedDescription);
        return t;
    }

    private static ParsedStatementLine line(LocalDate date, String description) {
        return new ParsedStatementLine(date, new BigDecimal("-500.00"), "DEBIT", description, null, null);
    }

    private Transaction bestMatch(ParsedStatementLine line, Transaction candidate) {
        return matcher.findBestMatch(line, List.of(candidate), 3, new HashSet<>());
    }

    // --- areDuplicates (statement upload) ---

    @Test
    void bankRowsWithIdenticalNarrationOnTheSameDayAreDuplicates() {
        assertThat(matcher.areDuplicates(
                txn(TransactionSource.file_upload, DAY, RAMESH_1),
                txn(TransactionSource.file_upload, DAY, RAMESH_1), 0)).isTrue();
    }

    @Test
    void bankNarrationComparisonIgnoresCaseAndWhitespace() {
        assertThat(matcher.areDuplicates(
                txn(TransactionSource.file_upload, DAY, "UPI DR 412345678901  RAMESH\nKUMAR"),
                txn(TransactionSource.gmail_statement, DAY, "upi dr 412345678901 ramesh kumar"), 0)).isTrue();
    }

    @Test
    void bankRowsDifferingOnlyInReferenceAreNotDuplicates() {
        // Token similarity of these two is 0.8, which the old 0.7 rule flagged as a duplicate.
        assertThat(matcher.calculateSimilarity(RAMESH_1, RAMESH_2)).isGreaterThanOrEqualTo(0.7);
        assertThat(matcher.areDuplicates(
                txn(TransactionSource.file_upload, DAY, RAMESH_1),
                txn(TransactionSource.file_upload, DAY, RAMESH_2), 0)).isFalse();
    }

    @Test
    void bankRowsWithIdenticalNarrationOnDifferentDaysAreNotDuplicatesEvenInsideTheWindow() {
        assertThat(matcher.areDuplicates(
                txn(TransactionSource.file_upload, DAY, RAMESH_1),
                txn(TransactionSource.gmail_statement, DAY.plusDays(1), RAMESH_1), 3)).isFalse();
    }

    @Test
    void uploadAgainstManualEntryKeepsTheSimilarityRule() {
        assertThat(matcher.areDuplicates(
                txn(TransactionSource.file_upload, DAY, RAMESH_1),
                txn(TransactionSource.manual, DAY, RAMESH_2), 0)).isTrue();
    }

    // --- findBestMatch (statement reconcile) ---

    @Test
    void lineMatchesAnEarlierStatementRowWithTheSameDateAndNarration() {
        Transaction prior = txn(TransactionSource.gmail_statement, DAY, RAMESH_1.toLowerCase());
        assertThat(bestMatch(line(DAY, RAMESH_1), prior)).isSameAs(prior);
    }

    @Test
    void lineDoesNotMatchAStatementRowWithADifferentReference() {
        assertThat(bestMatch(line(DAY, RAMESH_2), txn(TransactionSource.gmail_statement, DAY, RAMESH_1))).isNull();
    }

    @Test
    void lineDoesNotMatchAStatementRowWithTheSameNarrationOnAnotherDay() {
        assertThat(bestMatch(line(DAY, RAMESH_1), txn(TransactionSource.file_upload, DAY.minusDays(2), RAMESH_1))).isNull();
    }

    @Test
    void lineDoesNotMatchAStatementRowWithoutANarration() {
        Transaction prior = txn(TransactionSource.gmail_statement, DAY, null);
        assertThat(bestMatch(line(DAY, RAMESH_1), prior)).isNull();
    }

    @Test
    void lineStillMatchesAnAlertByAmountAndDateWindowWhateverItsText() {
        Transaction alert = txn(TransactionSource.gmail_transaction_alert, DAY.minusDays(2), "Ramesh Kumar");
        assertThat(bestMatch(line(DAY, RAMESH_1), alert)).isSameAs(alert);
    }

    @Test
    void lineStillMatchesAManualEntryByAmountAndDateWindowWhateverItsText() {
        Transaction manual = txn(TransactionSource.manual, DAY.plusDays(1), null);
        manual.setDescription("paid ramesh");
        assertThat(bestMatch(line(DAY, RAMESH_1), manual)).isSameAs(manual);
    }
}
