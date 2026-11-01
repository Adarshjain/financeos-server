package com.financeos.domain.report;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class SeededSpendReportRepointTest {

    private static final String SEEDED_ON_TAG = """
            {"chartType":"bar","dimension":{"field":"tag"},"measure":{"field":"spend","aggregation":"sum"},
             "filters":[{"field":"date","operator":"this_month"},{"field":"type","operator":"is","value":"DEBIT"},
                        {"field":"isExcluded","operator":"is","value":false},
                        {"field":"isTransferLeg","operator":"is","value":false}]}""";

    private static String repoint(String name, String description, String json) {
        return SeededSpendReportRepoint.repoint(name, description, "CHART", "transactions", json);
    }

    @Test
    void anUneditedSeededCopyMovesToCategoryKeepingEverythingElse() throws Exception {
        String out = repoint("Spend this month", "Debits this month, by category", SEEDED_ON_TAG);
        JsonNode def = new ObjectMapper().readTree(out);
        assertEquals("category", def.at("/dimension/field").asText());
        assertEquals(4, def.get("filters").size());
        assertEquals("spend", def.at("/measure/field").asText());
    }

    @Test
    void aRenamedOrReDescribedReportStaysOnTag() {
        assertNull(repoint("My spend", "Debits this month, by category", SEEDED_ON_TAG));
        assertNull(repoint("Spend this month", "mine", SEEDED_ON_TAG));
    }

    @Test
    void anEditedDefinitionStaysOnTag() {
        assertNull(repoint("Spend this month", "Debits this month, by category",
                SEEDED_ON_TAG.replace("\"this_month\"", "\"last_month\"")));
        assertNull(repoint("Spend this month", "Debits this month, by category",
                SEEDED_ON_TAG.replace("]}", "],\"series\":{\"field\":\"account\"}}")));
        assertNull(repoint("Spend this month", "Debits this month, by category",
                SEEDED_ON_TAG.replace("]}", ",{\"field\":\"amount\",\"operator\":\"gt\",\"value\":5}]}").replace("],{", ",{")));
    }

    @Test
    void aCopyAlreadyOnCategoryOrANonChartIsLeftAlone() {
        assertNull(repoint("Spend this month", "Debits this month, by category", SEEDED_ON_TAG.replace("\"tag\"", "\"category\"")));
        assertNull(SeededSpendReportRepoint.repoint("Spend this month", "Debits this month, by category", "TABLE",
                "transactions", SEEDED_ON_TAG));
    }
}
