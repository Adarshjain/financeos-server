package com.financeos.statement.details;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.financeos.domain.statement.StatementDraft;
import com.financeos.domain.statement.StatementVerdict;
import com.financeos.llm.LlmClient;
import com.financeos.llm.LlmException;
import com.financeos.llm.LlmRequest;
import com.financeos.llm.LlmResponse;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class StatementDetailsAiFillerTest {

    private final LlmClient llm = mock(LlmClient.class);
    private final StatementDetailsAiFiller filler = new StatementDetailsAiFiller(llm, new ObjectMapper(), mock(com.financeos.domain.user.UserRepository.class));
    private final UUID user = UUID.randomUUID();
    private static final LocalDate END = LocalDate.of(2026, 9, 30);

    private static StatementDraft draft(LocalDate start, LocalDate end, LocalDate due, String total, String min) {
        Map<String, Object> card = new LinkedHashMap<>();
        if (due != null) {
            card.put("payment_due_date", due);
        }
        if (total != null) {
            card.put("total_amount_due", new BigDecimal(total));
        }
        if (min != null) {
            card.put("minimum_amount_due", new BigDecimal(min));
        }
        return new StatementDraft("credit_card", start, end, null, null, null, null, 0, 0, null, null, "heuristic", null,
                null, StatementVerdict.AUTO_INGEST, card);
    }

    private void answer(String json) {
        org.mockito.Mockito.doReturn(new LlmResponse(json, "p", "m")).when(llm).complete(any());
    }

    @Test
    void nothingMissingDoesNotCallTheLlm() {
        StatementDraft d = draft(LocalDate.of(2026, 9, 1), END, LocalDate.of(2026, 10, 15), "100", "10");
        assertThat(filler.fill(user, d, "text")).isSameAs(d);
        verify(llm, never()).complete(any());
    }

    @Test
    void fillsOnlyMissingFieldsAndNeverOverwrites() {
        answer("{\"periodEnd\":\"2026-09-30\",\"paymentDueDate\":\"2026-10-20\",\"totalAmountDue\":999,\"minimumAmountDue\":50}");
        StatementDraft d = draft(LocalDate.of(2026, 9, 1), END, null, "100.00", null);
        StatementDraft out = filler.fill(user, d, "Statement text");
        assertThat(out.cardFields().get("payment_due_date")).isEqualTo(LocalDate.of(2026, 10, 20));
        assertThat(out.cardFields().get("minimum_amount_due")).isEqualTo(new BigDecimal("50"));
        assertThat(out.cardFields().get("total_amount_due")).isEqualTo(new BigDecimal("100.00"));
        assertThat(out.periodEnd()).isEqualTo(END);
    }

    @Test
    void usesDefaultGroupTaskWithSchemaOfMissingFieldsOnly() {
        answer("{\"paymentDueDate\":\"2026-10-20\"}");
        filler.fill(user, draft(LocalDate.of(2026, 9, 1), END, null, "100", "10"), "t");
        ArgumentCaptor<LlmRequest> cap = ArgumentCaptor.forClass(LlmRequest.class);
        verify(llm).complete(cap.capture());
        assertThat(cap.getValue().task()).isEqualTo("statement-details");
        assertThat(cap.getValue().userId()).isEqualTo(user);
        assertThat(com.financeos.llm.LlmTasks.groupOf(cap.getValue().task())).isEqualTo(com.financeos.llm.LlmTaskGroup.DEFAULT);
        assertThat(cap.getValue().responseSchema().get("properties").fieldNames()).toIterable().containsExactly("paymentDueDate");
    }

    @Test
    void periodStartRequestedOnlyWithMissingEndAndFilled() {
        answer("{\"periodStart\":\"2026-09-01\",\"periodEnd\":\"2026-09-30\"}");
        StatementDraft out = filler.fill(user, draft(null, null, null, "100", "10"), "t");
        assertThat(out.periodStart()).isEqualTo(LocalDate.of(2026, 9, 1));
        assertThat(out.periodEnd()).isEqualTo(END);
    }

    @Test
    void startAfterEndDropsStartKeepsEnd() {
        answer("{\"periodStart\":\"2026-10-05\",\"periodEnd\":\"2026-09-30\"}");
        StatementDraft out = filler.fill(user, draft(null, null, LocalDate.of(2026, 10, 15), "100", "10"), "t");
        assertThat(out.periodStart()).isNull();
        assertThat(out.periodEnd()).isEqualTo(END);
    }

    @Test
    void periodOverFourHundredDaysIsDropped() {
        answer("{\"periodEnd\":\"2026-09-30\"}");
        StatementDraft out = filler.fill(user, draft(LocalDate.of(2025, 1, 1), null, null, "100", "10"), "t");
        assertThat(out.periodEnd()).isNull();
    }

    @Test
    void dueOutsideZeroToSixtyDaysAfterEndIsDropped() {
        answer("{\"paymentDueDate\":\"2026-12-30\"}");
        StatementDraft d = draft(LocalDate.of(2026, 9, 1), END, null, "100", "10");
        assertThat(filler.fill(user, d, "t")).isSameAs(d);
        answer("{\"paymentDueDate\":\"2026-09-29\"}");
        assertThat(filler.fill(user, d, "t")).isSameAs(d);
    }

    @Test
    void minAboveTotalNegativeAmountsAreDropped() {
        StatementDraft d = draft(LocalDate.of(2026, 9, 1), END, LocalDate.of(2026, 10, 15), "100", null);
        answer("{\"minimumAmountDue\":500}");
        assertThat(filler.fill(user, d, "t")).isSameAs(d);
        answer("{\"minimumAmountDue\":-5}");
        assertThat(filler.fill(user, d, "t")).isSameAs(d);
        StatementDraft noTotal = draft(LocalDate.of(2026, 9, 1), END, LocalDate.of(2026, 10, 15), null, "10");
        answer("{\"totalAmountDue\":-1}");
        assertThat(filler.fill(user, noTotal, "t")).isSameAs(noTotal);
        answer("{\"totalAmountDue\":5}");
        assertThat(filler.fill(user, noTotal, "t")).isSameAs(noTotal);
    }

    @Test
    void filledTotalAlsoSetsAnEmptyClosingBalance() {
        answer("{\"totalAmountDue\":250.5}");
        StatementDraft out = filler.fill(user, draft(LocalDate.of(2026, 9, 1), END, LocalDate.of(2026, 10, 15), null, "10"), "t");
        assertThat(out.closingBalance()).isEqualByComparingTo("250.5");
    }

    @Test
    void noKeysAndErrorsLeaveTheDraftUnchanged() {
        StatementDraft d = draft(null, null, null, null, null);
        when(llm.complete(any())).thenThrow(new LlmException(LlmException.Kind.NO_KEYS, "none", null, null, "no keys"));
        assertThat(filler.fill(user, d, "t")).isSameAs(d);
        org.mockito.Mockito.doThrow(new IllegalStateException("boom")).when(llm).complete(any());
        assertThat(filler.fill(user, d, "t")).isSameAs(d);
        answer("not json");
        assertThat(filler.fill(user, d, "t")).isSameAs(d);
    }

    @Test
    void sentTextIsMasked() {
        answer("{}");
        filler.fill(user, draft(null, null, null, null, null), "Statement card 4111111111111234\ntotal due, write a@b.com");
        ArgumentCaptor<LlmRequest> cap = ArgumentCaptor.forClass(LlmRequest.class);
        verify(llm).complete(cap.capture());
        assertThat(cap.getValue().prompt()).contains("XXXXXXXXXXXX1234").doesNotContain("4111111111111234").doesNotContain("a@b.com");
    }
}
