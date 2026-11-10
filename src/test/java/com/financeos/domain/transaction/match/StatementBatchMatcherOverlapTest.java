package com.financeos.domain.transaction.match;

import com.financeos.domain.account.Account;
import com.financeos.domain.account.AccountType;
import com.financeos.domain.statement.Statement;
import com.financeos.domain.statement.StatementVerdict;
import com.financeos.domain.transaction.ReviewStatusManager;
import com.financeos.domain.transaction.ReviewType;
import com.financeos.domain.transaction.Transaction;
import com.financeos.domain.transaction.TransactionSource;
import com.financeos.domain.transaction.TransactionType;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** Two overlapping statements of one account (a yearly upload and a monthly mail): every line shared, none duplicated. */
class StatementBatchMatcherOverlapTest {

    private final MatchFixture fixture = new MatchFixture();
    private final ReviewStatusManager review = new ReviewStatusManager();

    private static Account account() {
        Account a = new Account();
        a.setId(UUID.randomUUID());
        a.setType(AccountType.bank_account);
        return a;
    }

    private Statement statement(Account a, LocalDate start, LocalDate end, StatementVerdict verdict) {
        Statement s = new Statement();
        s.setId(UUID.randomUUID());
        s.setPeriodStart(start);
        s.setPeriodEnd(end);
        s.setVerdict(verdict);
        fixture.period(a.getId(), s);
        return s;
    }

    private static StatementBatchMatcher.Line line(int index, LocalDate date, String amount, TransactionType type, String text) {
        return new StatementBatchMatcher.Line(index, date, new BigDecimal(amount), type, text, null, null, null);
    }

    private static StatementBatchMatcher.Line debit(int index, LocalDate date, String amount, String text) {
        return line(index, date, amount, TransactionType.DEBIT, text);
    }

    private static StatementBatchMatcher.Line credit(int index, LocalDate date, String amount, String text) {
        return line(index, date, amount, TransactionType.CREDIT, text);
    }

    private StatementBatchMatcher.LineCreator creator(Account account) {
        return unpaired -> {
            Map<Integer, Transaction> made = new LinkedHashMap<>();
            for (StatementBatchMatcher.Line l : unpaired) {
                Transaction t = new Transaction();
                t.setAccount(account);
                t.setDate(l.date());
                t.setAmount(l.amount());
                t.setType(l.type());
                t.setSource(TransactionSource.file_upload);
                t.setSourcedDescription(l.description());
                review.transitionTo(t, ReviewType.AUTO_REVIEWED);
                made.put(l.lineIndex(), fixture.store(t));
            }
            return made;
        };
    }

    private StatementBatchMatcher.Result run(Statement s, Account a, StatementBatchMatcher.Line... lines) {
        return fixture.matcher.match(s, a, List.of(lines), creator(a));
    }

    private UUID lineTxn(Statement s, int lineIndex) {
        return fixture.links.stream()
                .filter(l -> l.getId().getStatementId().equals(s.getId()) && l.getLineIndex() == lineIndex)
                .map(l -> l.getId().getTransactionId()).findFirst().orElse(null);
    }

    // ------------------------------------------------------------ A: a complete print's references only rank

    private static final LocalDate AUG_12 = LocalDate.of(2025, 8, 12);
    private static final String RAVI_OWN = "UPI-RAVI KUMAR-ravi@okicici-ICIC0001234-523412345678-PAY";
    private static final String SWIGGY_OWN = "UPI-SWIGGY-swiggy@icici-ICIC0000001-523487654321-ORDER";
    // The monthly print gave each line the other row's reference (the parser carried the previous row's Ref).
    private static final String RAVI_SWAPPED = "UPI-RAVI KUMAR-ravi@okicici-ICIC0001234-523487654321-PAY";
    private static final String SWIGGY_SWAPPED = "UPI-SWIGGY-swiggy@icici-ICIC0000001-523412345678-ORDER";

    @Test
    void aCompletePrintWhoseLinesCarrySwappedReferencesSharesTheOriginalRows() {
        Account hdfc = account();
        Statement yearly = statement(hdfc, LocalDate.of(2025, 4, 1), LocalDate.of(2026, 3, 31), StatementVerdict.AUTO_INGEST);
        Statement august = statement(hdfc, LocalDate.of(2025, 8, 1), LocalDate.of(2025, 8, 31), StatementVerdict.AUTO_INGEST);
        run(yearly, hdfc, debit(0, AUG_12, "300.00", RAVI_OWN), debit(1, AUG_12, "500.00", SWIGGY_OWN));

        StatementBatchMatcher.Result r = run(august, hdfc,
                debit(0, AUG_12, "300.00", RAVI_SWAPPED), debit(1, AUG_12, "500.00", SWIGGY_SWAPPED));

        assertThat(r.overlapAttached()).isEqualTo(2);
        assertThat(r.created()).isZero();
        assertThat(fixture.txns).hasSize(2);
        assertThat(lineTxn(august, 0)).isEqualTo(lineTxn(yearly, 0));
        assertThat(lineTxn(august, 1)).isEqualTo(lineTxn(yearly, 1));
    }

    @Test
    void aStatementNeedingReviewKeepsTheReferenceVeto() {
        Account hdfc = account();
        Statement yearly = statement(hdfc, LocalDate.of(2025, 4, 1), LocalDate.of(2026, 3, 31), StatementVerdict.AUTO_INGEST);
        Statement august = statement(hdfc, LocalDate.of(2025, 8, 1), LocalDate.of(2025, 8, 31), StatementVerdict.NEEDS_REVIEW);
        run(yearly, hdfc, debit(0, AUG_12, "300.00", RAVI_OWN), debit(1, AUG_12, "500.00", SWIGGY_OWN));

        StatementBatchMatcher.Result r = run(august, hdfc,
                debit(0, AUG_12, "300.00", RAVI_SWAPPED), debit(1, AUG_12, "500.00", SWIGGY_SWAPPED));

        assertThat(r.overlapAttached()).isZero();
        assertThat(r.created()).isEqualTo(2);
        assertThat(fixture.txns).hasSize(4);
    }

    @Test
    void aCompletePrintStillPairsSameDaySameAmountLinesOneToOne() {
        Account hdfc = account();
        Statement yearly = statement(hdfc, LocalDate.of(2025, 4, 1), LocalDate.of(2026, 3, 31), StatementVerdict.AUTO_INGEST);
        Statement august = statement(hdfc, LocalDate.of(2025, 8, 1), LocalDate.of(2025, 8, 31), StatementVerdict.AUTO_INGEST);
        run(yearly, hdfc,
                debit(0, AUG_12, "2.00", "UPI-LOUNGE-523400000001-CHECK"),
                debit(1, AUG_12, "2.00", "UPI-CARD CHECK-523400000002-VERIFY"));

        StatementBatchMatcher.Result r = run(august, hdfc,
                debit(0, AUG_12, "2.00", "UPI-LOUNGE-523400000002-CHECK"),
                debit(1, AUG_12, "2.00", "UPI-CARD CHECK-523400000001-VERIFY"),
                debit(2, AUG_12, "2.00", "UPI-THIRD-523400000003-PAY"));

        assertThat(r.overlapAttached()).isEqualTo(2);
        assertThat(r.created()).isEqualTo(1);
        assertThat(fixture.txns).hasSize(3);
        assertThat(List.of(lineTxn(august, 0), lineTxn(august, 1)))
                .containsExactlyInAnyOrder(lineTxn(yearly, 0), lineTxn(yearly, 1));
    }

    // ------------------------------------------------------------ B: interest of the 1st printed in the month before

    private static final LocalDate JUL_1 = LocalDate.of(2025, 7, 1);

    @Test
    void theFirstsInterestInTheJuneStatementSharesTheYearlyRow() {
        Account hdfc = account();
        Statement yearly = statement(hdfc, LocalDate.of(2025, 4, 1), LocalDate.of(2026, 3, 31), StatementVerdict.AUTO_INGEST);
        Statement june = statement(hdfc, LocalDate.of(2025, 6, 1), LocalDate.of(2025, 6, 30), StatementVerdict.AUTO_INGEST);
        run(yearly, hdfc, credit(0, JUL_1, "240.00", "Interest paid till 30-JUN-2025"));

        StatementBatchMatcher.Result r = run(june, hdfc, credit(0, JUL_1, "240.00", "INT PAID TILL 30/06/25"));

        assertThat(r.overlapAttached()).isEqualTo(1);
        assertThat(r.created()).isZero();
        assertThat(fixture.txns).hasSize(1);
        assertThat(lineTxn(june, 0)).isEqualTo(lineTxn(yearly, 0));
    }

    @Test
    void theYearlyInterestLineSharesTheRowTheJuneStatementPrintedOnTheFirst() {
        Account hdfc = account();
        Statement yearly = statement(hdfc, LocalDate.of(2025, 4, 1), LocalDate.of(2026, 3, 31), StatementVerdict.AUTO_INGEST);
        Statement june = statement(hdfc, LocalDate.of(2025, 6, 1), LocalDate.of(2025, 6, 30), StatementVerdict.AUTO_INGEST);
        run(june, hdfc, credit(0, JUL_1, "240.00", "INT PAID TILL 30/06/25"));

        StatementBatchMatcher.Result r = run(yearly, hdfc, credit(0, JUL_1, "240.00", "Interest paid till 30-JUN-2025"));

        assertThat(r.overlapAttached()).isEqualTo(1);
        assertThat(r.created()).isZero();
        assertThat(fixture.txns).hasSize(1);
        assertThat(lineTxn(yearly, 0)).isEqualTo(lineTxn(june, 0));
    }

    // ------------------------------------------------------------ C: Equitas prints a day apart

    private static final String EQUITAS_IMPS = "IMPS/P2A/028912345678/ADARSH JAIN/HDFC0000001";

    @Test
    void identicalTextWithALongReferenceADayApartIsTheSameLine() {
        // Equitas: the yearly upload prints the IMPS on 15/10, the October mail statement on 16/10.
        Account equitas = account();
        Statement first = statement(equitas, LocalDate.of(2020, 4, 1), LocalDate.of(2021, 3, 31), StatementVerdict.AUTO_INGEST);
        Statement second = statement(equitas, LocalDate.of(2020, 10, 1), LocalDate.of(2020, 10, 31), StatementVerdict.AUTO_INGEST);
        run(first, equitas, debit(0, LocalDate.of(2020, 10, 15), "10000.00", EQUITAS_IMPS));

        StatementBatchMatcher.Result r = run(second, equitas, debit(0, LocalDate.of(2020, 10, 16), "10000.00", EQUITAS_IMPS));

        assertThat(r.overlapAttached()).isEqualTo(1);
        assertThat(r.created()).isZero();
        assertThat(lineTxn(second, 0)).isEqualTo(lineTxn(first, 0));
    }

    @Test
    void aDailyMandateOnConsecutiveStatementsEdgesStaysTwoPayments() {
        // NACH with a fixed mandate number: day 1 closes the previous statement, day 2 opens the next one.
        Account equitas = account();
        Statement previous = statement(equitas, LocalDate.of(2020, 9, 2), LocalDate.of(2020, 10, 1), StatementVerdict.AUTO_INGEST);
        Statement next = statement(equitas, LocalDate.of(2020, 10, 2), LocalDate.of(2020, 11, 1), StatementVerdict.AUTO_INGEST);
        String nach = "NACH DR/ICIC7021807230018123/MUTUAL FUND SIP";
        run(previous, equitas, debit(0, LocalDate.of(2020, 10, 1), "500.00", nach));

        StatementBatchMatcher.Result r = run(next, equitas, debit(0, LocalDate.of(2020, 10, 2), "500.00", nach));

        assertThat(r.overlapAttached()).isZero();
        assertThat(r.created()).isEqualTo(1);
        assertThat(fixture.txns).hasSize(2);
    }

    @Test
    void identicalTextWithoutALongReferenceADayApartStaysSeparate() {
        Account equitas = account();
        Statement first = statement(equitas, LocalDate.of(2020, 10, 1), LocalDate.of(2020, 10, 31), StatementVerdict.AUTO_INGEST);
        Statement second = statement(equitas, LocalDate.of(2020, 10, 1), LocalDate.of(2020, 12, 31), StatementVerdict.AUTO_INGEST);
        run(first, equitas, debit(0, LocalDate.of(2020, 10, 15), "10000.00", "UPI-KARTHIK S"));

        StatementBatchMatcher.Result r = run(second, equitas, debit(0, LocalDate.of(2020, 10, 16), "10000.00", "UPI-KARTHIK S"));

        assertThat(r.overlapAttached()).isZero();
        assertThat(r.created()).isEqualTo(1);
        assertThat(fixture.txns).hasSize(2);
    }

    @Test
    void identicalTextWithALongReferenceTwoDaysApartStaysSeparate() {
        Account equitas = account();
        Statement first = statement(equitas, LocalDate.of(2020, 10, 1), LocalDate.of(2020, 10, 31), StatementVerdict.AUTO_INGEST);
        Statement second = statement(equitas, LocalDate.of(2020, 10, 1), LocalDate.of(2020, 12, 31), StatementVerdict.AUTO_INGEST);
        run(first, equitas, debit(0, LocalDate.of(2020, 10, 15), "10000.00", EQUITAS_IMPS));

        StatementBatchMatcher.Result r = run(second, equitas, debit(0, LocalDate.of(2020, 10, 17), "10000.00", EQUITAS_IMPS));

        assertThat(r.overlapAttached()).isZero();
        assertThat(r.created()).isEqualTo(1);
    }

    @Test
    void interestPrintedOnTheFirstAfterThePeriodSharesTheRowOfTheThirtieth() {
        Account equitas = account();
        Statement quarter = statement(equitas, LocalDate.of(2020, 7, 1), LocalDate.of(2020, 9, 30), StatementVerdict.AUTO_INGEST);
        Statement september = statement(equitas, LocalDate.of(2020, 9, 1), LocalDate.of(2020, 9, 30), StatementVerdict.AUTO_INGEST);
        LocalDate sep30 = LocalDate.of(2020, 9, 30);
        LocalDate oct1 = LocalDate.of(2020, 10, 1);
        run(quarter, equitas,
                credit(0, sep30, "27.00", "Int.Pd:01-07-2020 to 30-09-2020"),
                credit(1, sep30, "4149.00", "Int.Pd:01-07-2020 to 30-09-2020"));

        StatementBatchMatcher.Result r = run(september, equitas,
                credit(0, oct1, "27.00", "Int.Pd:01-07-2020 to 30-09-2020"),
                credit(1, oct1, "4149.00", "Int.Pd:01-07-2020 to 30-09-2020"));

        assertThat(r.overlapAttached()).isEqualTo(2);
        assertThat(r.created()).isZero();
        assertThat(fixture.txns).hasSize(2);
        assertThat(lineTxn(september, 0)).isEqualTo(lineTxn(quarter, 0));
        assertThat(lineTxn(september, 1)).isEqualTo(lineTxn(quarter, 1));
    }

    @Test
    void theThirtiethsInterestLineSharesTheRowTheSeptemberStatementPrintedOnTheFirst() {
        // Reverse order: the September mail statement (01/10 dates) came first, the quarter's upload second.
        Account equitas = account();
        Statement quarter = statement(equitas, LocalDate.of(2020, 7, 1), LocalDate.of(2020, 9, 30), StatementVerdict.AUTO_INGEST);
        Statement september = statement(equitas, LocalDate.of(2020, 9, 1), LocalDate.of(2020, 9, 30), StatementVerdict.AUTO_INGEST);
        LocalDate sep30 = LocalDate.of(2020, 9, 30);
        LocalDate oct1 = LocalDate.of(2020, 10, 1);
        run(september, equitas,
                credit(0, oct1, "27.00", "Int.Pd:01-07-2020 to 30-09-2020"),
                credit(1, oct1, "4149.00", "Int.Pd:01-07-2020 to 30-09-2020"));

        StatementBatchMatcher.Result r = run(quarter, equitas,
                credit(0, sep30, "27.00", "Int.Pd:01-07-2020 to 30-09-2020"),
                credit(1, sep30, "4149.00", "Int.Pd:01-07-2020 to 30-09-2020"));

        assertThat(r.overlapAttached()).isEqualTo(2);
        assertThat(r.created()).isZero();
        assertThat(fixture.txns).hasSize(2);
        assertThat(lineTxn(quarter, 0)).isEqualTo(lineTxn(september, 0));
        assertThat(lineTxn(quarter, 1)).isEqualTo(lineTxn(september, 1));
    }

    @Test
    void interestPrintedOnTheFirstPastTheJunePeriodSharesTheYearEndRow() {
        Account hdfc = account();
        Statement fy = statement(hdfc, LocalDate.of(2024, 7, 1), LocalDate.of(2025, 6, 30), StatementVerdict.AUTO_INGEST);
        Statement june = statement(hdfc, LocalDate.of(2025, 6, 1), LocalDate.of(2025, 6, 30), StatementVerdict.AUTO_INGEST);
        run(fy, hdfc, credit(0, LocalDate.of(2025, 6, 30), "240.00", "Interest paid till 30-JUN-2025"));

        StatementBatchMatcher.Result r = run(june, hdfc, credit(0, JUL_1, "240.00", "INT PAID TILL 30/06/25"));

        assertThat(r.overlapAttached()).isEqualTo(1);
        assertThat(r.created()).isZero();
        assertThat(fixture.txns).hasSize(1);
        assertThat(lineTxn(june, 0)).isEqualTo(lineTxn(fy, 0));
    }

    @Test
    void theYearEndInterestLineSharesTheRowTheJuneStatementPrintedOnTheFirst() {
        Account hdfc = account();
        Statement fy = statement(hdfc, LocalDate.of(2024, 7, 1), LocalDate.of(2025, 6, 30), StatementVerdict.AUTO_INGEST);
        Statement june = statement(hdfc, LocalDate.of(2025, 6, 1), LocalDate.of(2025, 6, 30), StatementVerdict.AUTO_INGEST);
        run(june, hdfc, credit(0, JUL_1, "240.00", "INT PAID TILL 30/06/25"));

        StatementBatchMatcher.Result r = run(fy, hdfc,
                credit(0, LocalDate.of(2025, 6, 30), "240.00", "Interest paid till 30-JUN-2025"));

        assertThat(r.overlapAttached()).isEqualTo(1);
        assertThat(r.created()).isZero();
        assertThat(fixture.txns).hasSize(1);
        assertThat(lineTxn(fy, 0)).isEqualTo(lineTxn(june, 0));
    }

    @Test
    void aLineThreeDaysPastItsPeriodStaysApartFromARowTwoDaysPast() {
        Account equitas = account();
        Statement other = statement(equitas, LocalDate.of(2020, 9, 1), LocalDate.of(2020, 10, 31), StatementVerdict.AUTO_INGEST);
        Statement september = statement(equitas, LocalDate.of(2020, 9, 1), LocalDate.of(2020, 9, 30), StatementVerdict.AUTO_INGEST);
        run(other, equitas, credit(0, LocalDate.of(2020, 10, 2), "27.00", "Interest credit"));

        StatementBatchMatcher.Result r = run(september, equitas,
                credit(0, LocalDate.of(2020, 10, 3), "27.00", "Int.Pd:01-07-2020 to 30-09-2020"));

        assertThat(r.overlapAttached()).isZero();
        assertThat(r.created()).isEqualTo(1);
        assertThat(fixture.txns).hasSize(2);
    }

    @Test
    void identicalLinesBothInsideTheirPeriodsADayApartStaySeparate() {
        Account equitas = account();
        Statement quarter = statement(equitas, LocalDate.of(2020, 7, 1), LocalDate.of(2020, 9, 30), StatementVerdict.AUTO_INGEST);
        Statement september = statement(equitas, LocalDate.of(2020, 9, 1), LocalDate.of(2020, 9, 30), StatementVerdict.AUTO_INGEST);
        run(quarter, equitas, credit(0, LocalDate.of(2020, 9, 30), "27.00", "Int.Pd:01-07-2020 to 30-09-2020"));

        StatementBatchMatcher.Result r = run(september, equitas,
                credit(0, LocalDate.of(2020, 9, 29), "27.00", "Int.Pd:01-07-2020 to 30-09-2020"));

        assertThat(r.overlapAttached()).isZero();
        assertThat(r.created()).isEqualTo(1);
        assertThat(fixture.txns).hasSize(2);
    }

    @Test
    void aLineInsideItsPeriodADayFromARowStaysSeparate() {
        Account equitas = account();
        Statement quarter = statement(equitas, LocalDate.of(2020, 7, 1), LocalDate.of(2020, 9, 30), StatementVerdict.AUTO_INGEST);
        Statement september = statement(equitas, LocalDate.of(2020, 9, 1), LocalDate.of(2020, 9, 30), StatementVerdict.AUTO_INGEST);
        run(quarter, equitas, credit(0, LocalDate.of(2020, 9, 29), "27.00", "Int.Pd:01-07-2020 to 30-09-2020"));

        StatementBatchMatcher.Result r = run(september, equitas,
                credit(0, LocalDate.of(2020, 9, 30), "27.00", "Interest credit"));

        assertThat(r.overlapAttached()).isZero();
        assertThat(r.created()).isEqualTo(1);
        assertThat(fixture.txns).hasSize(2);
    }
}
