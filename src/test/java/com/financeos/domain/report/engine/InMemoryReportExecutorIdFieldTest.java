package com.financeos.domain.report.engine;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.TextNode;
import com.financeos.domain.report.ReportType;
import com.financeos.domain.report.datasource.Aggregation;
import com.financeos.domain.report.datasource.ComputedReportDatasource;
import com.financeos.domain.report.datasource.DatasourceCatalog.FieldDef;
import com.financeos.domain.report.datasource.FieldRole;
import com.financeos.domain.report.datasource.FieldType;
import com.financeos.domain.report.definition.ChartDefinition;
import com.financeos.domain.report.definition.ChartType;
import com.financeos.domain.report.definition.DimensionRef;
import com.financeos.domain.report.definition.FilterClause;
import com.financeos.domain.report.definition.KpiDefinition;
import com.financeos.domain.report.definition.MeasureRef;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Filters on fields with an idField (stable id) and pie/donut series handling in the in-memory engine.
 */
class InMemoryReportExecutorIdFieldTest {

    private static final String RULE_A_ID = "id-a";
    private static final String RULE_B_ID = "id-b";

    private InMemoryReportExecutor executor;
    private Ds datasource;

    @BeforeEach
    void setUp() {
        executor = new InMemoryReportExecutor(new DateRangeResolver(4));
        datasource = new Ds();
        datasource.rows = List.of(
                row("Alpha", RULE_A_ID, "10", "Yes"),
                row("Beta", RULE_B_ID, "20", "No"),
                row("(none)", null, "40", "No"));   // rule-less line: no id, label "(none)"
    }

    private static Map<String, Object> row(String rule, String ruleId, String amount, String achieved) {
        Map<String, Object> m = new HashMap<>();
        m.put("rule", rule);
        m.put("ruleId", ruleId);
        m.put("amount", new BigDecimal(amount));
        m.put("achieved", achieved);
        return m;
    }

    private static ArrayNode array(String... values) {
        ArrayNode n = JsonNodeFactory.instance.arrayNode();
        for (String v : values) n.add(v);
        return n;
    }

    private BigDecimal sum(String field, String op, com.fasterxml.jackson.databind.JsonNode value) {
        KpiDefinition def = new KpiDefinition("amount", Aggregation.SUM, List.of(new FilterClause(field, op, value)), null);
        return executor.execute(def, datasource, Map.of()).value();
    }

    // ---------- positive operators: id OR label ----------

    @Test
    void isMatchesById() {
        assertEquals(new BigDecimal("10"), sum("rule", "is", TextNode.valueOf(RULE_A_ID)));
    }

    @Test
    void isMatchesByLegacyLabel() {
        assertEquals(new BigDecimal("10"), sum("rule", "is", TextNode.valueOf("Alpha")));
    }

    @Test
    void isMatchesNothingWhenNeitherIdNorLabelMatches() {
        assertEquals(BigDecimal.ZERO, sum("rule", "is", TextNode.valueOf("zzz")));
    }

    @Test
    void inMatchesRowsByIdAndByLabelInTheSameList() {
        // one entry is an id (Alpha row), the other a legacy label (Beta row)
        assertEquals(new BigDecimal("30"), sum("rule", "in", array(RULE_A_ID, "Beta")));
    }

    @Test
    void inMatchesByLabelForRuleLessLineWithNullId() {
        assertEquals(new BigDecimal("40"), sum("rule", "in", array("(none)")));
    }

    @Test
    void isByLabelOnRuleLessLineNeedsLabelBecauseIdIsNull() {
        assertEquals(new BigDecimal("40"), sum("rule", "is", TextNode.valueOf("(none)")));
    }

    // ---------- negated operators: id AND label ----------

    @Test
    void isNotExcludesRowMatchedById() {
        // Alpha excluded by id; Beta and (none) remain
        assertEquals(new BigDecimal("60"), sum("rule", "is_not", TextNode.valueOf(RULE_A_ID)));
    }

    @Test
    void isNotExcludesRowMatchedByLegacyLabel() {
        assertEquals(new BigDecimal("60"), sum("rule", "is_not", TextNode.valueOf("Alpha")));
    }

    @Test
    void isNotKeepsRuleLessRowWithNullIdWhenExcludingAnotherRule() {
        // null id passes the negated id check, label "(none)" passes too
        BigDecimal kept = sum("rule", "is_not", TextNode.valueOf(RULE_B_ID));
        assertEquals(new BigDecimal("50"), kept);   // Alpha 10 + (none) 40
    }

    @Test
    void isNotOnLabelNoneExcludesTheRuleLessLineViaLabelEvenThoughIdIsNull() {
        // byId passes (null), byLabel fails ("(none)" equals target) -> excluded
        assertEquals(new BigDecimal("30"), sum("rule", "is_not", TextNode.valueOf("(none)")));
    }

    @Test
    void notInExcludesRowsMatchedByIdOrLabel() {
        // Alpha excluded by id, Beta excluded by label; only (none) remains
        assertEquals(new BigDecimal("40"), sum("rule", "not_in", array(RULE_A_ID, "Beta")));
    }

    @Test
    void notInWithRuleLessLabelExcludesItDespiteNullId() {
        assertEquals(new BigDecimal("30"), sum("rule", "not_in", array("(none)")));
    }

    // ---------- Yes/No dimension ----------

    @Test
    void yesNoDimensionFilterIsAndIsNot() {
        assertEquals(new BigDecimal("10"), sum("achieved", "is", TextNode.valueOf("Yes")));
        assertEquals(new BigDecimal("10"), sum("achieved", "is_not", TextNode.valueOf("No")));
    }

    @Test
    void yesNoDimensionGroupsAChart() {
        ChartDefinition def = new ChartDefinition(ChartType.BAR, new DimensionRef("achieved", null), null,
                new MeasureRef("amount", Aggregation.SUM), List.of());
        ChartData chart = executor.execute(def, datasource, Map.of());
        assertEquals(List.of("No", "Yes"), chart.categories().stream().sorted().toList());
        int yes = chart.categories().indexOf("Yes");
        int no = chart.categories().indexOf("No");
        assertEquals(new BigDecimal("10"), chart.series().get(0).data().get(yes));
        assertEquals(new BigDecimal("60"), chart.series().get(0).data().get(no));
    }

    // ---------- pie / donut ignore series ----------

    private ChartData chart(ChartType type) {
        ChartDefinition def = new ChartDefinition(type, new DimensionRef("rule", null), new DimensionRef("achieved", null),
                new MeasureRef("amount", Aggregation.SUM), List.of());
        return executor.execute(def, datasource, Map.of());
    }

    @Test
    void pieIgnoresSeriesAndHasOneSeriesNamedAfterTheMeasure() {
        ChartData pie = chart(ChartType.PIE);
        assertEquals(1, pie.series().size());
        assertEquals("amount", pie.series().get(0).name());
        assertEquals(3, pie.categories().size());
    }

    @Test
    void donutIgnoresSeriesAndHasOneSeriesNamedAfterTheMeasure() {
        ChartData donut = chart(ChartType.DONUT);
        assertEquals(1, donut.series().size());
        assertEquals("amount", donut.series().get(0).name());
    }

    @Test
    void barWithSeriesStillSplitsBySeries() {
        ChartData bar = chart(ChartType.BAR);
        assertEquals(List.of("No", "Yes"), bar.series().stream().map(ChartData.Series::name).sorted().toList());
    }

    // ---------- fake ----------

    private static class Ds implements ComputedReportDatasource {
        List<Map<String, Object>> rows = List.of();

        @Override public String name() { return "ids"; }
        @Override public String label() { return "Ids"; }
        @Override public List<Map<String, Object>> rows() { return rows; }

        @Override
        public List<FieldDef> fields() {
            List<ReportType> all = List.of(ReportType.CHART, ReportType.TABLE);
            return List.of(
                    new FieldDef("rule", "Rule", FieldType.ENUM, FieldRole.DIMENSION, null, null, true, all, null, "ruleId"),
                    new FieldDef("achieved", "Achieved", FieldType.ENUM, FieldRole.DIMENSION, null, List.of("Yes", "No"), null, all),
                    new FieldDef("amount", "Amount", FieldType.NUMBER, FieldRole.MEASURE,
                            List.of(Aggregation.SUM), null, null, List.of(), "currency"));
        }
    }
}
