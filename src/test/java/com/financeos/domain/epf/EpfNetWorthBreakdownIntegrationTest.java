package com.financeos.domain.epf;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.UUID;

/** The net worth EPF row's breakdown over real persistence: matches the EPF balance, 404 without EPF, own data only. */
@SpringBootTest
@AutoConfigureMockMvc
class EpfNetWorthBreakdownIntegrationTest extends EpfIntegrationBase {

    private static final String ROW = "/api/v1/report/datasource/net_worth/rows/epf/breakdown";

    @Test
    void totalsTheEpfBalanceWithAStepPerEmployerAndAnEmployersSection() throws Exception {
        UUID acme = employer("Acme", "2020-04-01", "2023-03-31");
        UUID zeta = employer("Zeta", "2023-04-01", null);
        addEntry(body("OPENING_BALANCE", acme, "2023-03-31", 1000, 500));
        addEntry(body("OPENING_BALANCE", zeta, "2026-03-31", 200, 100));

        JsonNode b = api.getJson(ROW, 200);

        assertAmount("1800", b.get("total"));
        assertAmount("1800", api.getJson(BASE + "/summary", 200).get("balance"));
        assertEquals("EPF", b.get("title").asText());
        assertEquals(3, b.get("steps").size()); // two employers + equals
        JsonNode rows = api.getJson(ROW + "/sections/employers?sort=name,desc&size=1", 200).get("rows");
        assertEquals(1, rows.size());
        assertEquals("Zeta", rows.get(0).get("name").asText());
        api.getJson(ROW + "/sections/employers?sort=nope,asc", 400);
        api.getJson(ROW + "/sections/unknown", 404);
    }

    @Test
    void noEmployersIsNotFoundAndAnotherUsersEpfIsNotVisible() throws Exception {
        api.getJson(ROW, 404);
        UUID theirs = employer(otherApi, "Theirs", null, "2020-04-01", null);
        otherApi.postJson(BASE + "/entries", body("OPENING_BALANCE", theirs, "2026-03-31", 100, 50), 201);

        api.getJson(ROW, 404);
        api.getJson(ROW + "/sections/employers", 404);
        assertAmount("150", otherApi.getJson(ROW, 200).get("total"));
    }
}
