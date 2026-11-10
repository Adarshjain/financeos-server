package com.financeos.statement.details;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.financeos.domain.statement.StatementDraft;
import com.financeos.domain.statement.StatementVerdict;
import com.financeos.domain.user.UserRepository;
import com.financeos.llm.LlmClient;
import com.financeos.llm.LlmResponse;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** The snapshot describes what the parser read: filling blanks with AI never changes the parser version or snapshot. */
class StatementDetailsAiFillerParseRecordTest {

    private final LlmClient llm = mock(LlmClient.class);
    private final StatementDetailsAiFiller filler = new StatementDetailsAiFiller(llm, new ObjectMapper(), mock(UserRepository.class));

    @Test
    void anAiFilledDraftKeepsTheParsersVersionAndSnapshot() {
        Map<String, Object> card = new LinkedHashMap<>();
        card.put("total_amount_due", new BigDecimal("100.00"));
        card.put("minimum_amount_due", new BigDecimal("5.00"));
        StatementDraft in = new StatementDraft("credit_card", LocalDate.of(2025, 9, 18), LocalDate.of(2025, 10, 17), null, null, "HDFC",
                "7358", 3, 0, null, null, "heuristic", null, null, StatementVerdict.AUTO_INGEST, card, Boolean.TRUE, Set.of(),
                4, "{\"parsed\":\"as read\"}");
        doReturn(new LlmResponse("{\"paymentDueDate\":\"2025-11-05\"}", "p", "m")).when(llm).complete(any());

        StatementDraft out = filler.fill(UUID.randomUUID(), in, "text");

        assertThat(out.cardFields().get("payment_due_date")).isEqualTo(LocalDate.of(2025, 11, 5));
        assertThat(out.parserVersion()).isEqualTo(4);
        assertThat(out.parseSnapshot()).isEqualTo("{\"parsed\":\"as read\"}");
    }
}
