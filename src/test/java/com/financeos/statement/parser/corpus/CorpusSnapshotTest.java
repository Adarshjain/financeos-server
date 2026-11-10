package com.financeos.statement.parser.corpus;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.financeos.statement.parser.ParsedStatement;
import com.financeos.statement.parser.StatementParseEngine;
import com.financeos.statement.parser.StatementPdfFixtures;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class CorpusSnapshotTest {

    private static List<String> names(JsonNode o) {
        List<String> n = new ArrayList<>();
        Iterator<String> it = o.fieldNames();
        while (it.hasNext()) n.add(it.next());
        return n;
    }

    private static ObjectNode card() throws Exception {
        ParsedStatement ps = StatementParseEngine.parse(StatementPdfFixtures.cardSummaryBoxWithLabelsAboveValues(), null, null);
        return CorpusSnapshot.of(ps);
    }

    @Test
    void sameParseGivesIdenticalText() throws Exception {
        assertThat(card().toString()).isEqualTo(card().toString());
    }

    @Test
    void topLevelKeysAreInAFixedOrderAndLeaveOutDerivedAndSummaryText() throws Exception {
        assertThat(names(card())).containsExactly("meta", "statementType", "parseInfo", "summaryFields", "transactions");
    }

    @Test
    void metaCarriesEveryStatementMetaField() throws Exception {
        assertThat(names(card().get("meta"))).containsExactly("bank", "accountNumber", "ifsc", "holderName", "periodStart",
                "periodEnd", "openingBalance", "closingBalance", "issuerId", "documentKind", "isStatement", "documentDate",
                "productPhrase", "creditLimit", "closureStated");
    }

    @Test
    void parseInfoCarriesEveryField() throws Exception {
        assertThat(names(card().get("parseInfo"))).containsExactly("mode", "verdict", "chainValidationPct", "rowsChainValidated",
                "totalCredits", "totalDebits", "checksumOk", "crossDebits", "crossCredits", "cardChecks", "rowsDropped");
    }

    @Test
    void summaryKeysAreSortedDatesAreIsoAndMoneyHasTwoDecimals() throws Exception {
        JsonNode s = card().get("summaryFields");
        List<String> keys = names(s);
        assertThat(keys).isSorted();
        assertThat(s.get("payment_due_date").asText()).isEqualTo("2026-08-06");
        assertThat(s.get("total_amount_due").asText()).isEqualTo("4198.00");
        assertThat(s.get("minimum_amount_due").asText()).isEqualTo("327.00");
        assertThat(card().get("meta").get("periodStart").asText()).isEqualTo("2026-06-18");
    }

    @Test
    void nullsAreWrittenAsJsonNullNotOmitted() throws Exception {
        JsonNode meta = card().get("meta");
        assertThat(meta.has("ifsc")).isTrue();
        assertThat(meta.get("ifsc").isNull()).isTrue();
    }

    @Test
    void transactionsAreIndexedInOrderWithEveryRowField() throws Exception {
        JsonNode t = card().get("transactions");
        assertThat(t.size()).isPositive();
        for (int i = 0; i < t.size(); i++) {
            assertThat(t.get(i).get("index").asInt()).isEqualTo(i);
            assertThat(names(t.get(i))).containsExactly("index", "date", "description", "amount", "balance", "chainValid",
                    "signSource", "cardLast4");
        }
    }

    @Test
    void moneyIsRoundedHalfUpToTwoDecimalsAsPlainText() {
        assertThat(CorpusSnapshot.money(1234.5)).isEqualTo("1234.50");
        assertThat(CorpusSnapshot.money(0.125)).isEqualTo("0.13");
        assertThat(CorpusSnapshot.money(1.0E7)).isEqualTo("10000000.00");
        assertThat(CorpusSnapshot.money(-3)).isEqualTo("-3.00");
    }

    @Test
    void failureSnapshotHasSimpleTypeAndMessageWithDigitsKept() {
        ObjectNode f = CorpusSnapshot.ofFailure(new IllegalStateException("page 12 of 40 failed"));
        assertThat(names(f)).containsExactly("failure");
        assertThat(f.get("failure").get("type").asText()).isEqualTo("IllegalStateException");
        assertThat(f.get("failure").get("message").asText()).isEqualTo("page 12 of 40 failed");
    }

    @Test
    void failureWithoutMessageKeepsANullMessage() {
        ObjectNode f = CorpusSnapshot.ofFailure(new RuntimeException());
        assertThat(f.get("failure").get("message").isNull()).isTrue();
    }
}
