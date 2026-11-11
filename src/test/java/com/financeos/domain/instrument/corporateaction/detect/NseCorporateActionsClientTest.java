package com.financeos.domain.instrument.corporateaction.detect;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.financeos.domain.instrument.corporateaction.detect.NseCorporateActionsClient.Row;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class NseCorporateActionsClientTest {

    private final NseCorporateActionsClient client =
            new NseCorporateActionsClient(new CorporateActionsProperties(), new ObjectMapper());

    @Test
    void parsesRows() {
        List<Row> rows = client.parse("""
                [{"symbol":"ACMEIND","isin":"INE000X01011","comp":"Acme Industries Ltd","series":"EQ",
                  "exDate":"28-Oct-2024","subject":"Bonus 1:1","faceVal":"10"},
                 {"symbol":"BRAVOW","isin":"INE000X01029","comp":"Bravo Works","series":"EQ",
                  "exDate":"-","subject":"Dividend - Rs 3 Per Share"},
                 {"symbol":"CHARLIE","comp":"Charlie","exDate":"5-Jan-2025","subject":"Demerger"}]
                """);
        assertThat(rows).hasSize(3);
        assertThat(rows.get(0)).isEqualTo(new Row("ACMEIND", "INE000X01011", "Acme Industries Ltd", "EQ",
                LocalDate.of(2024, 10, 28), "Bonus 1:1"));
        assertThat(rows.get(1).exDate()).isNull();
        assertThat(rows.get(2).isin()).isNull();
        assertThat(rows.get(2).exDate()).isEqualTo(LocalDate.of(2025, 1, 5));
    }

    @Test
    void emptyArrayIsNoRows() {
        assertThat(client.parse("[]")).isEmpty();
    }

    @Test
    void nonArrayOrNonJsonIsNull() {
        assertThat(client.parse("{\"error\":\"x\"}")).isNull();
        assertThat(client.parse("<html>blocked</html>")).isNull();
    }

    @Test
    void emptyListForAWindowOfThreeDaysOrMoreIsFailed() {
        assertThat(client.interpret(LocalDate.of(2026, 11, 1), LocalDate.of(2026, 11, 3), List.of()).status())
                .isEqualTo(NseCorporateActionsClient.Status.FAILED);
        assertThat(client.interpret(LocalDate.of(2026, 1, 1), LocalDate.of(2026, 10, 11), List.of()).status())
                .isEqualTo(NseCorporateActionsClient.Status.FAILED);
    }

    @Test
    void emptyListForAShortWindowIsOkAndNonEmptyIsAlwaysOk() {
        assertThat(client.interpret(LocalDate.of(2026, 11, 1), LocalDate.of(2026, 11, 2), List.of()).status())
                .isEqualTo(NseCorporateActionsClient.Status.OK);
        Row r = new Row("A", null, null, "EQ", LocalDate.of(2026, 11, 1), "Bonus 1:1");
        assertThat(client.interpret(LocalDate.of(2026, 1, 1), LocalDate.of(2026, 10, 11), List.of(r)).rows()).hasSize(1);
    }
}
