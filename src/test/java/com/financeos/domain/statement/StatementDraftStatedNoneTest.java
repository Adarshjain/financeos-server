package com.financeos.domain.statement;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

class StatementDraftStatedNoneTest {

    private static final LocalDate END = LocalDate.of(2025, 10, 17);

    @Test
    void sixteenArgConstructorHasNoStatedNoneAndNoCardChecks() {
        StatementDraft d = new StatementDraft("credit_card", null, END, null, null, "HDFC", "7358", 1, 0, null, null,
                "heuristic", null, null, StatementVerdict.AUTO_INGEST, Map.of());
        assertThat(d.statedNone()).isEmpty();
        assertThat(d.cardChecksOk()).isNull();
    }

    @Test
    void seventeenArgConstructorKeepsCardChecksAndHasNoStatedNone() {
        StatementDraft d = new StatementDraft("credit_card", null, END, null, null, "HDFC", "7358", 1, 0, null, null,
                "heuristic", null, null, StatementVerdict.AUTO_INGEST, Map.of(), Boolean.TRUE);
        assertThat(d.statedNone()).isEmpty();
        assertThat(d.cardChecksOk()).isTrue();
    }

    @Test
    void fullConstructorCopiesStatedNoneAndTreatsNullAsEmpty() {
        Set<String> none = new HashSet<>(Set.of("payment_due_date"));
        StatementDraft d = new StatementDraft("credit_card", null, END, null, new BigDecimal("-38.74"), "HDFC", "7358", 1, 0,
                null, null, "heuristic", null, null, StatementVerdict.AUTO_INGEST, Map.of(), null, none);
        none.add("minimum_amount_due");
        assertThat(d.statedNone()).containsExactly("payment_due_date");
        assertThatThrownBy(() -> d.statedNone().add("x")).isInstanceOf(UnsupportedOperationException.class);

        StatementDraft nullSet = new StatementDraft("credit_card", null, END, null, null, null, null, 0, 0, null, null,
                "heuristic", null, null, StatementVerdict.AUTO_INGEST, Map.of(), null, null);
        assertThat(nullSet.statedNone()).isEmpty();
    }
}
