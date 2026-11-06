package com.financeos.domain.transaction.match;

import com.financeos.domain.account.Account;
import com.financeos.domain.account.AccountType;
import com.financeos.domain.statement.Statement;
import com.financeos.domain.transaction.ReviewReason;
import com.financeos.domain.transaction.ReviewStatusManager;
import com.financeos.domain.transaction.ReviewType;
import com.financeos.domain.transaction.Transaction;
import com.financeos.domain.transaction.TransactionSource;
import com.financeos.domain.transaction.TransactionType;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;

class StatementBatchMatcherTest {

    private MatchFixture fixture = new MatchFixture();
    private final ReviewStatusManager review = new ReviewStatusManager();

    private static Account account(AccountType type) {
        Account a = new Account();
        a.setId(UUID.randomUUID());
        a.setType(type);
        return a;
    }

    private static Statement statement(LocalDate start, LocalDate end) {
        Statement s = new Statement();
        s.setId(UUID.randomUUID());
        s.setPeriodStart(start);
        s.setPeriodEnd(end);
        return s;
    }

    private Transaction alert(Account account, LocalDate date, String amount, String text) {
        Transaction t = new Transaction();
        t.setAccount(account);
        t.setDate(date);
        t.setAmount(new BigDecimal(amount));
        t.setType(TransactionType.DEBIT);
        t.setSource(TransactionSource.gmail_transaction_alert);
        t.setSourcedDescription(text);
        review.addReason(t, ReviewReason.UNRECONCILED);
        return fixture.store(t);
    }

    private Transaction manual(Account account, LocalDate date, String amount, String text) {
        Transaction t = new Transaction();
        t.setAccount(account);
        t.setDate(date);
        t.setAmount(new BigDecimal(amount));
        t.setType(TransactionType.DEBIT);
        t.setSource(TransactionSource.manual);
        t.setDescription(text);
        review.transitionTo(t, ReviewType.MANUALLY_REVIEWED);
        return fixture.store(t);
    }

    private static StatementBatchMatcher.Line line(int index, LocalDate date, String amount, String text) {
        return new StatementBatchMatcher.Line(index, date, new BigDecimal(amount), TransactionType.DEBIT, text, null, null, null);
    }

    /** The upload path's creation: a Confirmed file_upload row per unpaired line. */
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

    // ------------------------------------------------------------ the owner's examples

    @Test
    void lifeInsuranceAlertAttachesToItsLineAndTheStatementWins() {
        Account ace = account(AccountType.credit_card);
        LocalDate oct17 = LocalDate.of(2022, 10, 17);
        Transaction a = alert(ace, oct17, "60182.88", "LIFE INSURA");
        review.addReason(a, ReviewReason.DATE_CORRECTED);
        Statement s = statement(LocalDate.of(2022, 10, 1), LocalDate.of(2022, 10, 31));

        StatementBatchMatcher.Result r = run(s, ace, line(0, oct17, "60182.88", "10073240 F IN. INST"));

        assertThat(r.attached()).isEqualTo(1);
        assertThat(r.created()).isZero();
        assertThat(fixture.txns).containsExactly(a);
        assertThat(lineTxn(s, 0)).isEqualTo(a.getId());
        assertThat(a.getSourcedDescription()).isEqualTo("10073240 F IN. INST");
        assertThat(a.getReviewReasons()).isEmpty();
        assertThat(a.getReviewType()).isEqualTo(ReviewType.AUTO_REVIEWED);
        verify(fixture.accountRepository).lockById(ace.getId());
        verify(fixture.coverage).invalidateAll();
    }

    @Test
    void twoSameAmountAlertsArePairedWithTheirLinesByDate() {
        Account hdfc = account(AccountType.bank_account);
        LocalDate oct20 = LocalDate.of(2026, 10, 20);
        LocalDate oct22 = LocalDate.of(2026, 10, 22);
        Transaction toN241 = alert(hdfc, oct20, "500.00", "VPA n241@okaxis");
        Transaction toKarthik = alert(hdfc, oct22, "500.00", "KARTHIK");
        Statement s = statement(LocalDate.of(2026, 10, 1), LocalDate.of(2026, 10, 31));

        // the statement lists the later payment first
        StatementBatchMatcher.Result r = run(s, hdfc,
                line(0, oct22, "500.00", "UPI-KARTHIK S-karthik@oksbi-SBIN0001234-429900000002-UPI"),
                line(1, oct20, "500.00", "UPI-NAVEEN-n241@okaxis-UTIB0000001-429900000001-UPI"));

        assertThat(r.attached()).isEqualTo(2);
        assertThat(lineTxn(s, 0)).isEqualTo(toKarthik.getId());
        assertThat(lineTxn(s, 1)).isEqualTo(toN241.getId());
        assertThat(toKarthik.getDate()).isEqualTo(oct22);
        assertThat(toN241.getDate()).isEqualTo(oct20);
    }

    @Test
    void threeIdenticalTeaPaymentsMakeThreePairs() {
        Account kiwi = account(AccountType.credit_card);
        LocalDate oct23 = LocalDate.of(2026, 10, 23);
        LocalDate oct24 = LocalDate.of(2026, 10, 24);
        Transaction a1 = alert(kiwi, oct23, "45.00", "KARBAGAVALLI");
        Transaction a2 = alert(kiwi, oct24, "45.00", "KARBAGAVALLI");
        Transaction a3 = alert(kiwi, oct24, "45.00", "KARBAGAVALLI");
        Statement s = statement(LocalDate.of(2026, 10, 1), LocalDate.of(2026, 10, 31));

        StatementBatchMatcher.Result r = run(s, kiwi,
                line(0, oct23, "45.00", "UPI/KARBAGAVALLI/SBIN"),
                line(1, oct24, "45.00", "UPI/KARBAGAVALLI/SBIN"),
                line(2, oct24, "45.00", "UPI/KARBAGAVALLI/SBIN"));

        assertThat(r.attached()).isEqualTo(3);
        assertThat(r.created()).isZero();
        assertThat(lineTxn(s, 0)).isEqualTo(a1.getId());
        assertThat(List.of(lineTxn(s, 1), lineTxn(s, 2))).containsExactlyInAnyOrder(a2.getId(), a3.getId());
        assertThat(fixture.txns).hasSize(3);
    }

    @Test
    void holdAlertIsTaggedAndTheFinalChargeIsCreated() {
        Account card = account(AccountType.credit_card);
        LocalDate oct10 = LocalDate.of(2026, 10, 10);
        Transaction hold = alert(card, oct10, "5000.00", "TAJ HOTELS");
        Statement s = statement(LocalDate.of(2026, 10, 1), LocalDate.of(2026, 10, 31));

        StatementBatchMatcher.Result r = run(s, card, line(0, LocalDate.of(2026, 10, 12), "3200.00", "TAJ HOTELS MUMBAI"));

        assertThat(r.attached()).isZero();
        assertThat(r.created()).isEqualTo(1);
        assertThat(r.tagged()).isEqualTo(1);
        assertThat(hold.getReviewReasons()).containsExactly(ReviewReason.NOT_ON_STATEMENT); // UNRECONCILED dropped
        assertThat(hold.getReviewType()).isEqualTo(ReviewType.NEEDS_REVIEW);
        assertThat(hold.getNotOnStatementId()).isEqualTo(s.getId());
        Transaction charge = fixture.find(lineTxn(s, 0)).orElseThrow();
        assertThat(charge.getAmount()).isEqualByComparingTo("3200.00");
        assertThat(charge.getReviewReasons()).isEmpty();
        assertThat(charge.getReviewType()).isEqualTo(ReviewType.AUTO_REVIEWED);
    }

    @Test
    void alertInTheLastWDaysOfACardPeriodOrOutsideItIsNotTagged() {
        Account card = account(AccountType.credit_card);
        Statement s = statement(LocalDate.of(2026, 10, 1), LocalDate.of(2026, 10, 31));
        Transaction lastDays = alert(card, LocalDate.of(2026, 10, 29), "999.00", "AMAZON");
        Transaction beforePeriod = alert(card, LocalDate.of(2026, 9, 29), "888.00", "FLIPKART");
        Transaction justInside = alert(card, LocalDate.of(2026, 10, 28), "777.00", "MYNTRA");

        StatementBatchMatcher.Result r = run(s, card, line(0, LocalDate.of(2026, 10, 5), "10.00", "OTHER"));

        assertThat(lastDays.getReviewReasons()).doesNotContain(ReviewReason.NOT_ON_STATEMENT);
        assertThat(beforePeriod.getReviewReasons()).doesNotContain(ReviewReason.NOT_ON_STATEMENT);
        assertThat(justInside.getReviewReasons()).contains(ReviewReason.NOT_ON_STATEMENT);
        assertThat(r.tagged()).isEqualTo(1);
    }

    @Test
    void unpairedManualAndStatementRowsAreNeverTagged() {
        Account bank = account(AccountType.bank_account);
        Statement s = statement(LocalDate.of(2026, 10, 1), LocalDate.of(2026, 10, 31));
        Transaction typed = manual(bank, LocalDate.of(2026, 10, 10), "1500.00", "Cash to maid");

        run(s, bank, line(0, LocalDate.of(2026, 10, 5), "10.00", "OTHER"));

        assertThat(typed.getReviewReasons()).isEmpty();
        assertThat(typed.getReviewType()).isEqualTo(ReviewType.MANUALLY_REVIEWED);
        assertThat(typed.getNotOnStatementId()).isNull();
    }

    @Test
    void attachingNeverDowngradesAManuallyReviewedRowAndKeepsATypedDescription() {
        Account bank = account(AccountType.bank_account);
        LocalDate oct10 = LocalDate.of(2026, 10, 10);
        Transaction typed = manual(bank, LocalDate.of(2026, 10, 9), "1500.00", "Rent share");
        Statement s = statement(LocalDate.of(2026, 10, 1), LocalDate.of(2026, 10, 31));

        run(s, bank, line(0, oct10, "1500.00", "UPI/DR/429911112222/RAMESH/HDFC"));

        assertThat(lineTxn(s, 0)).isEqualTo(typed.getId());
        assertThat(typed.getReviewType()).isEqualTo(ReviewType.MANUALLY_REVIEWED);
        assertThat(typed.getDescription()).isEqualTo("Rent share");
        assertThat(typed.getSourcedDescription()).isEqualTo("UPI/DR/429911112222/RAMESH/HDFC");
        assertThat(typed.getDate()).isEqualTo(oct10);
    }

    @Test
    void alertJustBeforeThePeriodPairsWithALineInsideIt() {
        Account card = account(AccountType.credit_card);
        Transaction a = alert(card, LocalDate.of(2026, 9, 30), "250.00", "UBER");
        Statement s = statement(LocalDate.of(2026, 10, 1), LocalDate.of(2026, 10, 31));

        run(s, card, line(0, LocalDate.of(2026, 10, 2), "250.00", "UBER INDIA SYSTEMS"));

        assertThat(lineTxn(s, 0)).isEqualTo(a.getId());
        assertThat(a.getDate()).isEqualTo(LocalDate.of(2026, 10, 2));
    }

    @Test
    void aCorrectedStatementConfirmsATaggedAlert() {
        Account card = account(AccountType.credit_card);
        Transaction a = alert(card, LocalDate.of(2026, 10, 10), "640.00", "ZOMATO");
        Statement first = statement(LocalDate.of(2026, 10, 1), LocalDate.of(2026, 10, 31));
        run(first, card, line(0, LocalDate.of(2026, 10, 5), "10.00", "OTHER"));
        assertThat(a.getReviewReasons()).contains(ReviewReason.NOT_ON_STATEMENT);

        Statement corrected = statement(LocalDate.of(2026, 10, 1), LocalDate.of(2026, 10, 31));
        run(corrected, card, line(0, LocalDate.of(2026, 10, 5), "10.00", "OTHER"),
                line(1, LocalDate.of(2026, 10, 11), "640.00", "ZOMATO BANGALORE"));

        assertThat(lineTxn(corrected, 1)).isEqualTo(a.getId());
        assertThat(a.getReviewReasons()).isEmpty();
        assertThat(a.getNotOnStatementId()).isNull();
        assertThat(a.getReviewType()).isEqualTo(ReviewType.AUTO_REVIEWED);
    }

    // ------------------------------------------------------------ re-runs and order

    @Test
    void reUploadOfAnOverlappingFileAttachesWithoutNewRows() {
        Account bank = account(AccountType.bank_account);
        Statement september = statement(LocalDate.of(2026, 9, 1), LocalDate.of(2026, 9, 30));
        run(september, bank,
                line(0, LocalDate.of(2026, 9, 28), "120.00", "UPI/DR/429900000011/CHAI POINT/YESB"),
                line(1, LocalDate.of(2026, 9, 29), "120.00", "UPI/DR/429900000012/CHAI POINT/YESB"),
                line(2, LocalDate.of(2026, 9, 30), "800.00", "NEFT/N295241234567890/ACME"));
        assertThat(fixture.txns).hasSize(3);

        // a custom-range download covering 28/09 – 05/10 (the Gmail PDF of the same lines spaces them differently)
        Statement overlap = statement(LocalDate.of(2026, 9, 28), LocalDate.of(2026, 10, 5));
        StatementBatchMatcher.Result r = run(overlap, bank,
                line(0, LocalDate.of(2026, 9, 28), "120.00", "UPI/DR/429900000011/CHAI  POINT/YESB"),
                line(1, LocalDate.of(2026, 9, 29), "120.00", "upi/dr/429900000012/chai point/yesb"),
                line(2, LocalDate.of(2026, 9, 30), "800.00", "NEFT/N295241234567890/ACME"),
                line(3, LocalDate.of(2026, 10, 3), "75.00", "UPI/DR/429900000013/METRO/YESB"));

        assertThat(r.overlapAttached()).isEqualTo(3);
        assertThat(r.created()).isEqualTo(1);
        assertThat(fixture.txns).hasSize(4);
        assertThat(lineTxn(overlap, 0)).isEqualTo(lineTxn(september, 0));
        assertThat(lineTxn(overlap, 1)).isEqualTo(lineTxn(september, 1));
        assertThat(lineTxn(overlap, 2)).isEqualTo(lineTxn(september, 2));
    }

    @Test
    void runningTheSameStatementTwiceChangesNothing() {
        Account card = account(AccountType.credit_card);
        Transaction paired = alert(card, LocalDate.of(2026, 10, 3), "300.00", "SWIGGY");
        Transaction missing = alert(card, LocalDate.of(2026, 10, 8), "450.00", "ZOMATO");
        Statement s = statement(LocalDate.of(2026, 10, 1), LocalDate.of(2026, 10, 31));
        StatementBatchMatcher.Line[] lines = {
                line(0, LocalDate.of(2026, 10, 3), "300.00", "SWIGGY BANGALORE"),
                line(1, LocalDate.of(2026, 10, 9), "99.00", "NETFLIX")};

        run(s, card, lines);
        List<String> before = ledger();
        int linksBefore = fixture.links.size();
        StatementBatchMatcher.Result again = run(s, card, lines);

        assertThat(again.attached()).isZero();
        assertThat(again.created()).isZero();
        assertThat(again.tagged()).isZero();
        assertThat(again.alreadyLinked()).isEqualTo(2);
        assertThat(ledger()).isEqualTo(before);
        assertThat(fixture.links).hasSize(linksBefore);
        assertThat(missing.getReviewReasons()).contains(ReviewReason.NOT_ON_STATEMENT);
        assertThat(missing.getNotOnStatementId()).isEqualTo(s.getId());
        assertThat(paired.getReviewReasons()).isEmpty();
    }

    @Test
    void aRerunMovesTheLineOfAnOwnRowToTheAlertItPairsWith() {
        // A statement moved to this card: its own row came along; an alert on this card is the same payment, so the
        // line goes to the alert and the own row is handed back to be folded in. The alert is not tagged.
        Account card = account(AccountType.credit_card);
        Statement s = statement(LocalDate.of(2026, 10, 1), LocalDate.of(2026, 10, 31));
        run(s, card, line(0, LocalDate.of(2026, 10, 6), "1299.00", "AMAZON PAY INDIA"));
        UUID own = lineTxn(s, 0);
        Transaction alert = alert(card, LocalDate.of(2026, 10, 5), "1299.00", "Amazon");

        StatementBatchMatcher.Result r = fixture.matcher.rerun(s, card);

        assertThat(r.replacedOwnRows()).containsExactly(Map.entry(own, alert.getId()));
        assertThat(lineTxn(s, 0)).isEqualTo(alert.getId());
        assertThat(alert.getDate()).isEqualTo(LocalDate.of(2026, 10, 6));
        assertThat(alert.getSourcedDescription()).isEqualTo("AMAZON PAY INDIA");
        assertThat(alert.getReviewReasons()).isEmpty();
        assertThat(r.tagged()).isZero();
    }

    @Test
    void aRerunKeepsAnOwnRowNothingPairsWithAndTagsTheTargetsMissingAlerts() {
        Account card = account(AccountType.credit_card);
        Statement s = statement(LocalDate.of(2026, 10, 1), LocalDate.of(2026, 10, 31));
        run(s, card, line(0, LocalDate.of(2026, 10, 6), "1299.00", "AMAZON PAY INDIA"));
        UUID own = lineTxn(s, 0);
        Transaction missing = alert(card, LocalDate.of(2026, 10, 9), "450.00", "SWIGGY");

        StatementBatchMatcher.Result r = fixture.matcher.rerun(s, card);

        assertThat(r.replacedOwnRows()).isEmpty();
        assertThat(r.created()).isZero();
        assertThat(lineTxn(s, 0)).isEqualTo(own);
        assertThat(fixture.find(own)).isPresent();
        assertThat(missing.getReviewReasons()).containsExactly(ReviewReason.NOT_ON_STATEMENT);
        assertThat(missing.getNotOnStatementId()).isEqualTo(s.getId());
    }

    @Test
    void aRerunLeavesARowAnAlertMailPointsAtLinked() {
        // The statement's line sits on a row an alert mail created: not line-only, so it is not re-matched.
        Account card = account(AccountType.credit_card);
        Statement s = statement(LocalDate.of(2026, 10, 1), LocalDate.of(2026, 10, 31));
        run(s, card, line(0, LocalDate.of(2026, 10, 6), "1299.00", "AMAZON PAY INDIA"));
        UUID own = lineTxn(s, 0);
        fixture.alertMailTxnIds.add(own);
        alert(card, LocalDate.of(2026, 10, 5), "1299.00", "Amazon"); // would pair if the row were line-only

        StatementBatchMatcher.Result r = fixture.matcher.rerun(s, card);

        assertThat(r.replacedOwnRows()).isEmpty();
        assertThat(lineTxn(s, 0)).isEqualTo(own);
    }

    /** The account's statements as coverage reports them (the second overlap pass reads them). */
    private void coverage(Account a, Statement... statements) {
        for (Statement s : statements) {
            fixture.period(a.getId(), s);
        }
    }

    @Test
    void anUploadOfAPeriodWhosePdfWordsLinesDifferentlyMakesNoDuplicates() {
        Account bank = account(AccountType.bank_account);
        Statement pdf = statement(LocalDate.of(2026, 10, 1), LocalDate.of(2026, 10, 31));
        Statement csv = statement(LocalDate.of(2026, 10, 1), LocalDate.of(2026, 10, 31));
        coverage(bank, pdf, csv);
        run(pdf, bank,
                line(0, LocalDate.of(2026, 10, 20), "500.00", "TO TRANSFER-UPI/DR/n241/KARTHIK S"),
                line(1, LocalDate.of(2026, 10, 22), "500.00", "TO TRANSFER-UPI/DR/PRIYA M"),
                line(2, LocalDate.of(2026, 10, 25), "1200.00", "ELECTRICITY BOARD"));
        assertThat(fixture.txns).hasSize(3);

        StatementBatchMatcher.Result r = run(csv, bank,
                line(0, LocalDate.of(2026, 10, 20), "500.00", "UPI-KARTHIK S-n241"),
                line(1, LocalDate.of(2026, 10, 22), "500.00", "UPI-PRIYA M"),
                line(2, LocalDate.of(2026, 10, 25), "1200.00", "BESCOM BILL PAYMENT"));

        assertThat(r.created()).isZero();
        assertThat(r.overlapAttached()).isEqualTo(3);
        assertThat(fixture.txns).hasSize(3);
        assertThat(lineTxn(csv, 0)).isEqualTo(lineTxn(pdf, 0));
        assertThat(lineTxn(csv, 1)).isEqualTo(lineTxn(pdf, 1));
        assertThat(lineTxn(csv, 2)).isEqualTo(lineTxn(pdf, 2));
        // the existing rows keep the first import's text
        assertThat(fixture.find(lineTxn(pdf, 2)).orElseThrow().getSourcedDescription()).isEqualTo("ELECTRICITY BOARD");
    }

    @Test
    void twoDistinctSameAmountLinesStayTwoRowsOnlyWhenTheOtherImportHasTwo() {
        Account bank = account(AccountType.bank_account);
        Statement pdf = statement(LocalDate.of(2026, 10, 1), LocalDate.of(2026, 10, 31));
        Statement csv = statement(LocalDate.of(2026, 10, 1), LocalDate.of(2026, 10, 31));
        coverage(bank, pdf, csv);
        run(pdf, bank, line(0, LocalDate.of(2026, 10, 20), "500.00", "TO TRANSFER-UPI/DR/KARTHIK S"));

        StatementBatchMatcher.Result r = run(csv, bank,
                line(0, LocalDate.of(2026, 10, 20), "500.00", "UPI-KARTHIK S"),
                line(1, LocalDate.of(2026, 10, 20), "500.00", "UPI-ANITHA R"));

        assertThat(r.overlapAttached()).isEqualTo(1);
        assertThat(r.created()).isEqualTo(1);
        assertThat(fixture.txns).hasSize(2);
        assertThat(lineTxn(csv, 0)).isEqualTo(lineTxn(pdf, 0));
        assertThat(lineTxn(csv, 1)).isNotEqualTo(lineTxn(pdf, 0));
    }

    @Test
    void aRowOfAnotherStatementWhosePeriodDoesNotHoldTheLineIsNotAnOverlap() {
        Account bank = account(AccountType.bank_account);
        Statement september = statement(LocalDate.of(2026, 9, 1), LocalDate.of(2026, 9, 30));
        Statement october = statement(LocalDate.of(2026, 10, 1), LocalDate.of(2026, 10, 31));
        coverage(bank, september, october);
        run(september, bank, line(0, LocalDate.of(2026, 9, 30), "500.00", "UPI/DR/KARTHIK S"));

        StatementBatchMatcher.Result r = run(october, bank, line(0, LocalDate.of(2026, 10, 1), "500.00", "UPI-KARTHIK S"));

        assertThat(r.overlapAttached()).isZero();
        assertThat(r.created()).isEqualTo(1);
        assertThat(fixture.txns).hasSize(2);
    }

    @Test
    void aStatementMovedOntoAnAccountHoldingTheSamePeriodFoldsItsOwnRowsIntoTheOtherImport() {
        // The same October was imported on the target card already (another import); the moved statement's own rows
        // came along. Every line overlaps a target row, so each own row is handed back to be folded: no duplicates.
        Account source = account(AccountType.credit_card);
        Account target = account(AccountType.credit_card);
        Statement moved = statement(LocalDate.of(2026, 10, 1), LocalDate.of(2026, 10, 31));
        Statement other = statement(LocalDate.of(2026, 10, 1), LocalDate.of(2026, 10, 31));
        run(moved, source,
                line(0, LocalDate.of(2026, 10, 6), "1299.00", "AMAZON PAY INDIA"),
                line(1, LocalDate.of(2026, 10, 9), "450.00", "SWIGGY BANGALORE"));
        run(other, target,
                line(0, LocalDate.of(2026, 10, 6), "1299.00", "AMAZON PAY INDIA"),
                line(1, LocalDate.of(2026, 10, 9), "450.00", "Swiggy Instamart Blr"));
        UUID own0 = lineTxn(moved, 0);
        UUID own1 = lineTxn(moved, 1);
        fixture.find(own0).orElseThrow().setAccount(target);
        fixture.find(own1).orElseThrow().setAccount(target);
        coverage(target, moved, other);

        StatementBatchMatcher.Result r = fixture.matcher.rerun(moved, target);

        assertThat(r.replacedOwnRows()).containsOnly(
                Map.entry(own0, lineTxn(other, 0)), Map.entry(own1, lineTxn(other, 1)));
        assertThat(lineTxn(moved, 0)).isEqualTo(lineTxn(other, 0));
        assertThat(lineTxn(moved, 1)).isEqualTo(lineTxn(other, 1));
    }

    @Test
    void aRowOfAnotherStatementDatedOutsideThisPeriodIsNotARewordedOverlap() {
        Account bank = account(AccountType.bank_account);
        Statement midMonth = statement(LocalDate.of(2026, 9, 15), LocalDate.of(2026, 10, 15));
        Statement october = statement(LocalDate.of(2026, 10, 1), LocalDate.of(2026, 10, 31));
        coverage(bank, midMonth, october);
        run(midMonth, bank, line(0, LocalDate.of(2026, 9, 30), "500.00", "UPI/DR/KARTHIK S"));

        StatementBatchMatcher.Result r = run(october, bank, line(0, LocalDate.of(2026, 10, 1), "500.00", "UPI-KARTHIK S"));

        assertThat(r.overlapAttached()).isZero();
        assertThat(r.created()).isEqualTo(1);
        assertThat(fixture.txns).hasSize(2);
    }

    @Test
    void aRerunLeavesALegacyLinkWithNoLineIndexAlone() {
        Account card = account(AccountType.credit_card);
        Statement s = statement(LocalDate.of(2026, 10, 1), LocalDate.of(2026, 10, 31));
        run(s, card, line(0, LocalDate.of(2026, 10, 6), "1299.00", "AMAZON PAY INDIA"));
        Transaction legacy = new Transaction();
        legacy.setAccount(card);
        legacy.setDate(LocalDate.of(2026, 10, 10));
        legacy.setAmount(new BigDecimal("200.00"));
        legacy.setType(TransactionType.DEBIT);
        legacy.setSource(TransactionSource.file_upload);
        legacy.setSourcedDescription("OLD LINE");
        fixture.store(legacy);
        fixture.links.add(new com.financeos.domain.statement.StatementTransaction(s.getId(), legacy.getId(), null, null, null));
        Transaction alert = alert(card, LocalDate.of(2026, 10, 10), "200.00", "OLD LINE");
        int rows = fixture.txns.size();

        StatementBatchMatcher.Result r = fixture.matcher.rerun(s, card);

        assertThat(r.replacedOwnRows()).isEmpty();
        assertThat(fixture.txns).hasSize(rows);
        assertThat(fixture.links).filteredOn(l -> l.getId().getStatementId().equals(s.getId()))
                .extracting(l -> l.getLineIndex() == null ? -1 : l.getLineIndex())
                .containsExactlyInAnyOrder(0, -1);
        assertThat(fixture.statementsOf(legacy)).containsExactly(s.getId());
        assertThat(fixture.statementsOf(alert)).isEmpty();
    }

    @Test
    void bothImportOrdersGiveTheSameLedger() {
        assertThat(ledgerAfterImports(true)).isEqualTo(ledgerAfterImports(false));
    }

    private List<String> ledgerAfterImports(boolean gmailFirst) {
        fixture = new MatchFixture();
        Account card = account(AccountType.credit_card);
        alert(card, LocalDate.of(2026, 10, 3), "300.00", "SWIGGY");
        alert(card, LocalDate.of(2026, 10, 4), "45.00", "KARBAGAVALLI");
        alert(card, LocalDate.of(2026, 10, 4), "45.00", "KARBAGAVALLI");
        alert(card, LocalDate.of(2026, 10, 9), "5000.00", "TAJ HOTELS");
        manual(card, LocalDate.of(2026, 10, 15), "60182.88", "Life insurance premium");
        Statement gmail = statement(LocalDate.of(2026, 10, 1), LocalDate.of(2026, 10, 31));
        Statement upload = statement(LocalDate.of(2026, 10, 1), LocalDate.of(2026, 10, 31));
        StatementBatchMatcher.Line[] lines = {
                line(0, LocalDate.of(2026, 10, 4), "300.00", "SWIGGY BANGALORE"),
                line(1, LocalDate.of(2026, 10, 4), "45.00", "KARBAGAVALLI"),
                line(2, LocalDate.of(2026, 10, 5), "45.00", "KARBAGAVALLI"),
                line(3, LocalDate.of(2026, 10, 11), "3200.00", "TAJ HOTELS MUMBAI"),
                line(4, LocalDate.of(2026, 10, 15), "60182.88", "10073240 F IN. INST")};
        if (gmailFirst) {
            run(gmail, card, lines);
            run(upload, card, lines);
        } else {
            run(upload, card, lines);
            run(gmail, card, lines);
        }
        assertThat(fixture.txns).hasSize(6);
        return ledger();
    }

    /** What the user sees: each row's date, amount, bank text, review state and how many statement lines back it. */
    private List<String> ledger() {
        List<String> out = new ArrayList<>();
        for (Transaction t : fixture.txns) {
            out.add(t.getDate() + " " + t.getAmount().stripTrailingZeros().toPlainString() + " " + t.getSourcedDescription()
                    + " " + t.getReviewType() + " " + new java.util.TreeSet<>(t.getReviewReasons()) + " lines=" + fixture.statementsOf(t).size());
        }
        out.sort(Comparator.naturalOrder());
        return out;
    }
    // ------------------------------------------------------------ foreign currency (wave 3)

    private Transaction usdAlert(Account account, LocalDate date, String usd, String text) {
        Transaction t = new Transaction();
        t.setAccount(account);
        t.setDate(date);
        t.setAmount(null);
        t.setOriginalAmount(new BigDecimal(usd));
        t.setOriginalCurrency("USD");
        t.setType(TransactionType.DEBIT);
        t.setSource(TransactionSource.gmail_transaction_alert);
        t.setSourcedDescription(text);
        review.transitionTo(t, ReviewType.AUTO_REVIEWED);
        return fixture.store(t);
    }

    @Test
    void theStatementFillsAForeignAlertsRupeeAmountAndKeepsItsOriginalAmount() {
        Account eterna = account(AccountType.credit_card);
        Statement s = statement(LocalDate.of(2026, 10, 1), LocalDate.of(2026, 10, 31));
        Transaction a = usdAlert(eterna, LocalDate.of(2026, 10, 3), "20.00", "GITHUB");

        StatementBatchMatcher.Result r = run(s, eterna,
                line(0, LocalDate.of(2026, 10, 8), "928.08", "GITHUB, INC. GITHUB.COM CA 136 USD"),
                line(1, LocalDate.of(2026, 10, 8), "20.00", "TEA STALL"));

        assertThat(r.attached()).isEqualTo(1);
        assertThat(lineTxn(s, 0)).isEqualTo(a.getId());
        assertThat(a.getAmount()).isEqualByComparingTo("928.08");
        assertThat(a.getOriginalAmount()).isEqualByComparingTo("20.00");
        assertThat(a.getOriginalCurrency()).isEqualTo("USD");
        assertThat(a.getDate()).isEqualTo(LocalDate.of(2026, 10, 8));
        assertThat(a.getSourcedDescription()).isEqualTo("GITHUB, INC. GITHUB.COM CA 136 USD");
        assertThat(lineTxn(s, 1)).isNotEqualTo(a.getId()).isNotNull();
        assertThat(fixture.txns).hasSize(2);
    }

    @Test
    void aForeignAlertIsNeverPairedWithARupeeLineThatLacksItsCurrencyCode() {
        Account eterna = account(AccountType.credit_card);
        Statement s = statement(LocalDate.of(2026, 10, 1), LocalDate.of(2026, 10, 31));
        Transaction a = usdAlert(eterna, LocalDate.of(2026, 10, 3), "20.00", "GITHUB");

        run(s, eterna, line(0, LocalDate.of(2026, 10, 3), "20.00", "GITHUB, INC. GITHUB.COM CA"));

        assertThat(a.getAmount()).isNull();
        assertThat(lineTxn(s, 0)).isNotEqualTo(a.getId());
        assertThat(a.getReviewReasons()).containsExactly(ReviewReason.NOT_ON_STATEMENT);
    }

    @Test
    void twoForeignAlertsOfOneMerchantPairOneToOneWithTheirLines() {
        Account eterna = account(AccountType.credit_card);
        Statement s = statement(LocalDate.of(2026, 10, 1), LocalDate.of(2026, 10, 31));
        Transaction first = usdAlert(eterna, LocalDate.of(2026, 10, 3), "20.00", "GITHUB");
        Transaction second = usdAlert(eterna, LocalDate.of(2026, 10, 12), "4.00", "GITHUB");

        run(s, eterna,
                line(0, LocalDate.of(2026, 10, 5), "928.08", "GITHUB, INC. GITHUB.COM CA 136 USD"),
                line(1, LocalDate.of(2026, 10, 14), "186.10", "GITHUB, INC. GITHUB.COM CA 137 USD"));

        assertThat(lineTxn(s, 0)).isEqualTo(first.getId());
        assertThat(lineTxn(s, 1)).isEqualTo(second.getId());
        assertThat(first.getAmount()).isEqualByComparingTo("928.08");
        assertThat(second.getAmount()).isEqualByComparingTo("186.10");
    }

    @Test
    void theGithubChargeEndsAsOneTransactionWhicheverArrivesFirst() {
        LocalDate oct3 = LocalDate.of(2026, 10, 3);
        LocalDate oct8 = LocalDate.of(2026, 10, 8);
        String text = "GITHUB, INC. GITHUB.COM CA 136 USD";
        com.financeos.domain.user.User user = new com.financeos.domain.user.User();

        // Alert first, then the statement.
        Account a1 = account(AccountType.credit_card);
        Statement s1 = statement(LocalDate.of(2026, 10, 1), LocalDate.of(2026, 10, 31));
        fixture.period(a1.getId(), s1);
        fixture.alertMatcher.matchOrCreate(new AlertMatcher.Alert(user, a1, null, TransactionType.DEBIT,
                new BigDecimal("20.00"), "USD", oct3, "GITHUB", null, java.util.Set.of(), "alerts@eterna.example", null, "m1"));
        run(s1, a1, line(0, oct8, "928.08", text));
        List<Transaction> alertFirst = fixture.txns.stream().filter(t -> t.getAccount() == a1).toList();

        // Statement first, then the alert.
        Account a2 = account(AccountType.credit_card);
        Statement s2 = statement(LocalDate.of(2026, 10, 1), LocalDate.of(2026, 10, 31));
        fixture.period(a2.getId(), s2);
        run(s2, a2, line(0, oct8, "928.08", text));
        AlertMatcher.Outcome o = fixture.alertMatcher.matchOrCreate(new AlertMatcher.Alert(user, a2, null, TransactionType.DEBIT,
                new BigDecimal("20.00"), "USD", oct3, "GITHUB", null, java.util.Set.of(), "alerts@eterna.example", null, "m2"));
        List<Transaction> statementFirst = fixture.txns.stream().filter(t -> t.getAccount() == a2).toList();

        assertThat(o.created()).isFalse();
        for (List<Transaction> ledger : List.of(alertFirst, statementFirst)) {
            assertThat(ledger).hasSize(1);
            Transaction t = ledger.get(0);
            assertThat(t.getAmount()).isEqualByComparingTo("928.08");
            assertThat(t.getOriginalAmount()).isEqualByComparingTo("20.00");
            assertThat(t.getOriginalCurrency()).isEqualTo("USD");
            assertThat(t.getDate()).isEqualTo(oct8);
            assertThat(t.getSourcedDescription()).isEqualTo(text);
            assertThat(t.getType()).isEqualTo(TransactionType.DEBIT);
        }
    }

    @Test
    void foreignAlertsOfOneMerchantPairByThePrintedForeignAmountInEitherLineOrder() {
        for (boolean twentyFirst : new boolean[] {true, false}) {
            Account eterna = account(AccountType.credit_card);
            Statement s = statement(LocalDate.of(2026, 10, 1), LocalDate.of(2026, 10, 31));
            Transaction twenty = usdAlert(eterna, LocalDate.of(2026, 10, 3), "20.00", "GITHUB");
            Transaction five = usdAlert(eterna, LocalDate.of(2026, 10, 3), "5.00", "GITHUB");
            StatementBatchMatcher.Line l20 = line(twentyFirst ? 0 : 1, LocalDate.of(2026, 10, 5), "1720.00",
                    "GITHUB, INC. GITHUB.COM USD 20.00");
            StatementBatchMatcher.Line l5 = line(twentyFirst ? 1 : 0, LocalDate.of(2026, 10, 5), "430.00",
                    "GITHUB, INC. GITHUB.COM USD 5.00");

            if (twentyFirst) run(s, eterna, l20, l5);
            else run(s, eterna, l5, l20);

            assertThat(lineTxn(s, l20.lineIndex())).as("twentyFirst=" + twentyFirst).isEqualTo(twenty.getId());
            assertThat(lineTxn(s, l5.lineIndex())).as("twentyFirst=" + twentyFirst).isEqualTo(five.getId());
            assertThat(twenty.getAmount()).isEqualByComparingTo("1720.00");
            assertThat(five.getAmount()).isEqualByComparingTo("430.00");
        }
    }

    @Test
    void aRupeeAlertTakesItsAmountLineBeforeTheForeignPassLooksAtIt() {
        Account eterna = account(AccountType.credit_card);
        Statement s = statement(LocalDate.of(2026, 10, 1), LocalDate.of(2026, 10, 31));
        Transaction rupee = alert(eterna, LocalDate.of(2026, 10, 5), "928.08", "GITHUB");
        Transaction foreign = usdAlert(eterna, LocalDate.of(2026, 10, 3), "20.00", "GITHUB");

        run(s, eterna, line(0, LocalDate.of(2026, 10, 5), "928.08", "GITHUB, INC. GITHUB.COM CA 136 USD"));

        assertThat(lineTxn(s, 0)).isEqualTo(rupee.getId());
        assertThat(foreign.getAmount()).isNull();
    }

    @Test
    void aFilledForeignChargeGetsTheIngestEventItSkippedWithNoAmount() {
        org.springframework.context.ApplicationEventPublisher publisher =
                org.mockito.Mockito.mock(org.springframework.context.ApplicationEventPublisher.class);
        fixture.matcher.setEventPublisher(publisher);
        Account eterna = account(AccountType.credit_card);
        Statement s = statement(LocalDate.of(2026, 10, 1), LocalDate.of(2026, 10, 31));
        Transaction a = usdAlert(eterna, LocalDate.of(2026, 10, 3), "20.00", "GITHUB");
        com.financeos.domain.user.User user = new com.financeos.domain.user.User();
        user.setId(UUID.randomUUID());
        a.setUser(user);

        run(s, eterna, line(0, LocalDate.of(2026, 10, 8), "928.08", "GITHUB, INC. GITHUB.COM CA 136 USD"));

        org.mockito.ArgumentCaptor<Object> event = org.mockito.ArgumentCaptor.forClass(Object.class);
        org.mockito.Mockito.verify(publisher).publishEvent(event.capture());
        assertThat(event.getValue()).isInstanceOf(com.financeos.domain.transaction.autolink.TransactionsIngestedEvent.class);
        assertThat(((com.financeos.domain.transaction.autolink.TransactionsIngestedEvent) event.getValue()).userId())
                .isEqualTo(user.getId());
    }
}
