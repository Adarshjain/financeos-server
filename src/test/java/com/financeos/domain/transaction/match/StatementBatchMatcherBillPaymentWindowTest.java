package com.financeos.domain.transaction.match;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.financeos.domain.account.Account;
import com.financeos.domain.account.AccountType;
import com.financeos.domain.notification.bill.BillPaymentStore;
import com.financeos.domain.statement.Statement;
import com.financeos.domain.transaction.ReviewStatusManager;
import com.financeos.domain.transaction.ReviewType;
import com.financeos.domain.transaction.Transaction;
import com.financeos.domain.transaction.TransactionSource;
import com.financeos.domain.transaction.TransactionType;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * A card credit the app created to pay a bill (it has a {@code bill_payments} row) pairs with the statement's line up to
 * seven days away instead of the card window of three; every other credit keeps the three-day window.
 */
class StatementBatchMatcherBillPaymentWindowTest {

    private static final LocalDate PAID = LocalDate.of(2026, 10, 30);

    private final MatchFixture fixture = new MatchFixture();
    private final BillPaymentStore store = mock(BillPaymentStore.class);
    private final Set<UUID> billPaymentCredits = new java.util.HashSet<>();
    private final Account card = new Account();
    private final Statement statement = new Statement();

    @BeforeEach
    void setUp() {
        card.setId(UUID.randomUUID());
        card.setType(AccountType.credit_card);
        statement.setId(UUID.randomUUID());
        statement.setPeriodStart(LocalDate.of(2026, 10, 18));
        statement.setPeriodEnd(LocalDate.of(2026, 11, 17));
        when(store.cardTxnIdsAmong(any())).thenAnswer(inv -> {
            java.util.Collection<UUID> asked = inv.getArgument(0);
            return asked.stream().filter(billPaymentCredits::contains).collect(java.util.stream.Collectors.toSet());
        });
        fixture.matcher.setBillPaymentStore(store);
    }

    private Transaction credit(boolean billPayment) {
        Transaction t = new Transaction();
        t.setAccount(card);
        t.setDate(PAID);
        t.setAmount(new BigDecimal("3675"));
        t.setType(TransactionType.CREDIT);
        t.setSource(TransactionSource.manual);
        t.setDescription("Card bill payment");
        new ReviewStatusManager().transitionTo(t, ReviewType.MANUALLY_REVIEWED);
        fixture.store(t);
        if (billPayment) {
            billPaymentCredits.add(t.getId());
        }
        return t;
    }

    private StatementBatchMatcher.Result run(LocalDate lineDate) {
        StatementBatchMatcher.Line line = new StatementBatchMatcher.Line(0, lineDate, new BigDecimal("3675"),
                TransactionType.CREDIT, "PAYMENT RECEIVED - THANK YOU", null, null, null);
        return fixture.matcher.match(statement, card, List.of(line), unpaired -> {
            Map<Integer, Transaction> made = new LinkedHashMap<>();
            for (StatementBatchMatcher.Line l : unpaired) {
                Transaction t = new Transaction();
                t.setAccount(card);
                t.setDate(l.date());
                t.setAmount(l.amount());
                t.setType(l.type());
                t.setSource(TransactionSource.file_upload);
                made.put(l.lineIndex(), fixture.store(t));
            }
            return made;
        });
    }

    @Test
    void aBillPaymentCreditPairsWithALineSevenDaysAway() {
        Transaction credit = credit(true);

        StatementBatchMatcher.Result r = run(PAID.plusDays(7));

        assertThat(r.attached()).isEqualTo(1);
        assertThat(r.created()).isZero();
        assertThat(fixture.txns).containsExactly(credit);
        assertThat(credit.getDate()).isEqualTo(PAID.plusDays(7));
    }

    @Test
    void aBillPaymentCreditDoesNotPairWithALineEightDaysAway() {
        credit(true);

        StatementBatchMatcher.Result r = run(PAID.plusDays(8));

        assertThat(r.attached()).isZero();
        assertThat(r.created()).isEqualTo(1);
    }

    @Test
    void anyOtherCreditKeepsTheThreeDayCardWindow() {
        Transaction plain = credit(false);

        StatementBatchMatcher.Result four = run(PAID.plusDays(4));
        assertThat(four.attached()).isZero();
        assertThat(four.created()).isEqualTo(1);
        assertThat(plain.getDate()).isEqualTo(PAID);
    }

    @Test
    void anyOtherCreditStillPairsWithinThreeDays() {
        Transaction plain = credit(false);

        StatementBatchMatcher.Result r = run(PAID.plusDays(3));

        assertThat(r.attached()).isEqualTo(1);
        assertThat(plain.getDate()).isEqualTo(PAID.plusDays(3));
    }

    @Test
    void withoutTheStoreTheWindowIsUnchanged() {
        fixture.matcher.setBillPaymentStore(null);
        credit(true);

        StatementBatchMatcher.Result r = run(PAID.plusDays(5));

        assertThat(r.attached()).isZero();
    }
}
