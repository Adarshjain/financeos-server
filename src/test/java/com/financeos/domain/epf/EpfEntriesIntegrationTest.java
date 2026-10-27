package com.financeos.domain.epf;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** EPF entry reads and writes: ledger order, running balances, paging, validation, imported rows, transfer pairs, tenancy. */
@SpringBootTest
@AutoConfigureMockMvc
class EpfEntriesIntegrationTest extends EpfIntegrationBase {

    private static final String IMPORTED_MESSAGE = "Imported entries can't change. Upload a newer passbook to change them.";

    private String error(Map<String, Object> body) throws Exception {
        return message(api.postJson(BASE + "/entries", body, 400));
    }

    @Test
    void entriesComeNewestFirstWithTheEmployersRunningBalanceAndPageAndFilter() throws Exception {
        UUID a = employer("Acme", "2023-01-01", null);
        UUID b = employer("Beta", "2020-01-01", "2022-12-31");
        addEntry(body("OPENING_BALANCE", b, "2022-03-31", 500, 500));         // FY 2021
        addEntry(contribution(a, "2024-03-01", "2024-04-10", 100, 50, 0));    // FY 2024
        addEntry(contribution(a, "2024-04-01", "2024-05-10", 100, 50, 0));    // FY 2024
        addEntry(interest(a, 2024, "2025-03-31", 10, 5));                     // FY 2024
        addEntry(contribution(a, "2025-04-01", "2025-05-10", 200, 100, 0));   // FY 2025

        JsonNode page = api.getJson(BASE + "/entries?size=3", 200);

        assertEquals(5, page.get("totalElements").asInt());
        assertEquals(3, page.get("content").size());
        JsonNode first = page.get("content").get(0);
        assertEquals("2025-05-10", first.get("creditedOn").asText());
        assertEquals("Acme", first.get("employerName").asText());
        assertAmount("615", first.get("runningBalance")); // 150 + 150 + 15 + 300
        assertAmount("315", page.get("content").get(1).get("runningBalance"));
        assertAmount("300", page.get("content").get(2).get("runningBalance"));
        JsonNode second = api.getJson(BASE + "/entries?size=3&page=1", 200);
        assertEquals(2, second.get("content").size());
        assertAmount("1000", second.get("content").get(1).get("runningBalance")); // Beta's own balance

        assertEquals(3, api.getJson(BASE + "/entries?fy=2024", 200).get("totalElements").asInt());
        assertEquals(1, api.getJson(BASE + "/entries?employerId=" + b, 200).get("totalElements").asInt());
        assertEquals(0, api.getJson(BASE + "/entries?employerId=" + b + "&fy=2024", 200).get("totalElements").asInt());

        assertEquals(java.util.List.of(2025, 2024, 2021), ints(api.getJson(BASE + "/fys", 200)));
        assertEquals(java.util.List.of(2021), ints(api.getJson(BASE + "/fys?employerId=" + b, 200)));
        api.getJson(BASE + "/fys?employerId=" + UUID.randomUUID(), 404);
    }

    private static java.util.List<Integer> ints(JsonNode array) {
        java.util.List<Integer> out = new java.util.ArrayList<>();
        array.forEach(n -> out.add(n.asInt()));
        return out;
    }

    @Test
    void creationRulesPerEntryType() throws Exception {
        UUID a = employer("Acme", null, null);
        UUID b = employer("Beta", null, null);

        Map<String, Object> noWageMonth = body("CONTRIBUTION", a, "2026-05-10", 100, 100);
        assertEquals("Wage month is required for a contribution.", error(noWageMonth));
        Map<String, Object> noFy = body("INTEREST", a, "2026-03-31", 10, 10);
        assertEquals("Financial year is required for interest.", error(noFy));
        Map<String, Object> noKind = body("WITHDRAWAL", a, "2026-05-10", 100, 100);
        assertEquals("Withdrawal kind is required.", error(noKind));
        assertEquals("A pension withdrawal needs a pension amount.", error(withdrawal(a, "PENSION", "2026-05-10", 0, 0, 0, null)));
        assertEquals("A pension withdrawal has no employee or employer amount.",
                error(withdrawal(a, "PENSION", "2026-05-10", 5, 0, 900, null)));
        assertEquals("A withdrawal needs an employee or employer amount.", error(withdrawal(a, "PARTIAL", "2026-05-10", 0, 0, 0, null)));
        assertEquals("TDS must be less than the withdrawal amount.", error(withdrawal(a, "PARTIAL", "2026-05-10", 600, 400, 0, 1000)));
        assertEquals("Amounts can't be negative.", error(body("OPENING_BALANCE", a, "2026-05-10", -1, 0)));
        assertEquals("Amounts can't be negative.", error(withdrawal(a, "PARTIAL", "2026-05-10", 600, 400, 0, -5)));
        assertEquals("TDS applies to withdrawals only.", error(withTds(body("OPENING_BALANCE", a, "2026-05-10", 1, 0), 5)));
        assertEquals("A transfer out is created from the transfer in on the other employer.",
                error(body("TRANSFER_OUT", a, "2026-05-10", 1, 0)));
        Map<String, Object> self = body("TRANSFER_IN", a, "2026-05-10", 1, 0);
        self.put("fromEmployerId", a.toString());
        assertEquals("A transfer needs two different employers.", error(self));
        Map<String, Object> wrongType = body("CONTRIBUTION", a, "2026-05-10", 1, 0);
        wrongType.put("wageMonth", "2026-04-01");
        wrongType.put("fromEmployerId", b.toString());
        assertEquals("Only a transfer in has a source employer.", error(wrongType));
        Map<String, Object> noEmployer = body("OPENING_BALANCE", a, "2026-05-10", 1, 0);
        noEmployer.remove("employerId");
        assertEquals("Employer is required.", error(noEmployer));
        Map<String, Object> noType = body("OPENING_BALANCE", a, "2026-05-10", 1, 0);
        noType.remove("type");
        assertEquals("Type is required.", error(noType));
        Map<String, Object> linkOnContribution = contribution(a, "2026-04-01", "2026-05-10", 1, 1, 0);
        linkOnContribution.put("transactionId", UUID.randomUUID().toString());
        assertEquals("Only a withdrawal can be linked to a bank transaction.", error(linkOnContribution));
        assertEquals(0, api.getJson(BASE + "/entries", 200).get("totalElements").asInt());
    }

    private static Map<String, Object> withTds(Map<String, Object> body, Number tds) {
        body.put("tdsAmount", tds);
        return body;
    }

    @Test
    void aContributionWageMonthIsNormalisedToTheFirstAndOnlyRelevantFieldsAreKept() throws Exception {
        UUID a = employer("Acme", null, null);
        Map<String, Object> contribution = contribution(a, "2026-04-17", "2026-05-10", 3000, 1750, 1250);
        contribution.put("note", "  April  ");
        contribution.put("interestFy", 2020); // not an interest entry: dropped

        JsonNode saved = addEntry(contribution);

        assertEquals("2026-04-01", saved.get("wageMonth").asText());
        assertTrue(saved.get("interestFy").isNull());
        assertEquals("April", saved.get("note").asText());
        assertEquals("MANUAL", saved.get("source").asText());
        assertAmount("1250", saved.get("pensionAmount"));
        assertTrue(saved.get("grossAmount").isNull()); // withdrawals only
        assertAmount("4750", saved.get("runningBalance"));
    }

    @Test
    void aManualEntryCanBeUpdatedAndItsBalanceFollows() throws Exception {
        UUID a = employer("Acme", null, null);
        UUID b = employer("Beta", null, null);
        UUID id = addEntryId(contribution(a, "2026-04-01", "2026-05-10", 100, 100, 0));

        Map<String, Object> change = contribution(b, "2026-04-01", "2026-05-12", 300, 200, 0);
        JsonNode updated = api.putJson(BASE + "/entries/" + id, change, 200);

        assertEquals("Beta", updated.get("employerName").asText());
        assertEquals("2026-05-12", updated.get("creditedOn").asText());
        assertAmount("500", updated.get("runningBalance"));
        assertEquals("Wage month is required for a contribution.",
                message(api.putJson(BASE + "/entries/" + id, body("CONTRIBUTION", b, "2026-05-12", 1, 1), 400)));
        assertAmount("500", api.getJson(BASE + "/entries/" + id, 200).get("runningBalance"));
    }

    @Test
    void anImportedEntryOnlyTakesTdsAndNoteChanges() throws Exception {
        UUID a = employer("Acme", null, null);
        UUID imported = passbookEntry(a, EpfEntryType.WITHDRAWAL, "2026-06-01", 700, 300);

        Map<String, Object> same = withdrawal(a, "PARTIAL", "2026-06-01", 700, 300, 0, 100);
        same.put("note", "tax cut");
        JsonNode updated = api.putJson(BASE + "/entries/" + imported, same, 200);
        assertAmount("100", updated.get("tdsAmount"));
        assertAmount("900", updated.get("receivedAmount"));
        assertEquals("tax cut", updated.get("note").asText());
        assertEquals("PASSBOOK", updated.get("source").asText());

        Map<String, Object> otherDate = withdrawal(a, "PARTIAL", "2026-06-02", 700, 300, 0, 100);
        assertEquals(IMPORTED_MESSAGE, message(api.putJson(BASE + "/entries/" + imported, otherDate, 400)));
        Map<String, Object> otherAmount = withdrawal(a, "PARTIAL", "2026-06-01", 701, 300, 0, 100);
        assertEquals(IMPORTED_MESSAGE, message(api.putJson(BASE + "/entries/" + imported, otherAmount, 400)));
        Map<String, Object> otherType = body("OPENING_BALANCE", a, "2026-06-01", 700, 300);
        assertEquals(IMPORTED_MESSAGE, message(api.putJson(BASE + "/entries/" + imported, otherType, 400)));
        Map<String, Object> otherEmployer = withdrawal(employer("Beta", null, null), "PARTIAL", "2026-06-01", 700, 300, 0, 100);
        assertEquals(IMPORTED_MESSAGE, message(api.putJson(BASE + "/entries/" + imported, otherEmployer, 400)));
        Map<String, Object> tdsTooBig = withdrawal(a, "PARTIAL", "2026-06-01", 700, 300, 0, 1000);
        assertEquals("TDS must be less than the withdrawal amount.", message(api.putJson(BASE + "/entries/" + imported, tdsTooBig, 400)));

        api.deleteJson(BASE + "/entries/" + imported, 400);
        assertEquals(1, api.getJson(BASE + "/entries", 200).get("totalElements").asInt());
    }

    @Test
    void aTransferInWithASourceEmployerCreatesAndKeepsInSyncItsTransferOut() throws Exception {
        UUID a = employer("Old Co", null, "2022-12-31");
        UUID b = employer("New Co", null, null);
        addEntry(body("OPENING_BALANCE", a, "2022-03-31", 800, 200));
        Map<String, Object> in = body("TRANSFER_IN", b, "2023-01-05", 800, 200);
        in.put("fromEmployerId", a.toString());

        JsonNode created = addEntry(in);

        assertEquals(a.toString(), created.get("counterpartEmployerId").asText());
        assertEquals("Old Co", created.get("counterpartEmployerName").asText());
        assertAmount("1000", created.get("runningBalance"));
        JsonNode all = api.getJson(BASE + "/entries?employerId=" + a, 200);
        JsonNode out = all.get("content").get(0);
        assertEquals("TRANSFER_OUT", out.get("type").asText());
        assertEquals("2023-01-05", out.get("creditedOn").asText());
        assertEquals(b.toString(), out.get("counterpartEmployerId").asText());
        assertAmount("0", out.get("runningBalance"));
        assertEquals("TRANSFERRED", employerStatus("Old Co"));
        UUID outId = UUID.fromString(out.get("id").asText());

        // Editing the transfer in moves the date and amounts of the out too.
        Map<String, Object> edit = body("TRANSFER_IN", b, "2023-01-07", 600, 150);
        edit.put("fromEmployerId", a.toString());
        api.putJson(BASE + "/entries/" + created.get("id").asText(), edit, 200);
        JsonNode outAfter = api.getJson(BASE + "/entries/" + outId, 200);
        assertEquals("2023-01-07", outAfter.get("creditedOn").asText());
        assertAmount("600", outAfter.get("employeeAmount"));
        assertAmount("150", outAfter.get("employerAmount"));
        assertAmount("250", outAfter.get("runningBalance"));

        // Editing the transfer out moves the in.
        Map<String, Object> editOut = body("TRANSFER_OUT", a, "2023-01-08", 700, 200);
        api.putJson(BASE + "/entries/" + outId, editOut, 200);
        JsonNode inAfter = api.getJson(BASE + "/entries/" + created.get("id").asText(), 200);
        assertEquals("2023-01-08", inAfter.get("creditedOn").asText());
        assertAmount("700", inAfter.get("employeeAmount"));

        // Deleting either removes both.
        api.deleteJson(BASE + "/entries/" + outId, 204);
        api.getJson(BASE + "/entries/" + created.get("id").asText(), 404);
        assertEquals(1, api.getJson(BASE + "/entries", 200).get("totalElements").asInt()); // the opening balance
    }

    @Test
    void aTransferWhoseOtherSideCameFromAPassbookCannotBeEditedOrDeletedFromThisSide() throws Exception {
        UUID a = employer("Old Co", null, null);
        UUID b = employer("New Co", null, null);
        Map<String, Object> in = body("TRANSFER_IN", b, "2023-01-05", 100, 0);
        in.put("fromEmployerId", a.toString());
        UUID inId = addEntryId(in);
        UUID outId = UUID.fromString(api.getJson(BASE + "/entries?employerId=" + a, 200).get("content").get(0).get("id").asText());
        // importing the old employer's passbook replaces the manual transfer out in place
        jdbc.update("UPDATE epf_entry SET source = 'PASSBOOK' WHERE id = ?", outId.toString());
        String expected = "The other side of this transfer came from a passbook and can't change.";

        Map<String, Object> edit = body("TRANSFER_IN", b, "2023-01-06", 50, 0);
        edit.put("fromEmployerId", a.toString());
        assertEquals(expected, message(api.putJson(BASE + "/entries/" + inId, edit, 400)));
        assertEquals(expected, message(api.deleteJson(BASE + "/entries/" + inId, 400)));
        assertEquals("2023-01-05", api.getJson(BASE + "/entries/" + inId, 200).get("creditedOn").asText());
        assertAmount("100", api.getJson(BASE + "/entries/" + outId, 200).get("employeeAmount"));
    }

    @Test
    void clearingTheSourceEmployerOfATransferInRemovesTheOutAndSettingOneAddsIt() throws Exception {
        UUID a = employer("Old Co", null, null);
        UUID b = employer("New Co", null, null);
        UUID in = addEntryId(body("TRANSFER_IN", b, "2023-01-05", 100, 100));
        assertEquals(1, api.getJson(BASE + "/entries", 200).get("totalElements").asInt());

        Map<String, Object> withSource = body("TRANSFER_IN", b, "2023-01-05", 100, 100);
        withSource.put("fromEmployerId", a.toString());
        api.putJson(BASE + "/entries/" + in, withSource, 200);
        assertEquals(2, api.getJson(BASE + "/entries", 200).get("totalElements").asInt());

        api.putJson(BASE + "/entries/" + in, body("TRANSFER_IN", b, "2023-01-05", 100, 100), 200);
        assertEquals(1, api.getJson(BASE + "/entries", 200).get("totalElements").asInt());
    }

    @Test
    void aTransferOutCannotChangeTypeAndNothingBecomesOne() throws Exception {
        UUID a = employer("Old Co", null, null);
        UUID b = employer("New Co", null, null);
        Map<String, Object> in = body("TRANSFER_IN", b, "2023-01-05", 100, 0);
        in.put("fromEmployerId", a.toString());
        UUID inId = addEntryId(in);
        UUID outId = UUID.fromString(api.getJson(BASE + "/entries?employerId=" + a, 200).get("content").get(0).get("id").asText());

        assertEquals("A transfer out can't change its type.",
                message(api.putJson(BASE + "/entries/" + outId, body("OPENING_BALANCE", a, "2023-01-05", 100, 0), 400)));
        assertEquals("A transfer out is created from the transfer in on the other employer.",
                message(api.putJson(BASE + "/entries/" + inId, body("TRANSFER_OUT", b, "2023-01-05", 100, 0), 400)));
    }

    @Test
    void anotherUsersEmployersAndEntriesAreNotFound() throws Exception {
        UUID mine = employer("Mine", null, null);
        UUID entry = addEntryId(body("OPENING_BALANCE", mine, "2026-05-10", 100, 0));
        UUID theirs = employer(otherApi, "Theirs", null, null, null);

        otherApi.getJson(BASE + "/entries/" + entry, 404);
        otherApi.putJson(BASE + "/entries/" + entry, body("OPENING_BALANCE", theirs, "2026-05-10", 1, 0), 404);
        otherApi.deleteJson(BASE + "/entries/" + entry, 404);
        otherApi.postJson(BASE + "/entries", body("OPENING_BALANCE", mine, "2026-05-10", 1, 0), 404); // their entry on my employer
        api.postJson(BASE + "/entries", body("OPENING_BALANCE", theirs, "2026-05-10", 1, 0), 404);
        Map<String, Object> transferFromTheirs = body("TRANSFER_IN", mine, "2026-05-10", 1, 0);
        transferFromTheirs.put("fromEmployerId", theirs.toString());
        api.postJson(BASE + "/entries", transferFromTheirs, 404);
        otherApi.getJson(BASE + "/entries?employerId=" + mine, 404);
        assertEquals(0, otherApi.getJson(BASE + "/entries", 200).get("totalElements").asInt());
        assertEquals(0, otherApi.getJson(BASE + "/fys", 200).size());
        assertEquals(1, api.getJson(BASE + "/entries", 200).get("totalElements").asInt());
        assertEquals(entry.toString(), api.getJson(BASE + "/entries/" + entry, 200).get("id").asText());
    }

    private String employerStatus(String name) throws Exception {
        for (JsonNode e : api.getJson(BASE + "/employers", 200)) {
            if (name.equals(e.get("name").asText())) {
                return e.get("status").asText();
            }
        }
        throw new AssertionError("no employer " + name);
    }
}
