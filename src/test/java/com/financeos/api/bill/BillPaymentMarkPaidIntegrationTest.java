package com.financeos.api.bill;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.financeos.domain.statement.Statement;
import com.financeos.domain.statement.StatementPersistenceService;
import com.financeos.domain.transaction.TransactionType;
import com.financeos.domain.transaction.match.StatementBatchMatcher;
import com.financeos.support.ApiTestClient;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * "Mark paid" that also records the payment (POST /bills/{id}/mark-paid with a {@code payment}), the undo that takes it
 * back, the validation errors, the payment candidates endpoint and the {@code payments} of the bill response.
 */
class BillPaymentMarkPaidIntegrationTest extends BillPaymentTestBase {

    @Autowired StatementBatchMatcher statementBatchMatcher;

    private static BigDecimal bd(JsonNode n) {
        return n.decimalValue().setScale(2, RoundingMode.HALF_UP);
    }

    private static Map<String, Object> body(Object amount, Object paidOn, Map<String, Object> payment) {
        Map<String, Object> m = new LinkedHashMap<>();
        if (amount != null) m.put("amount", amount);
        if (paidOn != null) m.put("paidOn", paidOn.toString());
        if (payment != null) m.put("payment", payment);
        return m;
    }

    private static Map<String, Object> existing(UUID txn) {
        return Map.of("mode", "EXISTING", "fromTransactionId", txn.toString());
    }

    private static Map<String, Object> newFrom(UUID account) {
        return Map.of("mode", "NEW", "fromAccountId", account.toString());
    }

    // ------------------------------------------------------------ modes

    @Test
    void existingModePaysFromTheDebitAndLinksThemByTheUser() throws Exception {
        Statement s = bill("3675");
        UUID debit = save(bank(), due.plusDays(1), "3675", TransactionType.DEBIT, "UNRELATED NARRATION");

        JsonNode bill = markPaid(s.getId(), body(null, null, existing(debit)));

        assertEquals("PAID", bill.get("status").asText());
        assertEquals("CREDITS", bill.get("paidSource").asText());
        assertTrue(bill.get("paidMarkedOn").isNull(), "no manual mark when a transaction is recorded");
        JsonNode p = bill.get("payments").get(0);
        assertEquals("USER", p.get("origin").asText());
        assertEquals(debit.toString(), p.get("bankTransactionId").asText());
        assertEquals("Equitas", p.get("bankAccountName").asText());
        assertEquals(due.plusDays(1).toString(), p.get("date").asText(), "the debit's date is the payment's");
        assertEquals(0, new BigDecimal("3675").compareTo(bd(p.get("amount"))));
        Map<String, Object> link = linkOf(debit);
        assertEquals("CC_PAYMENT", link.get("type"));
        assertEquals("USER", link.get("created_by"));
        assertEquals(1, cardCredits().size());
        assertEquals(1, txnCount(bankId), "no second bank debit");
        assertEquals("card_bill", jdbc.queryForObject("SELECT category_key FROM transactions WHERE id = ?", String.class, p.get("transactionId").asText()));
    }

    @Test
    void newModeWithAnAccountCreatesTheBankDebitAndTheCreditAndLinksThem() throws Exception {
        Statement s = bill("3675");

        JsonNode bill = markPaid(s.getId(), body(null, due, newFrom(bankId)));

        assertEquals("PAID", bill.get("status").asText());
        JsonNode p = bill.get("payments").get(0);
        String debitId = p.get("bankTransactionId").asText();
        assertNotNull(debitId);
        assertEquals(due.toString(), p.get("date").asText());
        assertEquals(1, txnCount(bankId));
        assertEquals("DEBIT", jdbc.queryForObject("SELECT type FROM transactions WHERE id = ?", String.class, debitId));
        assertEquals("manual", jdbc.queryForObject("SELECT source FROM transactions WHERE id = ?", String.class, debitId));
        assertEquals("card_bill", jdbc.queryForObject("SELECT category_key FROM transactions WHERE id = ?", String.class, debitId));
        assertEquals("CC_PAYMENT", linkOf(UUID.fromString(debitId)).get("type"));
        assertEquals(1, count("SELECT COUNT(*) FROM bill_payments WHERE statement_id = ? AND bank_txn_created = 1", s.getId().toString()));
    }

    @Test
    void newModeWithoutAnAccountCreatesOnlyTheCardSideCredit() throws Exception {
        Statement s = bill("3675");

        JsonNode bill = markPaid(s.getId(), body(null, null, Map.of("mode", "NEW")));

        assertEquals("PAID", bill.get("status").asText());
        JsonNode p = bill.get("payments").get(0);
        assertTrue(p.get("bankTransactionId").isNull());
        assertTrue(p.get("bankAccountName").isNull());
        assertEquals(today.toString(), p.get("date").asText(), "paid today by default");
        assertEquals(0, txnCount(bankId));
        assertNull(linkOf(UUID.fromString(p.get("transactionId").asText())));
    }

    @Test
    void partPaymentsAddUpAndTheSecondDefaultsToWhatIsLeft() throws Exception {
        Statement s = bill("3675");

        JsonNode first = markPaid(s.getId(), body(1000, due, newFrom(bankId)));
        assertEquals("OVERDUE", first.get("status").asText(), "due date passed, still partly unpaid");
        assertEquals(0, new BigDecimal("2675").compareTo(bd(first.get("remainingAmount"))));
        assertEquals(1, first.get("payments").size());

        JsonNode second = markPaid(s.getId(), body(null, null, Map.of("mode", "NEW")));
        assertEquals("PAID", second.get("status").asText());
        assertEquals(2, second.get("payments").size());
        assertEquals(0, new BigDecimal("2675").compareTo(bd(second.get("payments").get(1).get("amount"))));
    }

    @Test
    void noneModeAndAMissingPaymentAreTheManualMarkOnly() throws Exception {
        Statement s = bill("3675");
        JsonNode none = markPaid(s.getId(), body(null, null, Map.of("mode", "NONE")));
        assertEquals("MANUAL", none.get("paidSource").asText());
        assertFalse(none.get("paidMarkedOn").isNull());
        assertEquals(0, none.get("payments").size());
        assertEquals(0, paymentRows(s.getId()));
        assertEquals(0, cardCredits().size());

        Statement other = statement(card, periodEnd.minusDays(40), due.minusDays(40), "500");
        JsonNode plain = markPaid(other.getId(), Map.of());
        assertEquals("MANUAL", plain.get("paidSource").asText());
        assertEquals(0, plain.get("payments").size());
    }

    // ------------------------------------------------------------ undo

    @Test
    void undoDeletesTheCreatedCreditAndDebitAndClearsTheManualMarkToo() throws Exception {
        Statement s = bill("3675");
        JsonNode paid = markPaid(s.getId(), body(null, due, newFrom(bankId)));
        UUID credit = UUID.fromString(paid.get("payments").get(0).get("transactionId").asText());
        UUID debit = UUID.fromString(paid.get("payments").get(0).get("bankTransactionId").asText());

        JsonNode bill = api.deleteJson("/api/v1/bills/" + s.getId() + "/mark-paid", 200);

        assertEquals("OVERDUE", bill.get("status").asText());
        assertEquals(0, bill.get("payments").size());
        assertFalse(exists(credit));
        assertFalse(exists(debit));
        assertEquals(0, paymentRows(s.getId()));
        assertEquals(0, rejections("BILL_PAYMENT"), "a user payment is not remembered against the matcher");

        markPaid(s.getId(), body(null, null, null));   // a manual mark, then undo clears it as before
        JsonNode cleared = api.deleteJson("/api/v1/bills/" + s.getId() + "/mark-paid", 200);
        assertTrue(cleared.get("paidMarkedOn").isNull());
    }

    @Test
    void undoKeepsTheExistingDebitTheUserOwnedAndDeletesOnlyTheCreatedCredit() throws Exception {
        Statement s = bill("3675");
        UUID debit = save(bank(), due, "3675", TransactionType.DEBIT, "UNRELATED NARRATION");
        markPaid(s.getId(), body(null, null, existing(debit)));

        api.deleteJson("/api/v1/bills/" + s.getId() + "/mark-paid", 200);

        assertTrue(exists(debit));
        assertNull(linkOf(debit));
        assertEquals(0, cardCredits().size());
        assertEquals(0, paymentRows(s.getId()));
        assertEquals(1, count("SELECT COUNT(*) FROM auto_link_rejections WHERE user_id = ? AND kind = 'BILL_PAYMENT' AND transaction_id = ? AND other_ref = ?",
                userId.toString(), debit.toString(), s.getId().toString()), "the freed debit must not pay this bill again");
        pass();
        assertEquals(0, paymentRows(s.getId()), "the next auto pass does not re-pay the bill from the debit");
    }

    @Test
    void undoKeepsACreatedDebitThatAStatementHasLinkedAndTheCreditThatAStatementMergedInto() throws Exception {
        Statement s = bill("3675");
        JsonNode paid = markPaid(s.getId(), body(null, due, newFrom(bankId)));
        UUID credit = UUID.fromString(paid.get("payments").get(0).get("transactionId").asText());
        UUID debit = UUID.fromString(paid.get("payments").get(0).get("bankTransactionId").asText());
        Statement next = statement(card, periodEnd.plusDays(30), periodEnd.plusDays(48), "0");
        statementBatchMatcher.match(next, card, List.of(new StatementBatchMatcher.Line(0, due, new BigDecimal("3675"),
                TransactionType.CREDIT, "PAYMENT RECEIVED", null, null, null)), unpaired -> Map.of());
        // the bank's own statement lists the created debit
        statementPersistenceService.linkTransactions(next.getId(), List.of(new StatementPersistenceService.TxnLink(debit, 1, null, null)));

        api.deleteJson("/api/v1/bills/" + s.getId() + "/mark-paid", 200);

        assertTrue(exists(credit), "merged into by the statement line");
        assertTrue(exists(debit), "listed by a statement");
        assertEquals(0, paymentRows(s.getId()));
        assertNull(linkOf(debit), "the link is gone");
        pass();
        assertEquals(0, paymentRows(s.getId()));
        assertNull(linkOf(debit), "the kept pair is not paired again behind the undo");
    }

    @Test
    void unlinkingAUserPaymentLinkKeepsBothTransactions() throws Exception {
        Statement s = bill("3675");
        UUID debit = save(bank(), due, "3675", TransactionType.DEBIT, "UNRELATED NARRATION");
        markPaid(s.getId(), body(null, null, existing(debit)));
        String linkId = String.valueOf(linkOf(debit).get("id"));

        api.deleteJson("/api/v1/transaction-links/" + linkId, 204);

        assertTrue(exists(debit));
        assertEquals(1, cardCredits().size(), "a user payment keeps its credit");
        assertEquals(0, paymentRows(s.getId()));
        assertEquals(0, rejections("BILL_PAYMENT"));
    }

    // ------------------------------------------------------------ validation

    @Test
    void aPaymentDatedOnOrBeforeTheStatementDateOrInTheFutureIsRejected() throws Exception {
        Statement s = bill("3675");
        api.postJson("/api/v1/bills/" + s.getId() + "/mark-paid", body(null, periodEnd, newFrom(bankId)), 400);
        api.postJson("/api/v1/bills/" + s.getId() + "/mark-paid", body(null, periodEnd.minusDays(2), Map.of("mode", "NEW")), 400);
        api.postJson("/api/v1/bills/" + s.getId() + "/mark-paid", body(null, today.plusDays(1), newFrom(bankId)), 400);
        UUID early = save(bank(), periodEnd, "3675", TransactionType.DEBIT, "EARLY");
        api.postJson("/api/v1/bills/" + s.getId() + "/mark-paid", body(null, null, existing(early)), 400);
        assertEquals(0, paymentRows(s.getId()));
        assertEquals(1, txnCount(bankId), "nothing was created by the rejected calls");
        assertEquals(0, cardCredits().size());
    }

    @Test
    void existingModeNeedsAFreeOwnBankDebitOfTheSameAmount() throws Exception {
        Statement s = bill("3675");
        api.postJson("/api/v1/bills/" + s.getId() + "/mark-paid", body(null, null, Map.of("mode", "EXISTING")), 400);

        UUID credit = save(bank(), due, "3675", TransactionType.CREDIT, "A CREDIT");
        api.postJson("/api/v1/bills/" + s.getId() + "/mark-paid", body(null, null, existing(credit)), 400);

        UUID onCard = save(card, due, "3675", TransactionType.DEBIT, "CARD PURCHASE");
        api.postJson("/api/v1/bills/" + s.getId() + "/mark-paid", body(null, null, existing(onCard)), 400);

        UUID debit = save(bank(), due, "3675", TransactionType.DEBIT, "DEBIT");
        api.postJson("/api/v1/bills/" + s.getId() + "/mark-paid", body(1000, null, existing(debit)), 400);

        UUID linked = save(bank(), due, "50", TransactionType.DEBIT, "TO SAVINGS");
        UUID partner = save(accountRepository.findById(bankAccount(api, "Savings")).orElseThrow(), due, "50", TransactionType.CREDIT, "FROM CHECKING");
        api.postJson("/api/v1/transaction-links", Map.of("type", "TRANSFER", "members", List.of(
                Map.of("transactionId", linked.toString(), "isAnchor", true),
                Map.of("transactionId", partner.toString(), "isAnchor", false))), 201);
        api.postJson("/api/v1/bills/" + s.getId() + "/mark-paid", body(null, null, existing(linked)), 400);

        api.postJson("/api/v1/bills/" + s.getId() + "/mark-paid", body(null, null, existing(UUID.randomUUID())), 404);

        String email = "bill-pay-other-" + UUID.randomUUID() + "@example.test";
        ApiTestClient stranger = ApiTestClient.signUp(mockMvc, mapper, email);
        extraUsers.add(userRepository.findByEmail(email).orElseThrow().getId());
        UUID theirs = stranger.create("/api/v1/transactions", Map.of("accountId", bankAccount(stranger, "Their bank").toString(),
                "date", due.toString(), "amount", -3675, "description", "THEIR DEBIT"));
        assertEquals(404, api.post("/api/v1/bills/" + s.getId() + "/mark-paid", body(null, null, existing(theirs))).getStatus(),
                "someone else's debit looks exactly like a missing one");
        assertEquals(404, api.post("/api/v1/bills/" + s.getId() + "/mark-paid", body(3675, null, existing(theirs))).getStatus(),
                "also with an amount, which is never compared before ownership");
        assertEquals(0, paymentRows(s.getId()));
        assertEquals(1, cardCredits().size(), "only the card purchase used above: no credit was created");

        markPaid(s.getId(), body(null, null, existing(debit)));
        UUID second = save(bank(), due, "3675", TransactionType.DEBIT, "DEBIT AGAIN");
        Statement other = statement(card, periodEnd.minusDays(40), due.minusDays(40), "100");
        api.postJson("/api/v1/bills/" + other.getId() + "/mark-paid", body(null, null, existing(debit)), 400);
        assertTrue(exists(second));
    }

    @Test
    void newModeNeedsAnOwnNonCardAccountAndSomethingLeftToPay() throws Exception {
        Statement s = bill("3675");
        api.postJson("/api/v1/bills/" + s.getId() + "/mark-paid", body(null, null, newFrom(card.getId())), 400);

        String email = "bill-pay-other-" + UUID.randomUUID() + "@example.test";
        ApiTestClient other = ApiTestClient.signUp(mockMvc, mapper, email);
        extraUsers.add(userRepository.findByEmail(email).orElseThrow().getId());
        UUID foreign = bankAccount(other, "Their bank");
        api.postJson("/api/v1/bills/" + s.getId() + "/mark-paid", body(null, null, newFrom(foreign)), 400);
        api.postJson("/api/v1/bills/" + s.getId() + "/mark-paid", body(0, null, newFrom(bankId)), 400);
        assertEquals(0, paymentRows(s.getId()));

        markPaid(s.getId(), body(null, due, newFrom(bankId)));
        api.postJson("/api/v1/bills/" + s.getId() + "/mark-paid", body(null, null, Map.of("mode", "NEW")), 400);
        assertEquals(1, paymentRows(s.getId()));
    }

    @Test
    void anotherUsersBillCannotBePaid() throws Exception {
        Statement s = bill("3675");
        String email = "bill-pay-other-" + UUID.randomUUID() + "@example.test";
        ApiTestClient other = ApiTestClient.signUp(mockMvc, mapper, email);
        extraUsers.add(userRepository.findByEmail(email).orElseThrow().getId());

        other.postJson("/api/v1/bills/" + s.getId() + "/mark-paid", body(null, due, Map.of("mode", "NEW")), 400);
        assertEquals(0, paymentRows(s.getId()));
    }

    // ------------------------------------------------------------ candidates

    @Test
    void paymentCandidatesAreFreeBankDebitsOfTheAmountPayeeSignalFirstThenNearestTheDueDate() throws Exception {
        Statement s = bill("3675");
        UUID farSignal = bankDebit(due.plusDays(4), "3675");
        UUID nearNoSignal = save(bank(), due, "3675", TransactionType.DEBIT, "ZYXQ TRANSFER");
        UUID nearSignal = bankDebit(due.minusDays(1), "3675");
        save(bank(), due, "3600", TransactionType.DEBIT, CRED_TEXT);                // other amount
        save(bank(), periodEnd, "3675", TransactionType.DEBIT, CRED_TEXT);           // on the last billed day
        save(bank(), due.plusDays(11), "3675", TransactionType.DEBIT, CRED_TEXT);    // past due + 10
        save(card, due, "3675", TransactionType.DEBIT, CRED_TEXT);                   // a card account
        UUID linked = bankDebit(due, "3675");
        UUID partner = save(accountRepository.findById(bankAccount(api, "Savings")).orElseThrow(), due, "3675", TransactionType.CREDIT, "FROM ELSEWHERE");
        api.postJson("/api/v1/transaction-links", Map.of("type", "TRANSFER", "members", List.of(
                Map.of("transactionId", linked.toString(), "isAnchor", true),
                Map.of("transactionId", partner.toString(), "isAnchor", false))), 201);

        JsonNode list = api.getJson("/api/v1/bills/" + s.getId() + "/payment-candidates", 200);

        assertEquals(3, list.size());
        assertEquals(nearSignal.toString(), list.get(0).get("transactionId").asText());
        assertEquals(farSignal.toString(), list.get(1).get("transactionId").asText());
        assertEquals(nearNoSignal.toString(), list.get(2).get("transactionId").asText());
        JsonNode first = list.get(0);
        assertTrue(first.get("payeeSignal").asBoolean());
        assertFalse(list.get(2).get("payeeSignal").asBoolean());
        assertEquals(bankId.toString(), first.get("accountId").asText());
        assertEquals("Equitas", first.get("accountName").asText());
        assertEquals(due.minusDays(1).toString(), first.get("date").asText());
        assertEquals(0, new BigDecimal("3675").compareTo(bd(first.get("amount"))));
        assertEquals(CRED_TEXT, first.get("description").asText());

        assertEquals(1, api.getJson("/api/v1/bills/" + s.getId() + "/payment-candidates?amount=3600", 200).size());
        assertEquals(0, api.getJson("/api/v1/bills/" + s.getId() + "/payment-candidates?amount=1", 200).size());
    }

    @Test
    void aDebitThatAlreadyPaysABillIsNotACandidateAndPaidBillsDefaultToNoCandidates() throws Exception {
        Statement s = bill("3675");
        UUID debit = bankDebit(due, "3675");
        markPaid(s.getId(), body(null, null, existing(debit)));

        assertEquals(0, api.getJson("/api/v1/bills/" + s.getId() + "/payment-candidates", 200).size(), "paid: nothing left");
        assertEquals(0, api.getJson("/api/v1/bills/" + s.getId() + "/payment-candidates?amount=3675", 200).size(), "the debit pays a bill");
    }

    @Test
    void theBillResponsesCarryThePaymentsOnEveryReadPath() throws Exception {
        Statement s = bill("3675");
        markPaid(s.getId(), body(null, due, newFrom(bankId)));

        assertEquals(1, billJson(s.getId()).get("payments").size());
        JsonNode listed = api.getJson("/api/v1/bills", 200);
        assertEquals(1, listed.get(0).get("payments").size());
        JsonNode history = api.getJson("/api/v1/bills/history?accountId=" + card.getId(), 200);
        assertEquals(1, history.get(0).get("payments").size());
        Statement other = statement(card, periodEnd.minusDays(40), due.minusDays(40), "500");
        assertEquals(0, billJson(other.getId()).get("payments").size());
    }
}
