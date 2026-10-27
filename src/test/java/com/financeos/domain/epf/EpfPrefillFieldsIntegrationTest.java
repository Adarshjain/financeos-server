package com.financeos.domain.epf;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.Map;
import java.util.UUID;

/** Summary throughMonth/latestContribution, employer balance split and transfer/pension facts, entry interestRate. */
@SpringBootTest
@AutoConfigureMockMvc
class EpfPrefillFieldsIntegrationTest extends EpfIntegrationBase {

    private JsonNode employerNamed(String name) throws Exception {
        for (JsonNode e : api.getJson(BASE + "/employers", 200)) {
            if (name.equals(e.get("name").asText())) {
                return e;
            }
        }
        throw new AssertionError("no employer " + name);
    }

    @Test
    void summaryHasNoThroughMonthWithoutContributions() throws Exception {
        UUID e1 = employer("Acme", "2020-04-01", null);
        addEntry(body("OPENING_BALANCE", e1, "2026-03-31", 100, 50));
        JsonNode s = api.getJson(BASE + "/summary", 200);
        assertTrue(s.get("throughMonth").isNull());
        assertTrue(s.get("latestContribution").isNull());
    }

    @Test
    void latestContributionIsTheLatestWageMonthThenCreditedDateAcrossEmployers() throws Exception {
        UUID old = employer("Old Co", "2019-01-01", "2024-01-31");
        UUID cur = employer("Cur Co", "2024-02-01", null);
        addEntry(contribution(old, "2026-08-01", "2026-09-10", 100, 100, 10));
        addEntry(contribution(cur, "2026-07-01", "2026-09-30", 300, 300, 30)); // earlier month, later credit
        UUID latest = cur;
        addEntry(contribution(latest, "2026-08-01", "2026-09-12", 3000, 1750, 1250)); // same month, later credit

        JsonNode s = api.getJson(BASE + "/summary", 200);

        assertEquals("2026-08-01", s.get("throughMonth").asText());
        JsonNode c = s.get("latestContribution");
        assertEquals(cur.toString(), c.get("employerId").asText());
        assertEquals("2026-08-01", c.get("wageMonth").asText());
        assertEquals("2026-09-12", c.get("creditedOn").asText());
        assertAmount("3000", c.get("employeeAmount"));
        assertAmount("1750", c.get("employerAmount"));
        assertAmount("1250", c.get("pensionAmount"));
        assertTrue(otherApi.getJson(BASE + "/summary", 200).get("latestContribution").isNull());
    }

    @Test
    void employerBalanceSplitTransferAndPensionFacts() throws Exception {
        UUID a = employer("Old Co", null, "2022-12-31");
        UUID b = employer("New Co", null, null);
        addEntry(body("OPENING_BALANCE", a, "2022-03-31", 800, 200));
        Map<String, Object> in = body("TRANSFER_IN", b, "2023-01-05", 800, 200);
        in.put("fromEmployerId", a.toString());
        addEntry(in);
        addEntry(withdrawal(b, "PENSION", "2024-01-05", 0, 0, 500, 0));

        JsonNode oldCo = employerNamed("Old Co");
        assertAmount("1000", oldCo.get("transferredOutAmount"));
        assertEquals("New Co", oldCo.get("transferredToEmployerName").asText());
        assertFalse(oldCo.get("pensionWithdrawn").asBoolean());
        assertAmount("0", oldCo.get("employeeBalance"));

        JsonNode newCo = employerNamed("New Co");
        assertAmount("800", newCo.get("employeeBalance"));
        assertAmount("200", newCo.get("employerBalance"));
        assertAmount("1000", newCo.get("balance"));
        assertTrue(newCo.get("transferredOutAmount").isNull());
        assertTrue(newCo.get("transferredToEmployerName").isNull());
        assertTrue(newCo.get("pensionWithdrawn").asBoolean());
    }

    @Test
    void interestEntriesCarryTheDeclaredRateOthersNull() throws Exception {
        UUID e1 = employer("Acme", "2020-04-01", null);
        addEntry(interest(e1, 2024, "2025-03-31", 100, 50));
        addEntry(interest(e1, 2030, "2031-03-31", 100, 50));
        addEntry(contribution(e1, "2026-04-01", "2026-05-10", 10, 10, 1));

        for (JsonNode e : api.getJson(BASE + "/entries?size=50", 200).get("content")) {
            String type = e.get("type").asText();
            if (type.equals("INTEREST") && e.get("interestFy").asInt() == 2024) {
                assertAmount("8.25", e.get("interestRate"));
            } else {
                assertTrue(e.get("interestRate").isNull(), type);
            }
        }
    }
}
