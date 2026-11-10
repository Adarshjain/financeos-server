package com.financeos.statement.parser;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.financeos.statement.parser.corpus.CorpusSnapshot;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/** The one renderer behind the corpus tool and the stored per-statement record. */
class ParseSnapshotTest {

    private static ParsedStatement parsed() throws Exception {
        return StatementParseEngine.parse(StatementPdfFixtures.cardWithPeriodAndNumberButNoSummary(), null, null);
    }

    @Test
    void theSameParseGivesTheSameBytesEveryTime() throws Exception {
        assertThat(ParseSnapshot.json(parsed())).isEqualTo(ParseSnapshot.json(parsed()));
    }

    @Test
    void jsonIsTheCompactFormOfTheNode() throws Exception {
        ParsedStatement ps = parsed();
        String json = ParseSnapshot.json(ps);
        assertThat(json).isEqualTo(ParseSnapshot.of(ps).toString());
        assertThat(json).doesNotContain("\n").doesNotContain("\": ");
        assertThat(new ObjectMapper().readTree(json)).isEqualTo(ParseSnapshot.of(ps));
    }

    @Test
    void theCorpusToolRendersExactlyWhatProductionStores() throws Exception {
        ParsedStatement ps = parsed();
        assertThat(CorpusSnapshot.of(ps)).isEqualTo(ParseSnapshot.of(ps));
        assertThat(CorpusSnapshot.of(ps).toString()).isEqualTo(ParseSnapshot.json(ps));
    }

    @Test
    void itHoldsOnlyWhatTheParsedStatementExposesAndLeavesOutDerivedStatsAndSummaryText() throws Exception {
        ParsedStatement ps = parsed();
        JsonNode node = ParseSnapshot.of(ps);
        List<String> keys = new ArrayList<>();
        node.fieldNames().forEachRemaining(keys::add);
        assertThat(keys).containsExactly("meta", "statementType", "parseInfo", "summaryFields", "transactions");
        assertThat(node.get("transactions")).hasSize(ps.transactions().size());
        assertThat(node.get("transactions").get(0).get("index").asInt()).isZero();
        assertThat(node.get("transactions").get(0).get("description").asText()).isEqualTo(ps.transactions().get(0).description);
        assertThat(node.get("statementType").asText()).isEqualTo(ps.statementType());
    }

    @Test
    void moneyIsATwoDecimalString() {
        assertThat(ParseSnapshot.money(1234.5)).isEqualTo("1234.50");
        assertThat(ParseSnapshot.money(-3)).isEqualTo("-3.00");
    }
}
