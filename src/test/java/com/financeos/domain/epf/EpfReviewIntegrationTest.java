package com.financeos.domain.epf;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.Map;
import java.util.UUID;

/** GET /api/v1/epf/review: suggested withdrawal to credit pairings with their evidence. */
@SpringBootTest
@AutoConfigureMockMvc
class EpfReviewIntegrationTest extends EpfIntegrationBase {

    private UUID withdrawal(UUID employer, String creditedOn, int employee, Number tds) throws Exception {
        return addEntryId(withdrawal(employer, "PARTIAL", creditedOn, employee, 0, 0, tds));
    }

    @Test
    void aSuggestedPairShowsTheEntryTheCreditTheGapAndTheDays() throws Exception {
        UUID employer = employer("Acme", null, null);
        UUID entry = withdrawal(employer, "2026-06-01", 1000, 100);
        UUID credit = transaction(900, "2026-06-04", "NEFT EPFO");

        JsonNode matches = api.getJson(BASE + "/review", 200).get("matches");

        assertEquals(1, matches.size());
        JsonNode m = matches.get(0);
        assertEquals(entry.toString(), m.get("entry").get("id").asText());
        assertEquals("2026-06-01", m.get("entry").get("creditedOn").asText());
        assertEquals("PARTIAL", m.get("entry").get("kind").asText());
        assertEquals("Acme", m.get("entry").get("employerName").asText());
        assertAmount("1000", m.get("entry").get("gross"));
        assertAmount("900", m.get("entry").get("received"));
        assertAmount("100", m.get("entry").get("tdsAmount"));
        assertEquals(credit.toString(), m.get("credit").get("transactionId").asText());
        assertEquals("NEFT EPFO", m.get("credit").get("description").asText());
        assertEquals("EPF Bank", m.get("credit").get("accountName").asText());
        assertAmount("0", m.get("difference"));
        assertEquals(3, m.get("daysApart").asInt());
        assertEquals("NEEDS_CONFIRMATION", m.get("why").asText());
    }

    @Test
    void twoFittingCreditsGiveTheMultipleCreditsReason() throws Exception {
        UUID employer = employer("Acme", null, null);
        withdrawal(employer, "2026-06-01", 1000, null);
        transaction(1000, "2026-06-02", "NEFT EPFO A");
        transaction(1000, "2026-06-05", "NEFT EPFO B");

        JsonNode matches = api.getJson(BASE + "/review", 200).get("matches");

        assertEquals(1, matches.size());
        assertEquals("MULTIPLE_CREDITS", matches.get(0).get("why").asText());
        assertEquals(1, matches.get(0).get("daysApart").asInt());
    }

    @Test
    void aCreditFittingTwoWithdrawalsGivesTheSharedCreditReason() throws Exception {
        UUID employer = employer("Acme", null, null);
        withdrawal(employer, "2026-06-01", 1000, null);
        withdrawal(employer, "2026-06-03", 1000, null);
        transaction(1000, "2026-06-04", "NEFT EPFO");

        JsonNode matches = api.getJson(BASE + "/review", 200).get("matches");

        assertEquals(1, matches.size());
        assertEquals("SHARED_CREDIT", matches.get(0).get("why").asText());
    }

    @Test
    void aDismissedPairIsHidden() throws Exception {
        UUID employer = employer("Acme", null, null);
        UUID entry = withdrawal(employer, "2026-06-01", 1000, null);
        UUID credit = transaction(1000, "2026-06-03", "NEFT EPFO");

        api.postJson(BASE + "/entries/" + entry + "/suggestion/dismiss", Map.of("transactionId", credit.toString()), 204);

        assertTrue(api.getJson(BASE + "/review", 200).get("matches").isEmpty());
    }

    @Test
    void aLinkedWithdrawalIsNotListed() throws Exception {
        UUID employer = employer("Acme", null, null);
        UUID entry = withdrawal(employer, "2026-06-01", 1000, null);
        UUID credit = transaction(1000, "2026-06-03", "NEFT EPFO");
        api.putJson(BASE + "/entries/" + entry + "/transaction",
                Map.of("transactionId", credit.toString(), "recordTds", false), 200);

        assertTrue(api.getJson(BASE + "/review", 200).get("matches").isEmpty());
    }

    @Test
    void anotherUsersWithdrawalsNeverAppear() throws Exception {
        UUID employer = employer("Acme", null, null);
        withdrawal(employer, "2026-06-01", 1000, null);
        transaction(1000, "2026-06-03", "NEFT EPFO");

        assertTrue(otherApi.getJson(BASE + "/review", 200).get("matches").isEmpty());
        assertEquals(1, api.getJson(BASE + "/review", 200).get("matches").size());
    }
}
