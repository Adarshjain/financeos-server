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
import com.financeos.domain.user.User;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.verify;

class AlertMatcherTest {

    private static final String BANK = "alerts@hdfcbank.net";
    private static final String CRED = "noreply@cred.club";

    private MatchFixture fixture = new MatchFixture();
    private final ReviewStatusManager review = new ReviewStatusManager();
    private final User user = new User();
    private int mailSeq;

    private static Account account(AccountType type) {
        Account a = new Account();
        a.setId(UUID.randomUUID());
        a.setType(type);
        return a;
    }

    private Statement statement(Account a, LocalDate start, LocalDate end) {
        Statement s = new Statement();
        s.setId(UUID.randomUUID());
        s.setPeriodStart(start);
        s.setPeriodEnd(end);
        fixture.period(a.getId(), s);
        return s;
    }

    private static StatementBatchMatcher.Line line(int index, LocalDate date, String amount, String text) {
        return new StatementBatchMatcher.Line(index, date, new BigDecimal(amount), TransactionType.DEBIT, text, null, null, null);
    }

    /** Imports the statement through the batch match, creating Confirmed upload rows for unpaired lines. */
    private StatementBatchMatcher.Result importStatement(Statement s, Account a, StatementBatchMatcher.Line... lines) {
        return fixture.matcher.match(s, a, List.of(lines), unpaired -> {
            Map<Integer, Transaction> made = new LinkedHashMap<>();
            for (StatementBatchMatcher.Line l : unpaired) {
                Transaction t = new Transaction();
                t.setAccount(a);
                t.setDate(l.date());
                t.setAmount(l.amount());
                t.setType(l.type());
                t.setSource(TransactionSource.file_upload);
                t.setSourcedDescription(l.description());
                review.transitionTo(t, ReviewType.AUTO_REVIEWED);
                made.put(l.lineIndex(), fixture.store(t));
            }
            return made;
        });
    }

    private static Instant at(LocalDate day, int hour, int minute) {
        return day.atTime(hour, minute).toInstant(ZoneOffset.ofHoursMinutes(5, 30));
    }

    private AlertMatcher.Alert alert(Account a, TransactionType type, LocalDate date, String amount, String text,
                                     String sender, Instant sentAt) {
        return new AlertMatcher.Alert(user, a, null, type, new BigDecimal(amount), "INR", date, text, null, Set.of(),
                sender, sentAt, "m" + (++mailSeq));
    }

    private AlertMatcher.Alert debit(Account a, LocalDate date, String amount, String text) {
        return alert(a, TransactionType.DEBIT, date, amount, text, BANK, at(date, 10, mailSeq));
    }

    /** The writer's part: match or create, then the mail points at the transaction. */
    private AlertMatcher.Outcome receive(AlertMatcher.Alert alert) {
        AlertMatcher.Outcome o = fixture.alertMatcher.matchOrCreate(alert);
        fixture.alertMails.add(new MatchFixture.AlertMailRow(o.txn().getId(), alert.gmailMessageId(), alert.senderAddress(), alert.sentAt()));
        return o;
    }

    private UUID lineTxn(Statement s, int lineIndex) {
        return fixture.links.stream()
                .filter(l -> l.getId().getStatementId().equals(s.getId()) && Integer.valueOf(lineIndex).equals(l.getLineIndex()))
                .map(l -> l.getId().getTransactionId()).findFirst().orElse(null);
    }

    // ------------------------------------------------------------ covered by a statement

    @Test
    void anAlertCoveredByAStatementAttachesToTheLineItPairsWith() {
        Account card = account(AccountType.credit_card);
        Statement oct = statement(card, LocalDate.of(2026, 10, 1), LocalDate.of(2026, 10, 31));
        importStatement(oct, card, line(0, LocalDate.of(2026, 10, 10), "500.00", "SWIGGY BANGALORE"));

        AlertMatcher.Outcome o = receive(debit(card, LocalDate.of(2026, 10, 9), "500.00", "Swiggy"));

        assertThat(o.created()).isFalse();
        assertThat(o.attached().kind()).isEqualTo(AlertMatcher.Kind.STATEMENT_LINE);
        assertThat(o.attached().detail()).isEqualTo("Attached to an existing transaction (statement line 10/10/2026)");
        assertThat(o.txn().getId()).isEqualTo(lineTxn(oct, 0));
        assertThat(o.txn().getSourcedDescription()).isEqualTo("SWIGGY BANGALORE");
        assertThat(fixture.txns).hasSize(1);
        verify(fixture.accountRepository, atLeastOnce()).lockById(card.getId());
    }

    @Test
    void aLineAnotherAlertAlreadyStandsForIsNotOfferedAgain() {
        Account card = account(AccountType.credit_card);
        Statement oct = statement(card, LocalDate.of(2026, 10, 1), LocalDate.of(2026, 10, 31));
        importStatement(oct, card, line(0, LocalDate.of(2026, 10, 10), "45.00", "TEA STALL"));
        receive(debit(card, LocalDate.of(2026, 10, 10), "45.00", "TEA STALL"));

        AlertMatcher.Outcome second = receive(alert(card, TransactionType.DEBIT, LocalDate.of(2026, 10, 10), "45.00",
                "TEA STALL", BANK, at(LocalDate.of(2026, 10, 10), 18, 0)));

        assertThat(second.created()).isTrue();
        assertThat(second.txn().getReviewReasons()).containsExactly(ReviewReason.NOT_ON_STATEMENT);
        assertThat(fixture.txns).hasSize(2);
    }

    @Test
    void aCoveredAlertWithNoLineDatedBeforeTheLastWDaysIsCreatedTaggedNotOnStatement() {
        Account card = account(AccountType.credit_card);
        Statement oct = statement(card, LocalDate.of(2026, 10, 1), LocalDate.of(2026, 10, 31));
        importStatement(oct, card, line(0, LocalDate.of(2026, 10, 10), "500.00", "SWIGGY"));

        AlertMatcher.Outcome o = receive(debit(card, LocalDate.of(2026, 10, 20), "999.00", "FAILED PAYMENT"));

        assertThat(o.created()).isTrue();
        assertThat(o.txn().getReviewReasons()).containsExactly(ReviewReason.NOT_ON_STATEMENT);
        assertThat(o.txn().getReviewType()).isEqualTo(ReviewType.NEEDS_REVIEW);
        assertThat(o.txn().getNotOnStatementId()).isEqualTo(oct.getId());
        assertThat(o.txn().getSource()).isEqualTo(TransactionSource.gmail_transaction_alert);
        assertThat(o.txn().getSourceMessageId()).isEqualTo("m1");
    }

    @Test
    void anAlertInTheLastWDaysWithNoNextStatementIsCreatedPending() {
        Account card = account(AccountType.credit_card);
        Statement oct = statement(card, LocalDate.of(2026, 10, 1), LocalDate.of(2026, 10, 31));
        importStatement(oct, card, line(0, LocalDate.of(2026, 10, 10), "500.00", "SWIGGY"));

        AlertMatcher.Outcome o = receive(debit(card, LocalDate.of(2026, 10, 30), "800.00", "AMAZON"));

        assertThat(o.created()).isTrue();
        assertThat(o.txn().getReviewReasons()).isEmpty();
        assertThat(o.txn().getReviewType()).isEqualTo(ReviewType.AUTO_REVIEWED);
        assertThat(o.txn().getNotOnStatementId()).isNull();
    }

    @Test
    void anAlertInTheLastWDaysAttachesToALineOfTheNextStatement() {
        Account card = account(AccountType.credit_card);
        Statement oct = statement(card, LocalDate.of(2026, 10, 1), LocalDate.of(2026, 10, 31));
        Statement nov = statement(card, LocalDate.of(2026, 11, 1), LocalDate.of(2026, 11, 30));
        importStatement(oct, card, line(0, LocalDate.of(2026, 10, 10), "500.00", "SWIGGY"));
        importStatement(nov, card, line(0, LocalDate.of(2026, 11, 2), "800.00", "AMAZON PAY"));

        AlertMatcher.Outcome o = receive(debit(card, LocalDate.of(2026, 10, 31), "800.00", "AMAZON"));

        assertThat(o.created()).isFalse();
        assertThat(o.txn().getId()).isEqualTo(lineTxn(nov, 0));
        assertThat(fixture.txns).hasSize(2);
    }

    @Test
    void coveredAProviderMailAfterTheIssuersAlertTookTheLineJoinsThatTransactionUntagged() {
        Account card = account(AccountType.credit_card);
        Statement oct = statement(card, LocalDate.of(2026, 10, 1), LocalDate.of(2026, 10, 31));
        importStatement(oct, card, new StatementBatchMatcher.Line(0, LocalDate.of(2026, 10, 6), new BigDecimal("15000.00"),
                TransactionType.CREDIT, "PAYMENT RECEIVED", null, null, null));
        AlertMatcher.Outcome issuer = receive(billPayment(card, BANK, LocalDate.of(2026, 10, 6), "PAYMENT RECEIVED THANK YOU",
                at(LocalDate.of(2026, 10, 6), 9, 0)));
        assertThat(issuer.attached().kind()).isEqualTo(AlertMatcher.Kind.STATEMENT_LINE);

        AlertMatcher.Outcome o = receive(billPayment(card, CRED, LocalDate.of(2026, 10, 5), "Paid via CRED",
                at(LocalDate.of(2026, 10, 5), 9, 0)));

        assertThat(o.created()).isFalse();
        assertThat(o.attached().kind()).isEqualTo(AlertMatcher.Kind.ISSUER_EMAIL);
        assertThat(o.txn().getId()).isEqualTo(lineTxn(oct, 0));
        assertThat(o.txn().getReviewReasons()).doesNotContain(ReviewReason.NOT_ON_STATEMENT);
        assertThat(fixture.txns).hasSize(1);
    }

    @Test
    void coveredAResendAfterTheFirstAlertTookTheLineAttaches() {
        Account card = account(AccountType.credit_card);
        Statement oct = statement(card, LocalDate.of(2026, 10, 1), LocalDate.of(2026, 10, 31));
        importStatement(oct, card, line(0, LocalDate.of(2026, 10, 10), "500.00", "SWIGGY BANGALORE"));
        LocalDate day = LocalDate.of(2026, 10, 10);
        AlertMatcher.Outcome first = receive(alert(card, TransactionType.DEBIT, day, "500.00", "SWIGGY BANGALORE", BANK, at(day, 12, 0)));
        assertThat(first.attached().kind()).isEqualTo(AlertMatcher.Kind.STATEMENT_LINE);

        AlertMatcher.Outcome resend = receive(alert(card, TransactionType.DEBIT, day, "500.00", "SWIGGY BANGALORE", BANK, at(day, 12, 5)));

        assertThat(resend.created()).isFalse();
        assertThat(resend.attached().kind()).isEqualTo(AlertMatcher.Kind.RESENT_ALERT);
        assertThat(resend.txn().getId()).isEqualTo(lineTxn(oct, 0));
        assertThat(fixture.txns).hasSize(1);
    }

    @Test
    void coveredAManualEntryWithNoLineInThePeriodAttaches() {
        Account bank = account(AccountType.bank_account);
        Statement oct = statement(bank, LocalDate.of(2026, 10, 1), LocalDate.of(2026, 10, 31));
        importStatement(oct, bank, line(0, LocalDate.of(2026, 10, 3), "90.00", "TEA"));
        Transaction manual = new Transaction();
        manual.setAccount(bank);
        manual.setDate(LocalDate.of(2026, 10, 12));
        manual.setAmount(new BigDecimal("1200.00"));
        manual.setType(TransactionType.DEBIT);
        manual.setSource(TransactionSource.manual);
        manual.setDescription("Rent share to Arjun");
        review.transitionTo(manual, ReviewType.MANUALLY_REVIEWED);
        fixture.store(manual);

        AlertMatcher.Outcome o = receive(debit(bank, LocalDate.of(2026, 10, 12), "1200.00", "UPI ARJUN K"));

        assertThat(o.created()).isFalse();
        assertThat(o.attached().kind()).isEqualTo(AlertMatcher.Kind.MANUAL_ENTRY);
        assertThat(o.txn()).isSameAs(manual);
        assertThat(manual.getReviewReasons()).doesNotContain(ReviewReason.NOT_ON_STATEMENT);
        assertThat(fixture.txns).hasSize(2);
    }

    // ------------------------------------------------------------ not covered

    @Test
    void anAlertJustBeforeAPeriodAttachesToALineInsideIt() {
        Account card = account(AccountType.credit_card);
        Statement nov = statement(card, LocalDate.of(2026, 11, 1), LocalDate.of(2026, 11, 30));
        importStatement(nov, card, line(0, LocalDate.of(2026, 11, 1), "800.00", "AMAZON PAY"));

        AlertMatcher.Outcome o = receive(debit(card, LocalDate.of(2026, 10, 30), "800.00", "AMAZON"));

        assertThat(o.created()).isFalse();
        assertThat(o.attached().kind()).isEqualTo(AlertMatcher.Kind.STATEMENT_LINE);
        assertThat(o.txn().getId()).isEqualTo(lineTxn(nov, 0));
    }

    private AlertMatcher.Alert billPayment(Account card, String sender, LocalDate date, String text, Instant sentAt) {
        return alert(card, TransactionType.CREDIT, date, "15000.00", text, sender, sentAt);
    }

    @Test
    void anIssuerMailAfterTheProvidersJoinsItAndTheIssuersTextAndDateWin() {
        Account card = account(AccountType.credit_card);
        AlertMatcher.Outcome first = receive(billPayment(card, CRED, LocalDate.of(2026, 10, 5), "Paid via CRED",
                at(LocalDate.of(2026, 10, 5), 9, 0)));

        AlertMatcher.Outcome o = receive(billPayment(card, BANK, LocalDate.of(2026, 10, 6), "PAYMENT RECEIVED THANK YOU",
                at(LocalDate.of(2026, 10, 6), 9, 0)));

        assertThat(o.created()).isFalse();
        assertThat(o.attached().kind()).isEqualTo(AlertMatcher.Kind.PROVIDER_EMAIL);
        assertThat(o.txn().getId()).isEqualTo(first.txn().getId());
        assertThat(o.txn().getSourcedDescription()).isEqualTo("PAYMENT RECEIVED THANK YOU");
        assertThat(o.txn().getDate()).isEqualTo(LocalDate.of(2026, 10, 6));
        assertThat(fixture.txns).hasSize(1);
    }

    @Test
    void aProviderMailAfterTheIssuersJoinsItAndTheIssuersTextStays() {
        Account card = account(AccountType.credit_card);
        AlertMatcher.Outcome first = receive(billPayment(card, BANK, LocalDate.of(2026, 10, 6), "PAYMENT RECEIVED THANK YOU",
                at(LocalDate.of(2026, 10, 6), 9, 0)));

        AlertMatcher.Outcome o = receive(billPayment(card, CRED, LocalDate.of(2026, 10, 5), "Paid via CRED",
                at(LocalDate.of(2026, 10, 5), 9, 0)));

        assertThat(o.created()).isFalse();
        assertThat(o.attached().kind()).isEqualTo(AlertMatcher.Kind.ISSUER_EMAIL);
        assertThat(o.attached().detail()).isEqualTo("Attached to an existing transaction (issuer email)");
        assertThat(o.txn().getId()).isEqualTo(first.txn().getId());
        assertThat(o.txn().getSourcedDescription()).isEqualTo("PAYMENT RECEIVED THANK YOU");
        assertThat(o.txn().getDate()).isEqualTo(LocalDate.of(2026, 10, 6));
        assertThat(fixture.txns).hasSize(1);
    }

    @Test
    void twoIssuerMailsForOneAmountAreTwoPayments() {
        Account card = account(AccountType.credit_card);
        receive(billPayment(card, BANK, LocalDate.of(2026, 10, 6), "PAYMENT RECEIVED", at(LocalDate.of(2026, 10, 6), 9, 0)));
        AlertMatcher.Outcome o = receive(billPayment(card, BANK, LocalDate.of(2026, 10, 7), "PAYMENT RECEIVED",
                at(LocalDate.of(2026, 10, 7), 9, 0)));

        assertThat(o.created()).isTrue();
        assertThat(fixture.txns).hasSize(2);
    }

    @Test
    void aResendWithinTenMinutesAttachesAndElevenMinutesIsASeparatePayment() {
        Account bank = account(AccountType.bank_account);
        LocalDate day = LocalDate.of(2026, 10, 24);
        AlertMatcher.Outcome first = receive(alert(bank, TransactionType.DEBIT, day, "250.00", "ZOMATO", BANK, at(day, 12, 0)));

        AlertMatcher.Outcome resend = receive(alert(bank, TransactionType.DEBIT, day, "250.00", "ZOMATO", BANK, at(day, 12, 10)));
        assertThat(resend.created()).isFalse();
        assertThat(resend.attached().kind()).isEqualTo(AlertMatcher.Kind.RESENT_ALERT);
        assertThat(resend.attached().detail()).isEqualTo("Attached to an existing transaction (resent alert)");
        assertThat(resend.txn().getId()).isEqualTo(first.txn().getId());

        AlertMatcher.Outcome later = receive(alert(bank, TransactionType.DEBIT, day, "250.00", "ZOMATO", BANK, at(day, 12, 21)));
        assertThat(later.created()).isTrue();
        assertThat(fixture.txns).hasSize(2);
    }

    @Test
    void threeTeaPaymentsFromTheSameBankAreThreeTransactions() {
        Account kiwi = account(AccountType.credit_card);
        receive(alert(kiwi, TransactionType.DEBIT, LocalDate.of(2026, 10, 23), "45.00", "KARBAGAVALLI", BANK,
                at(LocalDate.of(2026, 10, 23), 16, 5)));
        receive(alert(kiwi, TransactionType.DEBIT, LocalDate.of(2026, 10, 24), "45.00", "KARBAGAVALLI", BANK,
                at(LocalDate.of(2026, 10, 24), 11, 0)));
        receive(alert(kiwi, TransactionType.DEBIT, LocalDate.of(2026, 10, 24), "45.00", "KARBAGAVALLI", BANK,
                at(LocalDate.of(2026, 10, 24), 16, 40)));

        assertThat(fixture.txns).hasSize(3);
        assertThat(fixture.txns).allMatch(t -> t.getReviewReasons().isEmpty() && t.getReviewType() == ReviewType.AUTO_REVIEWED);
    }

    @Test
    void anAlertForAManualEntryAttachesKeepingTheTypedDescriptionAndCategory() {
        Account bank = account(AccountType.bank_account);
        Transaction manual = new Transaction();
        manual.setAccount(bank);
        manual.setDate(LocalDate.of(2026, 10, 12));
        manual.setAmount(new BigDecimal("1200.00"));
        manual.setType(TransactionType.DEBIT);
        manual.setSource(TransactionSource.manual);
        manual.setDescription("Rent share to Arjun");
        manual.setCategoryKey("housing");
        review.transitionTo(manual, ReviewType.MANUALLY_REVIEWED);
        fixture.store(manual);

        AlertMatcher.Outcome o = receive(debit(bank, LocalDate.of(2026, 10, 12), "1200.00", "UPI ARJUN K"));

        assertThat(o.created()).isFalse();
        assertThat(o.attached().kind()).isEqualTo(AlertMatcher.Kind.MANUAL_ENTRY);
        assertThat(o.attached().detail()).isEqualTo("Attached to an existing transaction (manual entry)");
        assertThat(o.txn()).isSameAs(manual);
        assertThat(manual.getDescription()).isEqualTo("Rent share to Arjun");
        assertThat(manual.getCategoryKey()).isEqualTo("housing");
        assertThat(manual.getSourcedDescription()).isEqualTo("UPI ARJUN K");
        assertThat(manual.getReviewType()).isEqualTo(ReviewType.MANUALLY_REVIEWED);
        assertThat(fixture.txns).hasSize(1);
    }

    @Test
    void attachIfMatchedCreatesNothingOnAMiss() {
        Account card = account(AccountType.credit_card);

        assertThat(fixture.alertMatcher.attachIfMatched(debit(card, LocalDate.of(2026, 10, 9), "500.00", "Swiggy"))).isEmpty();
        assertThat(fixture.txns).isEmpty();
    }

    @Test
    void attachIfMatchedAttachesWhenTheLineIsThere() {
        Account card = account(AccountType.credit_card);
        Statement oct = statement(card, LocalDate.of(2026, 10, 1), LocalDate.of(2026, 10, 31));
        importStatement(oct, card, line(0, LocalDate.of(2026, 10, 10), "500.00", "SWIGGY BANGALORE"));

        var attached = fixture.alertMatcher.attachIfMatched(debit(card, LocalDate.of(2026, 10, 10), "500.00", "Swiggy"));

        assertThat(attached).isPresent();
        assertThat(attached.get().txn().getId()).isEqualTo(lineTxn(oct, 0));
        assertThat(fixture.txns).hasSize(1);
    }

    @Test
    void attachIfMatchedReReadsThePairableRowsAfterTheLockBeforeDecidingAgain() {
        Account card = account(AccountType.credit_card);
        Statement oct = statement(card, LocalDate.of(2026, 10, 1), LocalDate.of(2026, 10, 31));
        importStatement(oct, card,
                line(0, LocalDate.of(2026, 10, 10), "500.00", "SWIGGY BANGALORE"),
                line(1, LocalDate.of(2026, 10, 10), "80.00", "TEA"));
        jakarta.persistence.EntityManager em = org.mockito.Mockito.mock(jakarta.persistence.EntityManager.class);
        org.mockito.Mockito.when(em.contains(org.mockito.ArgumentMatchers.any())).thenReturn(true);
        org.springframework.test.util.ReflectionTestUtils.setField(fixture.alertMatcher, "entityManager", em);
        Transaction swiggy = fixture.find(lineTxn(oct, 0)).orElseThrow();
        Transaction tea = fixture.find(lineTxn(oct, 1)).orElseThrow();
        org.mockito.Mockito.clearInvocations(fixture.accountRepository);

        var attached = fixture.alertMatcher.attachIfMatched(debit(card, LocalDate.of(2026, 10, 10), "500.00", "Swiggy"));

        assertThat(attached).isPresent();
        var order = org.mockito.Mockito.inOrder(fixture.accountRepository, em);
        order.verify(fixture.accountRepository).lockById(card.getId());
        order.verify(em).refresh(swiggy);
        verify(em, org.mockito.Mockito.never()).refresh(tea);
    }

    @Test
    void aResendOfAnAlertWhoseRowTookALineTwoDaysLaterAttaches() {
        Account card = account(AccountType.credit_card);
        Statement oct = statement(card, LocalDate.of(2026, 10, 1), LocalDate.of(2026, 10, 31));
        importStatement(oct, card, line(0, LocalDate.of(2026, 10, 12), "500.00", "SWIGGY BANGALORE"));
        LocalDate day = LocalDate.of(2026, 10, 10);
        AlertMatcher.Outcome first = receive(alert(card, TransactionType.DEBIT, day, "500.00", "Swiggy", BANK, at(day, 12, 0)));
        assertThat(first.attached().kind()).isEqualTo(AlertMatcher.Kind.STATEMENT_LINE);
        assertThat(first.txn().getDate()).isEqualTo(LocalDate.of(2026, 10, 12));

        AlertMatcher.Outcome resend = receive(alert(card, TransactionType.DEBIT, day, "500.00", "Swiggy", BANK, at(day, 12, 5)));

        assertThat(resend.created()).isFalse();
        assertThat(resend.attached().kind()).isEqualTo(AlertMatcher.Kind.RESENT_ALERT);
        assertThat(resend.txn().getId()).isEqualTo(lineTxn(oct, 0));
        assertThat(fixture.txns).hasSize(1);
        // the ledger is read with each row's card in one query
        verify(fixture.transactionRepository, org.mockito.Mockito.never())
                .findByAccountIdAndDateRange(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(),
                        org.mockito.ArgumentMatchers.any());
    }

    @Test
    void resendsAtZeroEightAndSixteenMinutesDoNotChainIntoOnePayment() {
        Account bank = account(AccountType.bank_account);
        LocalDate day = LocalDate.of(2026, 10, 24);
        AlertMatcher.Outcome a = receive(alert(bank, TransactionType.DEBIT, day, "250.00", "ZOMATO", BANK, at(day, 12, 0)));
        AlertMatcher.Outcome b = receive(alert(bank, TransactionType.DEBIT, day, "250.00", "ZOMATO", BANK, at(day, 12, 8)));
        AlertMatcher.Outcome c = receive(alert(bank, TransactionType.DEBIT, day, "250.00", "ZOMATO", BANK, at(day, 12, 16)));

        assertThat(b.created()).isFalse();
        assertThat(b.txn().getId()).isEqualTo(a.txn().getId());
        assertThat(c.created()).isTrue();
        assertThat(fixture.txns).hasSize(2);
    }

    // ------------------------------------------------------------ order independence

    @Test
    void lifeInsuranceGivesTheSameLedgerInBothOrders() {
        assertSameLedgerInBothOrders(() -> {
            Account ace = account(AccountType.credit_card);
            LocalDate oct17 = LocalDate.of(2022, 10, 17);
            return new Scenario(ace, LocalDate.of(2022, 10, 1), LocalDate.of(2022, 10, 31),
                    List.of(debit(ace, oct17, "60182.88", "LIFE INSURA")),
                    List.of(line(0, oct17, "60182.88", "10073240 F IN. INST")));
        }, 1);
    }

    @Test
    void aHoldGivesTheSameLedgerInBothOrders() {
        assertSameLedgerInBothOrders(() -> {
            Account card = account(AccountType.credit_card);
            return new Scenario(card, LocalDate.of(2026, 10, 1), LocalDate.of(2026, 10, 31),
                    List.of(debit(card, LocalDate.of(2026, 10, 9), "5000.00", "TAJ HOTELS")),
                    List.of(line(0, LocalDate.of(2026, 10, 11), "3200.00", "TAJ HOTELS MUMBAI")));
        }, 2);
    }

    @Test
    void twoFiveHundredsGiveTheSameLedgerInBothOrders() {
        assertSameLedgerInBothOrders(() -> {
            Account savings = account(AccountType.bank_account);
            return new Scenario(savings, LocalDate.of(2026, 10, 1), LocalDate.of(2026, 10, 31),
                    List.of(debit(savings, LocalDate.of(2026, 10, 20), "500.00", "n241"),
                            debit(savings, LocalDate.of(2026, 10, 22), "500.00", "KARTHIK")),
                    List.of(line(0, LocalDate.of(2026, 10, 20), "500.00", "UPI/DR/XXXXn241"),
                            line(1, LocalDate.of(2026, 10, 22), "500.00", "UPI/DR/KARTHIK S")));
        }, 2);
    }

    private record Scenario(Account account, LocalDate start, LocalDate end, List<AlertMatcher.Alert> alerts,
                            List<StatementBatchMatcher.Line> lines) {
    }

    private void assertSameLedgerInBothOrders(Supplier<Scenario> scenario, int expectedRows) {
        List<String> alertFirst = ledgerAfter(scenario, true);
        List<String> statementFirst = ledgerAfter(scenario, false);
        assertThat(alertFirst).hasSize(expectedRows);
        assertThat(statementFirst).isEqualTo(alertFirst);
    }

    private List<String> ledgerAfter(Supplier<Scenario> scenario, boolean alertFirst) {
        fixture = new MatchFixture();
        mailSeq = 0;
        Scenario sc = scenario.get();
        Statement s = new Statement();
        s.setId(UUID.randomUUID());
        s.setPeriodStart(sc.start());
        s.setPeriodEnd(sc.end());
        if (alertFirst) {
            sc.alerts().forEach(this::receive);
            fixture.period(sc.account().getId(), s);
            importStatement(s, sc.account(), sc.lines().toArray(StatementBatchMatcher.Line[]::new));
        } else {
            fixture.period(sc.account().getId(), s);
            importStatement(s, sc.account(), sc.lines().toArray(StatementBatchMatcher.Line[]::new));
            sc.alerts().forEach(this::receive);
        }
        return ledger();
    }

    /** Each row: date, amount, bank text, review state and tags, statement lines and alert mails backing it. */
    private List<String> ledger() {
        List<String> out = new ArrayList<>();
        for (Transaction t : fixture.txns) {
            long mails = fixture.alertMails.stream().filter(m -> m.transactionId().equals(t.getId())).count();
            out.add(t.getDate() + " " + t.getAmount().stripTrailingZeros().toPlainString() + " " + t.getSourcedDescription()
                    + " " + t.getReviewType() + " " + new TreeSet<>(t.getReviewReasons())
                    + " tagged=" + (t.getNotOnStatementId() != null) + " lines=" + fixture.statementsOf(t).size()
                    + " mails=" + mails);
        }
        out.sort(Comparator.naturalOrder());
        return out;
    }
    // ------------------------------------------------------------ foreign currency (wave 3)

    private AlertMatcher.Alert usd(Account a, LocalDate date, String amount, String text, Instant sentAt) {
        return new AlertMatcher.Alert(user, a, null, TransactionType.DEBIT, new BigDecimal(amount), "USD", date, text, null,
                Set.of(), BANK, sentAt, "m" + (++mailSeq));
    }

    @Test
    void aForeignAlertIsCreatedWithNoRupeeAmountAndItsOriginalAmountKept() {
        Account card = account(AccountType.credit_card);
        LocalDate day = LocalDate.of(2026, 10, 3);

        AlertMatcher.Outcome o = receive(usd(card, day, "20.00", "GITHUB", at(day, 9, 0)));

        assertThat(o.created()).isTrue();
        assertThat(o.txn().getAmount()).isNull();
        assertThat(o.txn().getOriginalAmount()).isEqualByComparingTo("20.00");
        assertThat(o.txn().getOriginalCurrency()).isEqualTo("USD");
        assertThat(o.txn().getReviewType()).isEqualTo(ReviewType.AUTO_REVIEWED);
    }

    @Test
    void aRupeeAlertKeepsNoOriginalAmountAndDollarSignReadsAsUsd() {
        Account card = account(AccountType.credit_card);
        LocalDate day = LocalDate.of(2026, 10, 3);
        AlertMatcher.Outcome inr = receive(debit(card, day, "500.00", "SWIGGY"));
        assertThat(inr.txn().getAmount()).isEqualByComparingTo("500.00");
        assertThat(inr.txn().getOriginalAmount()).isNull();
        assertThat(inr.txn().getOriginalCurrency()).isNull();

        AlertMatcher.Alert dollar = new AlertMatcher.Alert(user, card, null, TransactionType.DEBIT, new BigDecimal("5.00"), "$",
                day, "OPENAI", null, Set.of(), BANK, at(day, 11, 0), "m" + (++mailSeq));
        assertThat(dollar.currency()).isEqualTo("USD");
        assertThat(receive(dollar).txn().getAmount()).isNull();
    }

    @Test
    void aForeignAlertAttachesToTheStatementLineRowPrintingItsCodeAndAddsItsOriginalAmount() {
        Account card = account(AccountType.credit_card);
        Statement oct = statement(card, LocalDate.of(2026, 10, 1), LocalDate.of(2026, 10, 31));
        importStatement(oct, card, line(0, LocalDate.of(2026, 10, 8), "928.08", "GITHUB, INC. GITHUB.COM CA 136 USD"),
                line(1, LocalDate.of(2026, 10, 3), "20.00", "GITHUB, INC. GITHUB.COM CA"));

        AlertMatcher.Outcome o = receive(usd(card, LocalDate.of(2026, 10, 3), "20.00", "GITHUB", at(LocalDate.of(2026, 10, 3), 9, 0)));

        assertThat(o.created()).isFalse();
        assertThat(o.attached().kind()).isEqualTo(AlertMatcher.Kind.STATEMENT_LINE);
        assertThat(o.txn().getId()).isEqualTo(lineTxn(oct, 0));
        assertThat(o.txn().getAmount()).isEqualByComparingTo("928.08");
        assertThat(o.txn().getOriginalAmount()).isEqualByComparingTo("20.00");
        assertThat(o.txn().getOriginalCurrency()).isEqualTo("USD");
        assertThat(fixture.txns).hasSize(2);
    }

    @Test
    void aForeignResendAttachesOnTheSameCurrencyAndOriginalAmount() {
        Account card = account(AccountType.credit_card);
        LocalDate day = LocalDate.of(2026, 10, 3);
        AlertMatcher.Outcome first = receive(usd(card, day, "20.00", "GITHUB", at(day, 9, 0)));

        AlertMatcher.Outcome resend = receive(usd(card, day, "20.00", "GITHUB", at(day, 9, 4)));
        assertThat(resend.created()).isFalse();
        assertThat(resend.attached().kind()).isEqualTo(AlertMatcher.Kind.RESENT_ALERT);
        assertThat(resend.txn().getId()).isEqualTo(first.txn().getId());

        AlertMatcher.Outcome otherAmount = receive(usd(card, day, "21.00", "GITHUB", at(day, 9, 6)));
        assertThat(otherAmount.created()).isTrue();
        assertThat(fixture.txns).hasSize(2);
    }

    @Test
    void aForeignAlertNeverJoinsAManualEntryThatHasARupeeAmount() {
        Account card = account(AccountType.credit_card);
        LocalDate day = LocalDate.of(2026, 10, 3);
        Transaction manual = new Transaction();
        manual.setAccount(card);
        manual.setDate(day);
        manual.setAmount(new BigDecimal("928.08"));
        manual.setType(TransactionType.DEBIT);
        manual.setSource(TransactionSource.manual);
        manual.setDescription("GITHUB USD subscription");
        review.transitionTo(manual, ReviewType.MANUALLY_REVIEWED);
        fixture.store(manual);

        AlertMatcher.Outcome o = receive(usd(card, day, "20.00", "GITHUB", at(day, 9, 0)));

        assertThat(o.created()).isTrue();
        assertThat(manual.getOriginalCurrency()).isNull();
        assertThat(fixture.txns).hasSize(2);
    }

    // ------------------------------------------------------------ second notice from another address of one bank

    private static final String ICICI_ALERT = "credit_cards@icicibank.com";
    private static final String ICICI_SI = "cards@icicibank.com";

    private AlertMatcher.Alert iciciAlert(Account a, LocalDate day, String sender, Instant sentAt, String last4) {
        return new AlertMatcher.Alert(user, a, null, TransactionType.DEBIT, new BigDecimal("1180.00"), "INR", day, "NETFLIX", last4,
                Set.of(), sender, sentAt, "m" + (++mailSeq));
    }

    /** A card statement with one line, and the first mail (credit_cards@) already attached to it. */
    private AlertMatcher.Outcome lineWithFirstAlert(Account card, Statement oct, LocalDate day) {
        importStatement(oct, card, line(0, day, "1180.00", "NETFLIX"));
        AlertMatcher.Outcome first = receive(iciciAlert(card, day, ICICI_ALERT, at(day, 12, 0), "1234"));
        assertThat(first.attached().kind()).isEqualTo(AlertMatcher.Kind.STATEMENT_LINE);
        return first;
    }

    @Test
    void aSecondNoticeFromAnotherAddressOfTheSameBankInTheSameMinuteAttaches() {
        Account card = account(AccountType.credit_card);
        LocalDate day = LocalDate.of(2026, 10, 10);
        Statement oct = statement(card, LocalDate.of(2026, 10, 1), LocalDate.of(2026, 10, 31));
        AlertMatcher.Outcome first = lineWithFirstAlert(card, oct, day);

        AlertMatcher.Outcome second = receive(iciciAlert(card, day, ICICI_SI, at(day, 12, 0), "1234"));

        assertThat(second.created()).isFalse();
        assertThat(second.attached().kind()).isEqualTo(AlertMatcher.Kind.RESENT_ALERT);
        assertThat(second.txn().getId()).isEqualTo(first.txn().getId());
        assertThat(fixture.txns).hasSize(1);
        assertThat(fixture.txns.get(0).getTags()).isNullOrEmpty();
    }

    @Test
    void aSecondNoticeTwentySevenMinutesLaterAttaches() {
        Account card = account(AccountType.credit_card);
        LocalDate day = LocalDate.of(2026, 10, 10);
        Statement oct = statement(card, LocalDate.of(2026, 10, 1), LocalDate.of(2026, 10, 31));
        AlertMatcher.Outcome first = lineWithFirstAlert(card, oct, day);

        AlertMatcher.Outcome second = receive(iciciAlert(card, day, ICICI_SI, at(day, 12, 27), "1234"));

        assertThat(second.created()).isFalse();
        assertThat(second.txn().getId()).isEqualTo(first.txn().getId());
        assertThat(fixture.txns).hasSize(1);
    }

    @Test
    void aSecondNoticeTwoHoursLaterDoesNotAttach() {
        Account card = account(AccountType.credit_card);
        LocalDate day = LocalDate.of(2026, 10, 10);
        Statement oct = statement(card, LocalDate.of(2026, 10, 1), LocalDate.of(2026, 10, 31));
        AlertMatcher.Outcome first = lineWithFirstAlert(card, oct, day);

        AlertMatcher.Outcome second = receive(iciciAlert(card, day, ICICI_SI, at(day, 14, 0), "1234"));

        assertThat(second.created()).isTrue();
        assertThat(second.txn().getId()).isNotEqualTo(first.txn().getId());
        assertThat(fixture.txns).hasSize(2);
    }

    @Test
    void aMailFromAnotherBanksDomainDoesNotAttachAsASecondNotice() {
        Account card = account(AccountType.credit_card);
        LocalDate day = LocalDate.of(2026, 10, 10);
        Statement oct = statement(card, LocalDate.of(2026, 10, 1), LocalDate.of(2026, 10, 31));
        AlertMatcher.Outcome first = lineWithFirstAlert(card, oct, day);

        AlertMatcher.Outcome other = receive(iciciAlert(card, day, "alerts@hdfcbank.net", at(day, 12, 1), "1234"));

        assertThat(other.created()).isTrue();
        assertThat(other.txn().getId()).isNotEqualTo(first.txn().getId());
    }

    @Test
    void aThirdAddressOfTheDomainAttachesWithinTheHourButAnAddressAlreadyOnTheRowOnlyThroughTheTenMinuteRule() {
        Account card = account(AccountType.credit_card);
        LocalDate day = LocalDate.of(2026, 10, 10);
        Statement oct = statement(card, LocalDate.of(2026, 10, 1), LocalDate.of(2026, 10, 31));
        AlertMatcher.Outcome first = lineWithFirstAlert(card, oct, day);
        receive(iciciAlert(card, day, ICICI_SI, at(day, 12, 5), "1234"));

        // A third address: no mail of its own on the row, all domain mails within the hour: attaches.
        AlertMatcher.Outcome third = receive(iciciAlert(card, day, "alerts@icicibank.com", at(day, 12, 20), "1234"));
        assertThat(third.created()).isFalse();
        assertThat(third.txn().getId()).isEqualTo(first.txn().getId());

        // credit_cards@ is already on the row and 30 minutes past its own mail: only the ten-minute rule applies.
        AlertMatcher.Outcome again = receive(iciciAlert(card, day, ICICI_ALERT, at(day, 12, 30), "1234"));
        assertThat(again.created()).isTrue();
        assertThat(fixture.txns).hasSize(2);
    }

    @Test
    void aSecondNoticeForADifferentCardDoesNotAttach() {
        Account card = account(AccountType.credit_card);
        LocalDate day = LocalDate.of(2026, 10, 10);
        Statement oct = statement(card, LocalDate.of(2026, 10, 1), LocalDate.of(2026, 10, 31));
        AlertMatcher.Outcome first = lineWithFirstAlert(card, oct, day);
        // The row's card is what the pair test compares: the first alert's card is on it.
        com.financeos.domain.account.card.Card c = new com.financeos.domain.account.card.Card();
        c.setLast4("1234");
        first.txn().setCard(c);

        AlertMatcher.Outcome other = receive(iciciAlert(card, day, ICICI_SI, at(day, 12, 0), "9999"));

        assertThat(other.created()).isTrue();
        assertThat(other.txn().getId()).isNotEqualTo(first.txn().getId());
    }
}
