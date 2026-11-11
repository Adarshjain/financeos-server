package com.financeos.domain.transaction.match;

import static org.assertj.core.api.Assertions.assertThat;

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
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** A text-message alert (source sms_alert) matches, creates and merges the way an alert email does. Invented data. */
class AlertMatcherSmsTest {

    private static final String BANK = "alerts@testbank.example";

    private final MatchFixture fixture = new MatchFixture();
    private final ReviewStatusManager review = new ReviewStatusManager();
    private final User user = new User();
    private int seq;

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

    private StatementBatchMatcher.Result importStatement(Statement s, Account a, LocalDate date, String amount, String text) {
        StatementBatchMatcher.Line line = new StatementBatchMatcher.Line(0, date, new BigDecimal(amount), TransactionType.DEBIT, text, null, null, null);
        return fixture.matcher.match(s, a, List.of(line), unpaired -> {
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

    private static Instant at(LocalDate day, int hour) {
        return day.atTime(hour, 0).toInstant(ZoneOffset.ofHoursMinutes(5, 30));
    }

    private AlertMatcher.Alert sms(Account a, LocalDate date, String amount, String text) {
        String id = "sms:" + UUID.randomUUID();
        return new AlertMatcher.Alert(user, a, null, TransactionType.DEBIT, new BigDecimal(amount), "INR", date, text, null,
                Set.of(), "sms:TESTBK", at(date, 11), id, TransactionSource.sms_alert);
    }

    private AlertMatcher.Alert email(Account a, LocalDate date, String amount, String text) {
        return new AlertMatcher.Alert(user, a, null, TransactionType.DEBIT, new BigDecimal(amount), "INR", date, text, null,
                Set.of(), BANK, at(date, 10), "m" + (++seq));
    }

    /** The writer's part for an email: match or create, then the mail points at the transaction. */
    private AlertMatcher.Outcome receiveEmail(AlertMatcher.Alert alert) {
        AlertMatcher.Outcome o = fixture.alertMatcher.matchOrCreate(alert);
        fixture.alertMails.add(new MatchFixture.AlertMailRow(o.txn().getId(), alert.gmailMessageId(), alert.senderAddress(), alert.sentAt()));
        return o;
    }

    @Test
    void anSmsAloneCreatesARowWithSourceSmsAlertAndItsMessageId() {
        Account bank = account(AccountType.generic);
        AlertMatcher.Alert alert = sms(bank, LocalDate.of(2026, 11, 8), "250.00", "TEST PAYEE");

        AlertMatcher.Outcome o = fixture.alertMatcher.matchOrCreate(alert);

        assertThat(o.created()).isTrue();
        assertThat(o.txn().getSource()).isEqualTo(TransactionSource.sms_alert);
        assertThat(o.txn().getSourceMessageId()).isEqualTo(alert.gmailMessageId());
        assertThat(o.txn().getSourcedDescription()).isEqualTo("TEST PAYEE");
        assertThat(o.txn().getReviewType()).isEqualTo(ReviewType.AUTO_REVIEWED);
    }

    @Test
    void anSmsAttachesToTheStatementLineOfThePayment() {
        Account bank = account(AccountType.bank_account);
        Statement nov = statement(bank, LocalDate.of(2026, 11, 1), LocalDate.of(2026, 11, 30));
        importStatement(nov, bank, LocalDate.of(2026, 11, 8), "250.00", "TEST PAYEE UPI");

        AlertMatcher.Outcome o = fixture.alertMatcher.matchOrCreate(sms(bank, LocalDate.of(2026, 11, 8), "250.00", "TEST PAYEE"));

        assertThat(o.created()).isFalse();
        assertThat(o.attached().kind()).isEqualTo(AlertMatcher.Kind.STATEMENT_LINE);
        assertThat(o.txn().getSource()).isEqualTo(TransactionSource.file_upload);
        assertThat(fixture.txns).hasSize(1);
    }

    @Test
    void anSmsAttachesToTheRowAnAlertEmailAlreadyPointsAt() {
        Account bank = account(AccountType.bank_account);
        AlertMatcher.Outcome mail = receiveEmail(email(bank, LocalDate.of(2026, 11, 8), "250.00", "Test Payee"));

        AlertMatcher.Outcome o = fixture.alertMatcher.matchOrCreate(sms(bank, LocalDate.of(2026, 11, 8), "250.00", "TEST PAYEE"));

        assertThat(o.created()).isFalse();
        assertThat(o.attached().kind()).isEqualTo(AlertMatcher.Kind.OTHER_CHANNEL_ALERT);
        assertThat(o.txn().getId()).isEqualTo(mail.txn().getId());
        assertThat(o.txn().getSource()).isEqualTo(TransactionSource.gmail_transaction_alert);
        assertThat(o.txn().getSourcedDescription()).isEqualTo("Test Payee");
        assertThat(fixture.txns).hasSize(1);
    }

    @Test
    void anSmsAttachesToTheStatementRowAnEmailAlreadyJoined() {
        Account bank = account(AccountType.bank_account);
        Statement nov = statement(bank, LocalDate.of(2026, 11, 1), LocalDate.of(2026, 11, 30));
        importStatement(nov, bank, LocalDate.of(2026, 11, 8), "250.00", "TEST PAYEE UPI");
        AlertMatcher.Outcome mail = receiveEmail(email(bank, LocalDate.of(2026, 11, 8), "250.00", "Test Payee"));
        assertThat(mail.created()).isFalse();

        AlertMatcher.Outcome o = fixture.alertMatcher.matchOrCreate(sms(bank, LocalDate.of(2026, 11, 8), "250.00", "TEST PAYEE"));

        assertThat(o.created()).isFalse();
        assertThat(o.txn().getId()).isEqualTo(mail.txn().getId());
        assertThat(fixture.txns).hasSize(1);
    }

    @Test
    void anAlertEmailAttachesToTheRowAnSmsCreated() {
        Account bank = account(AccountType.bank_account);
        AlertMatcher.Outcome text = fixture.alertMatcher.matchOrCreate(sms(bank, LocalDate.of(2026, 11, 8), "250.00", "TEST PAYEE"));

        AlertMatcher.Outcome mail = receiveEmail(email(bank, LocalDate.of(2026, 11, 8), "250.00", "Test Payee"));

        assertThat(mail.created()).isFalse();
        assertThat(mail.attached().kind()).isEqualTo(AlertMatcher.Kind.OTHER_CHANNEL_ALERT);
        assertThat(mail.txn().getId()).isEqualTo(text.txn().getId());
        assertThat(fixture.txns).hasSize(1);
    }

    @Test
    void aSecondEqualPaymentByTextIsANewRowWhenTheFirstIsTakenByAnotherMessage() {
        Account bank = account(AccountType.bank_account);
        AlertMatcher.Outcome mail = receiveEmail(email(bank, LocalDate.of(2026, 11, 8), "250.00", "Test Payee"));

        AlertMatcher.Alert second = sms(bank, LocalDate.of(2026, 11, 8), "250.00", "TEST PAYEE");
        assertThat(fixture.alertMatcher.findMatch(second)).isPresent();
        assertThat(fixture.alertMatcher.findMatch(second, Set.of(mail.txn().getId()))).isEmpty();
        AlertMatcher.Outcome o = fixture.alertMatcher.matchOrCreate(second, Set.of(mail.txn().getId()));

        assertThat(o.created()).isTrue();
        assertThat(fixture.txns).hasSize(2);
    }

    @Test
    void anSmsInsideACoveringStatementPeriodWithoutALineIsTaggedNotOnStatement() {
        Account bank = account(AccountType.bank_account);
        Statement nov = statement(bank, LocalDate.of(2026, 11, 1), LocalDate.of(2026, 11, 30));
        importStatement(nov, bank, LocalDate.of(2026, 11, 2), "10.00", "OTHER ROW");

        AlertMatcher.Outcome o = fixture.alertMatcher.matchOrCreate(sms(bank, LocalDate.of(2026, 11, 12), "777.00", "TEST SHOP"));

        assertThat(o.created()).isTrue();
        assertThat(o.txn().getReviewReasons()).contains(ReviewReason.NOT_ON_STATEMENT);
        assertThat(o.txn().getNotOnStatementId()).isEqualTo(nov.getId());
    }

    @Test
    void aStatementArrivingAfterAnSmsRowMergesWithItLikeItDoesForAnEmailAlert() {
        Account bank = account(AccountType.bank_account);
        AlertMatcher.Outcome text = fixture.alertMatcher.matchOrCreate(sms(bank, LocalDate.of(2026, 11, 8), "250.00", "TEST PAYEE"));
        Statement nov = statement(bank, LocalDate.of(2026, 11, 1), LocalDate.of(2026, 11, 30));

        StatementBatchMatcher.Result r = importStatement(nov, bank, LocalDate.of(2026, 11, 8), "250.00", "TEST PAYEE UPI 123");

        assertThat(r.attached()).isEqualTo(1);
        assertThat(r.created()).isZero();
        assertThat(fixture.txns).hasSize(1);
        assertThat(fixture.statementsOf(text.txn())).containsExactly(nov.getId());
    }

    @Test
    void aStatementTagsAnSmsRowItDoesNotListLikeAnEmailRow() {
        Account bank = account(AccountType.bank_account);
        AlertMatcher.Outcome text = fixture.alertMatcher.matchOrCreate(sms(bank, LocalDate.of(2026, 11, 8), "250.00", "TEST PAYEE"));
        Statement nov = statement(bank, LocalDate.of(2026, 11, 1), LocalDate.of(2026, 11, 30));

        StatementBatchMatcher.Result r = importStatement(nov, bank, LocalDate.of(2026, 11, 20), "99.00", "SOMETHING ELSE");

        assertThat(r.tagged()).isEqualTo(1);
        assertThat(text.txn().getReviewReasons()).contains(ReviewReason.NOT_ON_STATEMENT);
        assertThat(text.txn().getNotOnStatementId()).isEqualTo(nov.getId());
    }

    @Test
    void twoAlertEmailsNeverJoinEachOtherThroughTheOtherChannelStep() {
        Account bank = account(AccountType.bank_account);
        // an SMS row exists; the first email joins it, the second email (another sender, hours later) must not
        fixture.alertMatcher.matchOrCreate(sms(bank, LocalDate.of(2026, 11, 8), "250.00", "TEST PAYEE"));
        AlertMatcher.Outcome first = receiveEmail(email(bank, LocalDate.of(2026, 11, 8), "250.00", "Test Payee"));
        assertThat(first.created()).isFalse();

        AlertMatcher.Alert later = new AlertMatcher.Alert(user, bank, null, TransactionType.DEBIT, new BigDecimal("250.00"), "INR",
                LocalDate.of(2026, 11, 8), "Test Payee", null, Set.of(), "other@otherbank.example", at(LocalDate.of(2026, 11, 8), 20), "m" + (++seq));
        AlertMatcher.Outcome second = receiveEmail(later);

        assertThat(second.created()).isTrue();
        assertThat(fixture.txns).hasSize(2);
    }

    @Test
    void anAlertEmailNeverJoinsAnotherAlertEmailsRowThroughTheOtherChannelStep() {
        Account bank = account(AccountType.bank_account);
        AlertMatcher.Outcome first = receiveEmail(email(bank, LocalDate.of(2026, 11, 8), "250.00", "Test Payee"));
        AlertMatcher.Alert later = new AlertMatcher.Alert(user, bank, null, TransactionType.DEBIT, new BigDecimal("250.00"), "INR",
                LocalDate.of(2026, 11, 8), "Test Payee", null, Set.of(), "other@otherbank.example", at(LocalDate.of(2026, 11, 8), 20), "m" + (++seq));
        AlertMatcher.Outcome second = receiveEmail(later);

        assertThat(second.created()).isTrue();
        assertThat(second.txn().getId()).isNotEqualTo(first.txn().getId());
    }
}
