package com.financeos.domain.epf;

import com.fasterxml.jackson.databind.JsonNode;
import com.financeos.api.inbox.dto.InboxItemResponse;
import com.financeos.domain.inbox.collect.EpfMatchInboxCollector;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Linking EPF withdrawals to bank credits: link / unlink / record TDS, the credit-only and already-linked rules,
 * suggestions with their window and dismissal, the withdrawal options for a credit, the transaction's EPF ref,
 * and the inbox row.
 */
@SpringBootTest
@AutoConfigureMockMvc
class EpfLinkingIntegrationTest extends EpfIntegrationBase {

    @Autowired EpfMatchInboxCollector inboxCollector;

    private UUID employer;

    private UUID withdrawal(String creditedOn, int employee, int employerShare, Number tds) throws Exception {
        if (employer == null) {
            employer = employer("Acme", null, null);
        }
        return addEntryId(withdrawal(employer, "PARTIAL", creditedOn, employee, employerShare, 0, tds));
    }

    private JsonNode link(UUID entry, UUID transaction, boolean recordTds, int expected) throws Exception {
        return api.putJson(BASE + "/entries/" + entry + "/transaction",
                Map.of("transactionId", transaction.toString(), "recordTds", recordTds), expected);
    }

    @Test
    void linkingAWithdrawalToACreditShowsItAndUnlinkingClearsIt() throws Exception {
        UUID entry = withdrawal("2026-06-01", 1000, 0, null);
        UUID credit = transaction(1000, "2026-06-03", "NEFT EPFO");

        JsonNode linked = link(entry, credit, false, 200);

        JsonNode txn = linked.get("transaction");
        assertEquals(credit.toString(), txn.get("id").asText());
        assertEquals("2026-06-03", txn.get("date").asText());
        assertEquals("NEFT EPFO", txn.get("description").asText());
        assertEquals("EPF Bank", txn.get("accountName").asText());
        assertAmount("1000", txn.get("amount"));
        assertTrue(linked.get("suggestion").isNull());
        assertTrue(api.getJson(BASE + "/entries/" + entry, 200).get("transaction").get("id").asText().equals(credit.toString()));

        api.deleteJson(BASE + "/entries/" + entry + "/transaction", 204);
        assertTrue(api.getJson(BASE + "/entries/" + entry, 200).get("transaction").isNull());
        api.deleteJson(BASE + "/entries/" + entry + "/transaction", 204); // idempotent
    }

    @Test
    void recordTdsFillsTheGapOnlyWhenAsked() throws Exception {
        UUID entry = withdrawal("2026-06-01", 1000, 0, null);
        UUID credit = transaction(900, "2026-06-03", "NEFT EPFO");

        JsonNode noTds = link(entry, credit, false, 200);
        assertTrue(noTds.get("tdsAmount").isNull());

        JsonNode withTds = link(entry, credit, true, 200);
        assertAmount("100", withTds.get("tdsAmount"));
        assertAmount("900", withTds.get("receivedAmount"));

        // A credit at or above the gross never records TDS.
        UUID entry2 = withdrawal("2026-07-01", 500, 0, null);
        UUID bigCredit = transaction(500, "2026-07-02", "NEFT EPFO 2");
        assertTrue(link(entry2, bigCredit, true, 200).get("tdsAmount").isNull());
    }

    @Test
    void linkRulesCreditOnlyOwnedWithdrawalOnlyAndNotAlreadyLinked() throws Exception {
        UUID entry = withdrawal("2026-06-01", 1000, 0, null);
        UUID second = withdrawal("2026-06-02", 1000, 0, null);
        UUID credit = transaction(1000, "2026-06-03", "NEFT EPFO");
        UUID debit = transaction(-1000, "2026-06-03", "Rent");
        UUID contribution = addEntryId(contribution(employer, "2026-05-01", "2026-06-10", 1, 1, 0));

        assertEquals("A withdrawal can only be linked to money coming in.", message(link(entry, debit, false, 400)));
        assertEquals("Only a withdrawal can be linked to a bank transaction.", message(link(contribution, credit, false, 400)));
        link(entry, credit, false, 200);
        link(entry, credit, false, 200); // the same credit again is fine
        assertEquals("This transaction is already linked to another EPF entry.", message(link(second, credit, false, 400)));

        // another user's credit and entry are not found
        link(entry, UUID.randomUUID(), false, 404);
        otherApi.putJson(BASE + "/entries/" + entry + "/transaction",
                Map.of("transactionId", credit.toString(), "recordTds", false), 404);
        otherApi.deleteJson(BASE + "/entries/" + entry + "/transaction", 404);
        otherApi.postJson(BASE + "/entries/" + entry + "/suggestion/dismiss", Map.of("transactionId", credit.toString()), 404);
    }

    @Test
    void aCreditLinkedToALendingCannotBeLinkedToAWithdrawal() throws Exception {
        UUID entry = withdrawal("2026-06-01", 1000, 0, null);
        UUID credit = transaction(1000, "2026-06-03", "Friend repays");
        api.postJson("/api/v1/lendings", Map.of("newCounterpartyName", "Ravi", "direction", "borrowed", "amount", 1000,
                "entryDate", "2026-06-03", "transactionId", credit.toString()), 200);

        assertEquals("This transaction is already linked to a loan, lending or dividend record.",
                message(link(entry, credit, false, 400)));
    }

    @Test
    void creatingAWithdrawalWithATransactionLinksItAndAppliesTheSameChecks() throws Exception {
        employer = employer("Acme", null, null);
        UUID credit = transaction(1000, "2026-06-03", "NEFT EPFO");
        UUID debit = transaction(-1000, "2026-06-03", "Rent");
        Map<String, Object> body = withdrawal(employer, "PARTIAL", "2026-06-01", 1000, 0, 0, null);

        body.put("transactionId", debit.toString());
        assertEquals("A withdrawal can only be linked to money coming in.",
                message(api.postJson(BASE + "/entries", body, 400)));
        body.put("transactionId", credit.toString());
        JsonNode created = api.postJson(BASE + "/entries", body, 201);
        assertEquals(credit.toString(), created.get("transaction").get("id").asText());

        Map<String, Object> again = withdrawal(employer, "PARTIAL", "2026-06-02", 1000, 0, 0, null);
        again.put("transactionId", credit.toString());
        assertEquals("This transaction is already linked to another EPF entry.",
                message(api.postJson(BASE + "/entries", again, 400)));
    }

    @Test
    void suggestionIsAnExactReceivedCreditWithinFifteenDaysClosestFirstAndDismissalRemovesIt() throws Exception {
        UUID entry = withdrawal("2026-06-01", 1000, 0, 100); // received 900
        transaction(900, "2026-06-17", "day 16: outside the window");
        transaction(901, "2026-06-02", "off by one paisa-ish");
        transaction(900, "2026-05-31", "before the credit date");
        UUID far = transaction(900, "2026-06-16", "day 15");

        assertEquals(far.toString(), suggestion(entry).get("id").asText()); // 15 days after is inside the window
        UUID near = transaction(900, "2026-06-04", "day 3");
        JsonNode suggestion = suggestion(entry);
        assertEquals(near.toString(), suggestion.get("id").asText());
        assertEquals("day 3", suggestion.get("description").asText());
        assertEquals("EPF Bank", suggestion.get("accountName").asText());

        api.postJson(BASE + "/entries/" + entry + "/suggestion/dismiss", Map.of("transactionId", near.toString()), 204);
        api.postJson(BASE + "/entries/" + entry + "/suggestion/dismiss", Map.of("transactionId", near.toString()), 204); // idempotent
        assertEquals(far.toString(), suggestion(entry).get("id").asText());

        api.postJson(BASE + "/entries/" + entry + "/suggestion/dismiss", Map.of("transactionId", far.toString()), 204);
        assertTrue(api.getJson(BASE + "/entries/" + entry, 200).get("suggestion").isNull());
    }

    private JsonNode suggestion(UUID entry) throws Exception {
        return api.getJson(BASE + "/entries/" + entry, 200).get("suggestion");
    }

    @Test
    void aCreditLinkedToAnotherEntryIsNotSuggestedAndSuggestionsAreBatchedAcrossAPage() throws Exception {
        UUID first = withdrawal("2026-06-01", 1000, 0, null);
        UUID second = withdrawal("2026-06-20", 1000, 0, null);
        UUID credit = transaction(1000, "2026-06-21", "NEFT EPFO");
        UUID earlierCredit = transaction(1000, "2026-06-05", "NEFT EPFO earlier");

        // 'credit' fits only the second (the first's window ended 2026-06-16); 'earlierCredit' only the first.
        JsonNode page = api.getJson(BASE + "/entries", 200);
        for (JsonNode row : page.get("content")) {
            String expected = row.get("id").asText().equals(first.toString()) ? earlierCredit.toString() : credit.toString();
            assertEquals(expected, row.get("suggestion").get("id").asText());
        }
        link(second, earlierCredit, false, 200);
        // earlierCredit is now taken, so the first has nothing left to be suggested
        assertTrue(suggestion(first).isNull());
    }

    @Test
    void twoWithdrawalsOfTheSameAmountNeverSuggestTheSameCredit() throws Exception {
        UUID first = withdrawal("2026-06-01", 1000, 0, null);
        UUID second = withdrawal("2026-06-02", 1000, 0, null);
        UUID credit = transaction(1000, "2026-06-03", "NEFT EPFO");

        // the credit is 1 day from the second and 2 from the first: the second gets it, the first nothing
        assertTrue(suggestion(first).isNull());
        assertEquals(credit.toString(), suggestion(second).get("id").asText());
        assertEquals(1, inboxCollector.collect(userId, TODAY).size());
        JsonNode options = api.getJson(BASE + "/withdrawals?forTransactionId=" + credit, 200);
        long withSuggestion = 0;
        for (JsonNode o : options) {
            if (!o.get("entry").get("suggestion").isNull()) withSuggestion++;
        }
        assertEquals(1, withSuggestion);

        UUID another = transaction(1000, "2026-06-10", "NEFT EPFO again");
        assertEquals(another.toString(), suggestion(first).get("id").asText()); // the first now gets the other credit
    }

    @Test
    void creditsLinkedToLoansLendingsOrDividendsAreNotSuggested() throws Exception {
        UUID entry = withdrawal("2026-06-01", 1000, 0, null);
        UUID credit = transaction(1000, "2026-06-03", "Friend repays");
        api.postJson("/api/v1/lendings", Map.of("newCounterpartyName", "Ravi", "direction", "borrowed", "amount", 1000,
                "entryDate", "2026-06-03", "transactionId", credit.toString()), 200);

        assertTrue(suggestion(entry).isNull());
        assertTrue(inboxCollector.collect(userId, TODAY).isEmpty());
    }

    @Test
    void anEpfLinkedCreditCannotBeTakenByALending() throws Exception {
        UUID entry = withdrawal("2026-06-01", 1000, 0, null);
        UUID credit = transaction(1000, "2026-06-03", "NEFT EPFO");
        link(entry, credit, false, 200);

        JsonNode error = api.postJson("/api/v1/lendings", Map.of("newCounterpartyName", "Ravi", "direction", "borrowed",
                "amount", 1000, "entryDate", "2026-06-03", "transactionId", credit.toString()), 400);
        assertTrue(message(error).contains("EPF"), message(error));
    }

    @Test
    void theDatabaseAllowsOneEpfEntryPerTransaction() throws Exception {
        UUID e1 = withdrawal("2026-06-01", 1000, 0, null);
        UUID e2 = withdrawal("2026-06-02", 1000, 0, null);
        UUID credit = transaction(1000, "2026-06-03", "NEFT EPFO");
        link(e1, credit, false, 200);
        jdbc.update("UPDATE epf_entry SET transaction_id = NULL WHERE id = ?", e1.toString());
        jdbc.update("UPDATE epf_entry SET transaction_id = ? WHERE id = ?", credit.toString(), e1.toString());
        org.junit.jupiter.api.Assertions.assertThrows(org.springframework.dao.DataIntegrityViolationException.class,
                () -> jdbc.update("UPDATE epf_entry SET transaction_id = ? WHERE id = ?", credit.toString(), e2.toString()));
    }

    @Test
    void withdrawalOptionsForACreditPutSuggestedFirstThenNewestAndShowTheTdsGap() throws Exception {
        UUID exact = withdrawal("2026-05-01", 900, 0, null);          // received 900: suggested for a 900 credit
        UUID withTds = withdrawal("2026-06-01", 1000, 0, 100);        // received 900: suggested, tds already recorded
        UUID gap = withdrawal("2026-04-01", 1000, 0, null);           // gross 1000, credit 900: tds gap 100
        UUID bigger = withdrawal("2026-07-01", 500, 0, null);         // gross 500 < credit: no gap, not suggested
        UUID linkedElsewhere = withdrawal("2026-03-01", 900, 0, null);
        link(linkedElsewhere, transaction(900, "2026-03-02", "other"), false, 200);
        UUID credit = transaction(900, "2026-06-05", "NEFT EPFO");

        JsonNode options = api.getJson(BASE + "/withdrawals?forTransactionId=" + credit, 200);

        assertEquals(4, options.size()); // the linked withdrawal is not offered
        assertEquals(withTds.toString(), options.get(0).get("entry").get("id").asText()); // 2026-06-01 fits the window
        assertTrue(options.get(0).get("suggested").asBoolean());
        assertTrue(options.get(0).get("tdsGap").isNull());
        assertEquals(bigger.toString(), options.get(1).get("entry").get("id").asText());
        assertFalse(options.get(1).get("suggested").asBoolean());
        assertTrue(options.get(1).get("tdsGap").isNull());
        assertEquals(exact.toString(), options.get(2).get("entry").get("id").asText());
        assertFalse(options.get(2).get("suggested").asBoolean()); // right amount but credited 35 days before
        assertEquals(gap.toString(), options.get(3).get("entry").get("id").asText());
        assertAmount("100", options.get(3).get("tdsGap"));

        // without a credit: every unlinked withdrawal, newest first, nothing suggested
        JsonNode all = api.getJson(BASE + "/withdrawals", 200);
        assertEquals(4, all.size());
        assertEquals(bigger.toString(), all.get(0).get("entry").get("id").asText());
        api.getJson(BASE + "/withdrawals?forTransactionId=" + UUID.randomUUID(), 404);
        otherApi.getJson(BASE + "/withdrawals?forTransactionId=" + credit, 404);
    }

    @Test
    void theLinkedCreditCarriesAnEpfRefAndItsDirectionIsLocked() throws Exception {
        UUID entry = withdrawal("2026-06-01", 1000, 0, null);
        UUID credit = transaction(1000, "2026-06-03", "NEFT EPFO");
        link(entry, credit, false, 200);

        JsonNode txn = api.getJson("/api/v1/transactions/" + credit, 200);

        JsonNode ref = txn.get("obligationRefs").get(0);
        assertEquals("EPF_WITHDRAWAL", ref.get("kind").asText());
        assertEquals(entry.toString(), ref.get("id").asText());
        assertEquals("EPF withdrawal", ref.get("label").asText());
        assertAmount("1000", ref.get("amount"));

        JsonNode flip = api.putJson("/api/v1/transactions/" + credit, Map.of("accountId", bank().toString(),
                "date", "2026-06-03", "amount", -1000, "description", "NEFT EPFO"), 400);
        assertTrue(message(flip).contains("unlink it before changing its direction"), message(flip));
    }

    @Test
    void theInboxHasOneRowPerWithdrawalWithAMatchingCreditUntilItIsLinkedOrDismissed() throws Exception {
        UUID entry = withdrawal("2026-06-01", 1234567, 0, null);
        UUID credit = transaction(1234567, "2026-06-03", "NEFT EPFO");
        withdrawal("2026-06-10", 777, 0, null); // no credit: no row

        List<InboxItemResponse> rows = inboxCollector.collect(userId, TODAY);

        assertEquals(1, rows.size());
        InboxItemResponse row = rows.get(0);
        assertEquals("epf_match", row.kind());
        assertEquals("epf_match:" + entry + ":" + credit, row.key());
        assertEquals("needs_look", row.section());
        assertEquals("warning", row.severity());
        assertEquals("EPF withdrawal ₹12,34,567 may match a bank credit", row.title());
        assertEquals("NEFT EPFO · EPF Bank · 3 Jun 2026 · +₹12,34,567", row.subtitle());
        assertEquals("/investments/epf", row.href());
        assertEquals(List.of("epf_link", "dismiss"), row.actions().stream().map(a -> a.type()).toList());
        assertEquals(List.of("Link", "Not this one"), row.actions().stream().map(a -> a.label()).toList());
        assertEquals(entry, row.refs().epfEntryId());
        assertEquals(credit, row.refs().transactionId());

        api.postJson(BASE + "/entries/" + entry + "/suggestion/dismiss", Map.of("transactionId", credit.toString()), 204);
        assertTrue(inboxCollector.collect(userId, TODAY).isEmpty());
        assertTrue(inboxCollector.collect(otherUserId, TODAY).isEmpty());

        api.deleteJson(BASE + "/entries/" + entry, 204);
        assertTrue(inboxCollector.collect(userId, TODAY).isEmpty());
    }

    @Test
    void aLinkedWithdrawalHasNoInboxRow() throws Exception {
        UUID entry = withdrawal("2026-06-01", 1000, 0, null);
        UUID credit = transaction(1000, "2026-06-03", "NEFT EPFO");
        assertEquals(1, inboxCollector.collect(userId, TODAY).size());
        link(entry, credit, false, 200);
        assertTrue(inboxCollector.collect(userId, TODAY).isEmpty());
    }
}
