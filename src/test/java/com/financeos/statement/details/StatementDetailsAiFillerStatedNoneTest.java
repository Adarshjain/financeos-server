package com.financeos.statement.details;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.financeos.domain.statement.StatementDraft;
import com.financeos.domain.statement.StatementVerdict;
import com.financeos.domain.user.UserRepository;
import com.financeos.llm.LlmClient;
import com.financeos.llm.LlmRequest;
import com.financeos.llm.LlmResponse;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/** Fields the statement prints as none ("Payment Due Date: Nil") are never asked of, or filled by, the AI. */
class StatementDetailsAiFillerStatedNoneTest {

    private final LlmClient llm = mock(LlmClient.class);
    private final StatementDetailsAiFiller filler = new StatementDetailsAiFiller(llm, new ObjectMapper(), mock(UserRepository.class));
    private final UUID user = UUID.randomUUID();
    private static final LocalDate START = LocalDate.of(2025, 9, 18);
    private static final LocalDate END = LocalDate.of(2025, 10, 17);

    private static StatementDraft draft(String total, String min, Set<String> statedNone) {
        Map<String, Object> card = new LinkedHashMap<>();
        if (total != null) {
            card.put("total_amount_due", new BigDecimal(total));
        }
        if (min != null) {
            card.put("minimum_amount_due", new BigDecimal(min));
        }
        return new StatementDraft("credit_card", START, END, null, null, "HDFC", "7358", 3, 0, null, null, "heuristic", null,
                null, StatementVerdict.AUTO_INGEST, card, Boolean.TRUE, statedNone);
    }

    @Test
    void statedNoneDueDateAloneDoesNotCallTheLlm() {
        // the ••7358 case: credit balance, "Payment Due Date: Nil"; the AI once wrote the statement date there
        doReturn(new LlmResponse("{\"paymentDueDate\":\"2025-10-17\"}", "p", "m")).when(llm).complete(any());
        StatementDraft d = draft("-38.74", "0.00", Set.of("payment_due_date"));
        StatementDraft out = filler.fill(user, d, "Payment Due Date Nil");
        assertThat(out).isSameAs(d);
        assertThat(out.cardFields()).doesNotContainKey("payment_due_date");
        verify(llm, never()).complete(any());
    }

    @Test
    void otherMissingFieldsAreStillAskedButNotTheStatedNoneOne() {
        doReturn(new LlmResponse("{\"minimumAmountDue\":5,\"paymentDueDate\":\"2025-10-17\"}", "p", "m")).when(llm).complete(any());
        StatementDraft out = filler.fill(user, draft("100.00", null, Set.of("payment_due_date")), "text");

        ArgumentCaptor<LlmRequest> cap = ArgumentCaptor.forClass(LlmRequest.class);
        verify(llm).complete(cap.capture());
        assertThat(cap.getValue().responseSchema().get("properties").fieldNames()).toIterable()
                .containsExactly("minimumAmountDue");
        assertThat(out.cardFields().get("minimum_amount_due")).isEqualTo(new BigDecimal("5"));
        assertThat(out.cardFields()).doesNotContainKey("payment_due_date");
        assertThat(out.statedNone()).containsExactly("payment_due_date");
        assertThat(out.cardChecksOk()).isTrue();
    }

    @Test
    void statedNoneMinimumIsNotAsked() {
        doReturn(new LlmResponse("{\"paymentDueDate\":\"2025-11-05\"}", "p", "m")).when(llm).complete(any());
        filler.fill(user, draft("100.00", null, Set.of("minimum_amount_due")), "text");
        ArgumentCaptor<LlmRequest> cap = ArgumentCaptor.forClass(LlmRequest.class);
        verify(llm).complete(cap.capture());
        assertThat(cap.getValue().responseSchema().get("properties").fieldNames()).toIterable()
                .containsExactly("paymentDueDate");
    }

    @Test
    void withoutStatedNoneTheDueDateIsStillAsked() {
        doReturn(new LlmResponse("{\"paymentDueDate\":\"2025-11-05\"}", "p", "m")).when(llm).complete(any());
        StatementDraft out = filler.fill(user, draft("100.00", "5.00", Set.of()), "text");
        assertThat(out.cardFields().get("payment_due_date")).isEqualTo(LocalDate.of(2025, 11, 5));
    }
}
