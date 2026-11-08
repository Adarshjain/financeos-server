package com.financeos.api.bill;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import com.fasterxml.jackson.databind.JsonNode;
import com.financeos.domain.account.Account;
import com.financeos.domain.notification.bill.BillPaymentMetrics;
import com.financeos.domain.notification.bill.BillPaymentStore;
import com.financeos.domain.statement.Statement;
import com.financeos.domain.statement.StatementPersistenceService;
import com.financeos.domain.transaction.Transaction;
import com.financeos.domain.transaction.TransactionType;
import com.financeos.domain.transaction.autolink.AutoLinkStore;
import com.financeos.domain.transaction.autolink.BillPaymentMatcher;
import com.financeos.domain.transaction.autolink.BillPaymentStartupRun;
import com.financeos.domain.transaction.match.StatementBatchMatcher;
import io.micrometer.core.instrument.MeterRegistry;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * The bill-payment matcher end to end on H2: the owner's CRED scenario (debit, pass, credit + AUTO link + PAID by
 * credits, then the statement's line merges into the created credit), every guard, ambiguity both ways, the
 * remembered undo, and unlinking an AUTO link.
 */
class BillPaymentAutoMatchIntegrationTest extends BillPaymentTestBase {

    @Autowired StatementBatchMatcher statementBatchMatcher;
    @Autowired BillPaymentMetrics metrics;
    @Autowired BillPaymentStore paymentStore;
    @Autowired BillPaymentStartupRun startupRun;
    @Autowired BillPaymentMatcher matcher;
    @Autowired MeterRegistry registry;
    @Autowired AutoLinkStore autoLinkStore;

    private double counter(String name, String... tags) {
        var c = registry.find(name).tags(tags).counter();
        return c == null ? 0 : c.count();
    }

    private static BigDecimal bd(JsonNode n) {
        return n.decimalValue().setScale(2, RoundingMode.HALF_UP);
    }

    private JsonNode billOf(JsonNode all, UUID accountId) {
        for (JsonNode n : all) {
            if (accountId.toString().equals(n.path("accountId").asText())) {
                return n;
            }
        }
        throw new AssertionError("no bill for card");
    }

    // ------------------------------------------------------------ the owner's scenario

    @Test
    void aCredDebitPaysTheBillWithACreatedCreditAndTheStatementLineThenMergesIntoIt() throws Exception {
        Statement s = bill("3675");
        UUID debit = bankDebit(due, "3675");   // the due day, "P2M-CRED Club-payment on C-UTIB0000114- HEAD OFFICE"
        double before = counter("financeos.bill_payment.matched", "origin", "AUTO");

        assertTrue(pass().clean());

        // the card-side credit exists, dated by the debit, linked CC_PAYMENT / AUTO
        List<Map<String, Object>> credits = cardCredits();
        assertEquals(1, credits.size());
        UUID credit = UUID.fromString(String.valueOf(credits.get(0).get("id")));
        assertEquals(0, new BigDecimal("3675").compareTo((BigDecimal) credits.get(0).get("amount")));
        Map<String, Object> link = linkOf(debit);
        assertNotNull(link);
        assertEquals("CC_PAYMENT", link.get("type"));
        assertEquals("AUTO", link.get("created_by"));
        assertEquals(link.get("id"), linkOf(credit).get("id"));
        assertEquals(1, paymentRows(s.getId()));
        assertEquals(before + 1, counter("financeos.bill_payment.matched", "origin", "AUTO"), 0.0001);

        // the bill is PAID by the credits (no manual mark) and agrees with the card balance
        JsonNode bill = billOf(api.getJson("/api/v1/bills", 200), card.getId());
        assertEquals("PAID", bill.get("status").asText());
        assertEquals("CREDITS", bill.get("paidSource").asText());
        assertTrue(bill.get("paidMarkedOn").isNull());
        assertEquals(1, bill.get("payments").size());
        JsonNode payment = bill.get("payments").get(0);
        assertEquals(credit.toString(), payment.get("transactionId").asText());
        assertEquals(debit.toString(), payment.get("bankTransactionId").asText());
        assertEquals("Equitas", payment.get("bankAccountName").asText());
        assertEquals("AUTO", payment.get("origin").asText());
        assertEquals(due.toString(), payment.get("date").asText());
        JsonNode account = api.getJson("/api/v1/accounts/" + card.getId(), 200);
        assertEquals(bd(account.get("balance")), bd(bill.get("remainingAmount")).add(bd(bill.get("unbilledAmount"))).negate().setScale(2),
                "remaining + unbilled is what the card balance owes");

        // the next statement lists the payment two days after the debit: it merges into the created credit
        Statement next = statement(card, periodEnd.plusDays(30), periodEnd.plusDays(48), "0");
        StatementBatchMatcher.Result result = statementBatchMatcher.match(next, card, List.of(new StatementBatchMatcher.Line(0,
                due.plusDays(2), new BigDecimal("3675"), TransactionType.CREDIT, "PAYMENT RECEIVED - THANK YOU", null, null, null)),
                unpaired -> {
                    fail("the statement line must merge into the created credit, not create a duplicate");
                    return Map.of();
                });
        assertEquals(1, result.attached());
        assertEquals(0, result.created());
        List<Map<String, Object>> after = cardCredits();
        assertEquals(1, after.size(), "no duplicate credit");
        assertEquals(credit.toString(), after.get(0).get("id"));
        assertTrue(String.valueOf(after.get(0).get("transaction_date")).startsWith(due.plusDays(2).toString()), "the statement's date wins");
        assertEquals(1, paymentRows(s.getId()), "still the payment of the first bill");
        assertTrue(metrics.check().stream().noneMatch(u -> u.userId().equals(userId)), "attached to the later statement: nothing unmerged");
    }

    // ------------------------------------------------------------ guards

    @Test
    void withoutACardBillPayeeSignalNothingIsRecorded() throws Exception {
        Statement s = bill("3675");
        save(bank(), due, "3675", TransactionType.DEBIT, "UPI-ZYXQ WVUT-ZYXQ@OKAXIS-PAYMENT");

        pass();

        assertEquals(0, paymentRows(s.getId()));
        assertEquals(0, cardCredits().size());
    }

    @Test
    void aDebitAlreadyCategorisedCardBillCountsAsThePayeeSignal() throws Exception {
        Statement s = bill("3675");
        UUID debit = save(bank(), due, "3675", TransactionType.DEBIT, "ZYXQ WVUT TRANSFER");
        jdbc.update("UPDATE transactions SET category_key = 'card_bill' WHERE id = ?", debit.toString());

        pass();

        assertEquals(1, paymentRows(s.getId()));
    }

    @Test
    void anAmountOffByOnePaiseIsNotThePayment() throws Exception {
        Statement s = bill("3675");
        bankDebit(due, "3675.01");
        bankDebit(due, "3674.99");

        pass();

        assertEquals(0, paymentRows(s.getId()));
    }

    @Test
    void aDebitOnOrBeforeTheStatementsLastBilledDayIsNotThePayment() throws Exception {
        Statement s = bill("3675");
        bankDebit(periodEnd, "3675");
        bankDebit(periodEnd.minusDays(3), "3675");

        pass();

        assertEquals(0, paymentRows(s.getId()));
    }

    @Test
    void aDebitMoreThanThreeDaysPastTheDueDateIsNotThePaymentButThreeDaysIs() throws Exception {
        Statement s = bill("3675");
        bankDebit(due.plusDays(4), "3675");
        pass();
        assertEquals(0, paymentRows(s.getId()), "4 days late is outside");

        bankDebit(due.plusDays(3), "3675");
        pass();
        assertEquals(1, paymentRows(s.getId()), "3 days past the due date is still this bill's payment");
    }

    @Test
    void aCreditCardAccountDebitOfTheSameAmountIsNeverThePayment() throws Exception {
        Statement s = bill("3675");
        save(card, due, "3675", TransactionType.DEBIT, CRED_TEXT);

        pass();

        assertEquals(0, paymentRows(s.getId()));
    }

    @Test
    void aBillWithAManualMarkOrCreditsAlreadyIsNotMatched() throws Exception {
        Statement marked = bill("3675");
        markPaid(marked.getId(), Map.of());
        bankDebit(due, "3675");
        pass();
        assertEquals(0, paymentRows(marked.getId()), "a manual mark settles the bill");

        Account other = accountRepository.findById(cardAccount("Second Card", "5566")).orElseThrow();
        Statement credited = statement(other, periodEnd, due, "1200");
        save(other, due, "1200", TransactionType.CREDIT, "PAYMENT RECEIVED");
        bankDebit(due, "1200");
        pass();
        assertEquals(0, paymentRows(credited.getId()), "a credit already pays the bill");
    }

    @Test
    void aBillWithNothingDueIsNotMatched() throws Exception {
        Statement zero = bill("0");
        bankDebit(due, "0.50");
        pass();
        assertEquals(0, paymentRows(zero.getId()));
    }

    // ------------------------------------------------------------ ambiguity, both ways

    @Test
    void twoDebitsForOneBillAreOnlySuggestedAndOneGoneMakesTheOtherTheMatch() throws Exception {
        Statement s = bill("3675");
        UUID first = bankDebit(due, "3675");
        UUID second = bankDebit(due.plusDays(1), "3675");
        double ambiguous = counter("financeos.bill_payment.ambiguous");

        pass();

        assertEquals(0, paymentRows(s.getId()), "two debits fit the bill: nothing is recorded");
        assertEquals(ambiguous + 2, counter("financeos.bill_payment.ambiguous"), 0.0001);
        assertEquals(0, count("SELECT COUNT(*) FROM link_suggestions WHERE user_id = ?", userId.toString()), "ambiguity is never stored");
        assertEquals(0, api.getJson("/api/v1/link-suggestions", 200).size());

        pass();
        assertEquals(ambiguous + 2, counter("financeos.bill_payment.ambiguous"), 0.0001, "a known suggestion is not counted again");

        jdbc.update("DELETE FROM transactions WHERE id = ?", second.toString());
        pass();
        assertEquals(1, paymentRows(s.getId()), "the remaining debit now fits only this bill");
    }

    @Test
    void oneDebitFittingTwoBillsIsOnlySuggested() throws Exception {
        Statement a = bill("3675");
        Account other = accountRepository.findById(cardAccount("Second Card", "5566")).orElseThrow();
        Statement b = statement(other, periodEnd, due, "3675");
        UUID debit = bankDebit(due, "3675");
        double ambiguous = counter("financeos.bill_payment.ambiguous");

        pass();

        assertEquals(0, paymentRows(a.getId()));
        assertEquals(0, paymentRows(b.getId()));
        assertEquals(ambiguous + 1, counter("financeos.bill_payment.ambiguous"), 0.0001);
        assertEquals(0, count("SELECT COUNT(*) FROM link_suggestions WHERE user_id = ?", userId.toString()));
        assertTrue(exists(debit));
        assertNull(linkOf(debit));
    }

    // ------------------------------------------------------------ undo is remembered

    @Test
    void undoingAnAutoPaymentDeletesTheCreatedCreditKeepsTheDebitAndTheMatcherNeverReAddsIt() throws Exception {
        Statement s = bill("3675");
        UUID debit = bankDebit(due, "3675");
        pass();
        assertEquals(1, paymentRows(s.getId()));

        JsonNode bill = api.deleteJson("/api/v1/bills/" + s.getId() + "/mark-paid", 200);

        assertEquals("OVERDUE", bill.get("status").asText());
        assertEquals(0, bill.get("payments").size());
        assertEquals(0, paymentRows(s.getId()));
        assertEquals(0, cardCredits().size(), "the created credit is deleted");
        assertTrue(exists(debit), "the user's own bank debit stays");
        assertNull(linkOf(debit));
        assertEquals(1, count("SELECT COUNT(*) FROM auto_link_rejections WHERE user_id = ? AND kind = 'BILL_PAYMENT' AND transaction_id = ? AND other_ref = ?",
                userId.toString(), debit.toString(), s.getId().toString()));

        pass();
        assertEquals(0, paymentRows(s.getId()), "the remembered undo is respected");
        assertEquals(0, cardCredits().size());
    }

    @Test
    void undoingKeepsACreditThatAStatementHasMergedInto() throws Exception {
        Statement s = bill("3675");
        bankDebit(due, "3675");
        pass();
        Statement next = statement(card, periodEnd.plusDays(30), periodEnd.plusDays(48), "0");
        statementBatchMatcher.match(next, card, List.of(new StatementBatchMatcher.Line(0, due.plusDays(1), new BigDecimal("3675"),
                TransactionType.CREDIT, "PAYMENT RECEIVED", null, null, null)), unpaired -> Map.of());
        assertEquals(1, cardCredits().size());

        api.deleteJson("/api/v1/bills/" + s.getId() + "/mark-paid", 200);

        assertEquals(1, cardCredits().size(), "the real line of the statement stays");
        assertEquals(0, paymentRows(s.getId()));
    }

    // ------------------------------------------------------------ AUTO aborts under the card lock

    @Autowired com.financeos.domain.notification.bill.BillPaymentService paymentService;

    private com.financeos.domain.notification.bill.BillPaymentStore.Row autoRecord(UUID statementId, UUID debit) {
        return paymentService.record(userId, statementId,
                new com.financeos.domain.notification.bill.BillPaymentService.Source.ExistingDebit(debit),
                com.financeos.domain.notification.bill.BillPaymentOrigin.AUTO);
    }

    @Test
    void anAutoPaymentIsANoOpWhenTheBillGotAManualMarkMeanwhile() throws Exception {
        Statement s = bill("3675");
        UUID debit = bankDebit(due, "3675");
        markPaid(s.getId(), Map.of());

        assertNull(autoRecord(s.getId(), debit));

        assertEquals(0, paymentRows(s.getId()));
        assertEquals(0, cardCredits().size());
        assertNull(linkOf(debit));
    }

    @Test
    void anAutoPaymentIsANoOpWhenTheBillAlreadyHasAPaymentRow() throws Exception {
        Statement s = bill("3675");
        UUID first = bankDebit(due, "3675");
        assertNotNull(autoRecord(s.getId(), first));
        UUID second = bankDebit(due.plusDays(1), "3675");

        // the first payment's credit is also after the cutoff, so remove it to isolate the "row exists" condition
        jdbc.update("UPDATE transactions SET transaction_date = ? WHERE account_id = ?", java.sql.Date.valueOf(periodEnd.minusDays(1)), card.getId().toString());
        assertNull(autoRecord(s.getId(), second));

        assertEquals(1, paymentRows(s.getId()));
        assertNull(linkOf(second));
    }

    @Test
    void anAutoPaymentIsANoOpWhenCreditsAfterTheLastBilledDayAlreadyPayTheBill() throws Exception {
        Statement s = bill("3675");
        UUID debit = bankDebit(due, "3675");
        save(card, due, "100", TransactionType.CREDIT, "REFUND");

        assertNull(autoRecord(s.getId(), debit));

        assertEquals(0, paymentRows(s.getId()));
        assertNull(linkOf(debit));
        assertEquals(1, cardCredits().size(), "only the existing credit");
    }

    @Test
    void anAutoPaymentRecordsWhenTheBillIsStillOpen() throws Exception {
        Statement s = bill("3675");
        UUID debit = bankDebit(due, "3675");

        assertNotNull(autoRecord(s.getId(), debit));
        assertEquals(1, paymentRows(s.getId()));
    }

    // ------------------------------------------------------------ unlinking

    @Test
    void unlinkingAnAutoPaymentLinkTakesTheCreatedCreditBackAndRemembersIt() throws Exception {
        Statement s = bill("3675");
        UUID debit = bankDebit(due, "3675");
        pass();
        String linkId = String.valueOf(linkOf(debit).get("id"));

        api.deleteJson("/api/v1/transaction-links/" + linkId, 204);

        assertEquals(0, cardCredits().size());
        assertEquals(0, paymentRows(s.getId()));
        assertTrue(exists(debit));
        assertEquals(1, rejections("BILL_PAYMENT"));
        pass();
        assertEquals(0, paymentRows(s.getId()), "not re-added after the unlink");
    }

    // ------------------------------------------------------------ metrics, start-up

    @Test
    void theUnmergedGaugeAndCheckFlagAPaymentInsideALaterStatementThatIsNotOnIt() throws Exception {
        Statement s = bill("3675");
        bankDebit(due, "3675");
        pass();
        // a statement imported afterwards covers the payment's date, but its lines do not list the payment
        Statement later = statement(card, periodEnd.plusDays(30), periodEnd.plusDays(48), "0");

        List<BillPaymentStore.Unmerged> unmerged = metrics.check().stream().filter(u -> u.userId().equals(userId)).toList();

        assertEquals(1, unmerged.size());
        assertEquals(s.getId(), unmerged.get(0).statementId());
        assertEquals(later.getId(), unmerged.get(0).laterStatementId());
        assertTrue(registry.get("financeos.bill_payment.unmerged").gauge().value() >= 1.0, "the gauge counts it (it counts every user's)");
    }

    @Test
    void theStartUpRunMatchesBillsThatWereAlreadyOpen() throws Exception {
        Statement s = bill("3675");
        bankDebit(due, "3675");

        int linked = startupRun.runAll();

        assertTrue(linked >= 1);
        assertEquals(1, paymentRows(s.getId()));
        startupRun.runAll();
        assertEquals(1, paymentRows(s.getId()), "idempotent");
    }

    @Test
    void aScopedPassStillLooksAtEveryOpenBill() throws Exception {
        Statement s = bill("3675");
        UUID debit = bankDebit(due, "3675");

        // a scope far from both the debit and the bill
        autoLinkService.run(userId, com.financeos.domain.transaction.autolink.LinkScope.of(today.minusDays(1), today));

        assertEquals(1, paymentRows(s.getId()));
        assertNotNull(linkOf(debit));
    }
}
