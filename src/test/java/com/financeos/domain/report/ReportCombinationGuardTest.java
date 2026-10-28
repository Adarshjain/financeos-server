package com.financeos.domain.report;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.financeos.core.exception.ValidationException;
import com.financeos.domain.report.datasource.Aggregation;
import com.financeos.domain.report.datasource.DatasourceCatalog.FieldDef;
import com.financeos.domain.report.datasource.DatasourceCatalog.FieldGuard;
import com.financeos.domain.report.datasource.DatasourceCatalog.SingleDatasourceView;
import com.financeos.domain.report.datasource.FieldRole;
import com.financeos.domain.report.datasource.FieldType;
import com.financeos.domain.report.datasource.ReportDatasource;
import com.financeos.domain.report.datasource.impl.FnoTradesDatasource;
import com.financeos.domain.report.datasource.impl.InvestmentTradesDatasource;
import com.financeos.domain.report.datasource.impl.LendingsDatasource;
import com.financeos.domain.report.datasource.impl.NetWorthDatasource;
import com.financeos.domain.report.datasource.impl.PortfolioValueDatasource;
import com.financeos.domain.report.datasource.impl.PositionsDatasource;
import com.financeos.domain.report.datasource.impl.RealizedLotsDatasource;
import com.financeos.domain.report.datasource.impl.RewardCapsDatasource;
import com.financeos.domain.report.datasource.impl.RewardEarningsDatasource;
import com.financeos.domain.report.datasource.impl.RewardMilestonesDatasource;
import com.financeos.domain.report.definition.AggregatedTableDefinition;
import com.financeos.domain.report.definition.ChartDefinition;
import com.financeos.domain.report.definition.ChartType;
import com.financeos.domain.report.definition.DimensionRef;
import com.financeos.domain.report.definition.FilterClause;
import com.financeos.domain.report.definition.KpiDefinition;
import com.financeos.domain.report.definition.MeasureRef;
import com.financeos.domain.report.definition.RawTableDefinition;
import com.financeos.domain.report.definition.ReportDefinition;
import com.financeos.domain.report.definition.TableMode;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ReportCombinationGuardTest {

    private static final ObjectMapper M = new ObjectMapper();
    private final ReportCombinationGuard guard = new ReportCombinationGuard(null);

    // ---------------------------------------------------------------- synthetic view

    private static FieldDef measure(String name, String label, FieldGuard... guards) {
        return new FieldDef(name, label, FieldType.NUMBER, FieldRole.MEASURE,
                List.of(Aggregation.SUM, Aggregation.AVG, Aggregation.COUNT, Aggregation.MIN, Aggregation.MAX),
                null, null, null, "number").withGuards(guards);
    }

    private static FieldDef dim(String name, String label, FieldType type) {
        return new FieldDef(name, label, type, FieldRole.DIMENSION, null, null, null, null);
    }

    private static SingleDatasourceView view(FieldDef... extra) {
        java.util.ArrayList<FieldDef> f = new java.util.ArrayList<>(List.of(
                dim("unit", "Unit", FieldType.ENUM), dim("kind", "Kind", FieldType.ENUM),
                dim("day", "Day", FieldType.DATE), dim("rule", "Rule", FieldType.ENUM),
                dim("code", "Code", FieldType.STRING)));
        f.addAll(List.of(extra));
        return new SingleDatasourceView("test", "Test", f);
    }

    private static MeasureRef m(String f, Aggregation a) {
        return new MeasureRef(f, a);
    }

    private static FilterClause is(String f, String v) {
        return new FilterClause(f, "is", M.valueToTree(v));
    }

    private static FilterClause in(String f, String... v) {
        return new FilterClause(f, "in", M.valueToTree(v));
    }

    private static ChartDefinition chart(String dimension, MeasureRef measure, FilterClause... filters) {
        return new ChartDefinition(ChartType.BAR, new DimensionRef(dimension, null), null, measure,
                List.of(filters), null, null, null, null, null, null, null, null);
    }

    private static KpiDefinition kpi(MeasureRef measure, FilterClause... filters) {
        return new KpiDefinition(measure.field(), measure.aggregation(), List.of(filters), null);
    }

    private List<String> problems(SingleDatasourceView v, ReportDefinition d) {
        return guard.problems(v, d);
    }

    // ---------------------------------------------------------------- no_aggregation

    @Test
    void noAggregationBlocksSumButAllowsAvg() {
        SingleDatasourceView v = view(measure("pct", "Utilisation (%)",
                FieldGuard.noAggregation(Set.of(Aggregation.SUM), "is a percentage, so it can't be summed")));

        assertEquals(List.of("Utilisation (%) is a percentage, so it can't be summed."),
                problems(v, kpi(m("pct", Aggregation.SUM))));
        assertTrue(problems(v, kpi(m("pct", Aggregation.AVG))).isEmpty());
    }

    // ---------------------------------------------------------------- needs

    private SingleDatasourceView needsView() {
        return view(measure("limit", "Cap limit", FieldGuard.needs(List.of("unit", "day"), "mixes ₹ and points")));
    }

    @Test
    void needsMessageIsExact() {
        assertEquals(List.of("Cap limit mixes ₹ and points. Split by Unit or Day, or filter it to one value."),
                problems(needsView(), kpi(m("limit", Aggregation.SUM))));
    }

    @Test
    void needsIsSatisfiedBySplit() {
        assertTrue(problems(needsView(), chart("unit", m("limit", Aggregation.SUM))).isEmpty());
    }

    @Test
    void needsIsSatisfiedBySingleIsFilterAndSingleValueIn() {
        assertTrue(problems(needsView(), kpi(m("limit", Aggregation.SUM), is("unit", "RUPEES"))).isEmpty());
        assertTrue(problems(needsView(), kpi(m("limit", Aggregation.SUM), in("unit", "RUPEES"))).isEmpty());
    }

    @Test
    void needsIsNotSatisfiedByMultiValueIn() {
        assertEquals(1, problems(needsView(), kpi(m("limit", Aggregation.SUM), in("unit", "RUPEES", "POINTS"))).size());
    }

    @Test
    void needsOnADateFieldAcceptsIsButNotIn() {
        assertTrue(problems(needsView(), kpi(m("limit", Aggregation.SUM), is("day", "2026-01-31"))).isEmpty());
        assertEquals(1, problems(needsView(), kpi(m("limit", Aggregation.SUM), in("day", "2026-01-31"))).size());
    }

    @Test
    void needsOnAStringFieldAcceptsExact() {
        SingleDatasourceView v = view(measure("qty", "Quantity", FieldGuard.needs(List.of("code"), "adds up units")));
        assertTrue(problems(v, kpi(m("qty", Aggregation.SUM), new FilterClause("code", "exact", M.valueToTree("X")))).isEmpty());
    }

    @Test
    void needsExemptsCountByDefaultAndHonoursAnAggregationSet() {
        assertTrue(problems(needsView(), kpi(m("limit", Aggregation.COUNT))).isEmpty());
        SingleDatasourceView avgOnly = view(measure("x", "X",
                FieldGuard.needs(List.of("unit"), Set.of(Aggregation.AVG), "is averaged badly")));
        assertTrue(problems(avgOnly, kpi(m("x", Aggregation.SUM))).isEmpty());
        assertEquals(1, problems(avgOnly, kpi(m("x", Aggregation.AVG))).size());
    }

    @Test
    void severalNeedsGuardsAreEachChecked() {
        SingleDatasourceView v = view(measure("limit", "Cap limit",
                FieldGuard.needs(List.of("unit"), "mixes ₹ and points"),
                FieldGuard.needs(List.of("day"), "adds up every window")));
        assertEquals(2, problems(v, kpi(m("limit", Aggregation.SUM))).size());
        assertEquals(List.of("Cap limit adds up every window. Split by Day, or filter it to one value."),
                problems(v, chart("unit", m("limit", Aggregation.SUM))));
    }

    @Test
    void aTableMeasureIsCheckedAgainstTheTablesSplitsAndFilters() {
        SingleDatasourceView v = needsView();
        AggregatedTableDefinition split = new AggregatedTableDefinition(TableMode.AGGREGATED,
                List.of(new DimensionRef("unit", null)), List.of(), List.of(m("limit", Aggregation.SUM)), List.of(), null);
        AggregatedTableDefinition none = new AggregatedTableDefinition(TableMode.AGGREGATED,
                List.of(new DimensionRef("rule", null)), List.of(), List.of(m("limit", Aggregation.SUM)), List.of(), null);
        assertTrue(problems(v, split).isEmpty());
        assertEquals(1, problems(v, none).size());
    }

    // ---------------------------------------------------------------- avoid

    private SingleDatasourceView avoidView() {
        return view(measure("spend", "Spend",
                FieldGuard.avoid(List.of("rule", "unit"), "counts once per transaction")));
    }

    @Test
    void avoidFiresOnSplitAndOnFilterWithExactMessage() {
        assertEquals(List.of("Spend counts once per transaction. Remove the split or filter on Rule."),
                problems(avoidView(), chart("rule", m("spend", Aggregation.SUM))));
        assertEquals(List.of("Spend counts once per transaction. Remove the split or filter on Rule and Unit."),
                problems(avoidView(), chart("rule", m("spend", Aggregation.SUM), is("unit", "x"))));
        assertEquals(1, problems(avoidView(), kpi(m("spend", Aggregation.SUM), is("rule", "r"))).size());
    }

    @Test
    void avoidDoesNotFireWhenAbsent() {
        assertTrue(problems(avoidView(), chart("kind", m("spend", Aggregation.SUM), is("code", "x"))).isEmpty());
    }

    // ---------------------------------------------------------------- no_filter

    @Test
    void noFilterFiresOnAnyReportTypeIncludingRawTables() {
        SingleDatasourceView v = view(dim("asOf", "As of", FieldType.DATE).withGuards(FieldGuard.noFilter("is always today")));
        FilterClause f = is("asOf", "2026-01-01");
        String expected = "As of is always today, so it can't be filtered.";
        assertEquals(List.of(expected), problems(v, new RawTableDefinition(TableMode.RAW, List.of("asOf"), List.of(f), null)));
        assertEquals(List.of(expected), problems(v, kpi(m("asOf", Aggregation.COUNT), f)));
        assertTrue(problems(v, new RawTableDefinition(TableMode.RAW, List.of("asOf"), List.of(), null)).isEmpty());
    }

    @Test
    void aRawTableIgnoresNeedsAvoidAndNoAggregation() {
        SingleDatasourceView v = view(measure("limit", "Cap limit", FieldGuard.needs(List.of("unit"), "mixes"),
                FieldGuard.avoid(List.of("rule"), "counts once"), FieldGuard.noAggregation(Set.of(Aggregation.SUM), "bad")));
        assertTrue(problems(v, new RawTableDefinition(TableMode.RAW, List.of("limit", "rule"), List.of(is("rule", "r")), null)).isEmpty());
    }

    // ---------------------------------------------------------------- enforce

    @Test
    void enforceThrowsTheFirstProblemOnly() {
        SingleDatasourceView v = view(measure("limit", "Cap limit",
                FieldGuard.needs(List.of("unit"), "mixes ₹ and points"),
                FieldGuard.needs(List.of("day"), "adds up every window")));
        ValidationException e = assertThrows(ValidationException.class,
                () -> guard.enforce(v, kpi(m("limit", Aggregation.SUM))));
        assertEquals("Cap limit mixes ₹ and points. Split by Unit, or filter it to one value.", e.getMessage());
    }

    @Test
    void problemsAreDeduplicated() {
        SingleDatasourceView v = view(measure("limit", "Cap limit",
                FieldGuard.needs(List.of("unit"), "mixes ₹ and points"),
                FieldGuard.needs(List.of("unit"), "mixes ₹ and points")));
        assertEquals(1, problems(v, kpi(m("limit", Aggregation.SUM))).size());
    }

    // ---------------------------------------------------------------- real catalog

    private static SingleDatasourceView real(ReportDatasource ds) {
        return new SingleDatasourceView(ds.name(), ds.label(), ds.fields(), ds.billingCycleAccountField());
    }

    private void assertBlocked(ReportDatasource ds, String measure, Aggregation agg, String... splits) {
        ReportDefinition d = splits.length == 0 ? kpi(m(measure, agg)) : chart(splits[0], m(measure, agg));
        assertEquals(1, problems(real(ds), d).size(), measure + " on " + ds.name());
    }

    private void assertAllowed(ReportDatasource ds, String measure, Aggregation agg, String split) {
        assertTrue(problems(real(ds), chart(split, m(measure, agg))).isEmpty(), measure + " by " + split);
    }

    @Test
    void realRewardCatalogs() {
        RewardCapsDatasource caps = new RewardCapsDatasource(null, null);
        assertEquals(2, problems(real(caps), kpi(m("capLimit", Aggregation.SUM))).size());
        assertEquals(2, problems(real(caps), kpi(m("remaining", Aggregation.SUM))).size());
        assertEquals(1, problems(real(caps), chart("unit", m("capLimit", Aggregation.SUM))).size()); // still needs a window
        assertBlocked(caps, "used", Aggregation.SUM);
        assertAllowed(caps, "used", Aggregation.SUM, "unit");
        assertEquals(List.of("Utilisation (%) is a percentage, so it can't be summed."),
                problems(real(caps), chart("card", m("utilizationPct", Aggregation.SUM))));
        assertTrue(problems(real(caps), chart("card", m("utilizationPct", Aggregation.AVG))).isEmpty());

        RewardMilestonesDatasource ms = new RewardMilestonesDatasource(null, null);
        assertEquals(2, problems(real(ms), kpi(m("threshold", Aggregation.SUM))).size());
        assertBlocked(ms, "progress", Aggregation.SUM);
        assertBlocked(ms, "payoutValue", Aggregation.SUM);
        assertAllowed(ms, "payoutValue", Aggregation.SUM, "rewardType");
        assertEquals(1, problems(real(ms), kpi(m("progressPct", Aggregation.SUM))).size());

        RewardEarningsDatasource earn = new RewardEarningsDatasource(null, null);
        assertBlocked(earn, "earned", Aggregation.SUM);
        assertAllowed(earn, "earned", Aggregation.SUM, "earnedUnit");
        assertEquals(1, problems(real(earn), chart("rule", m("spend", Aggregation.SUM))).size());
        assertEquals(1, problems(real(earn), kpi(m("spend", Aggregation.SUM), is("reason", "x"))).size());
        assertTrue(problems(real(earn), chart("card", m("spend", Aggregation.SUM))).isEmpty());
    }

    @Test
    void realInvestmentCatalogs() {
        assertBlocked(new PositionsDatasource(null), "quantity", Aggregation.SUM);
        assertBlocked(new RealizedLotsDatasource(null), "quantity", Aggregation.SUM);
        assertBlocked(new InvestmentTradesDatasource(null, null), "quantity", Aggregation.SUM);
        assertAllowed(new InvestmentTradesDatasource(null, null), "quantity", Aggregation.SUM, "instrument");
        assertBlocked(new FnoTradesDatasource(null, null), "quantity", Aggregation.SUM);

        PositionsDatasource positions = new PositionsDatasource(null);
        assertBlocked(positions, "xirr", Aggregation.AVG);
        assertBlocked(positions, "unrealizedPercent", Aggregation.AVG);
        assertTrue(problems(real(positions), kpi(m("xirr", Aggregation.MAX))).isEmpty());
    }

    @Test
    void realOtherCatalogs() {
        assertBlocked(new PortfolioValueDatasource(null, null, null, null, null), "value", Aggregation.SUM);
        assertAllowed(new PortfolioValueDatasource(null, null, null, null, null), "value", Aggregation.SUM, "valueDate");

        LendingsDatasource lendings = new LendingsDatasource(null);
        assertEquals(2, problems(real(lendings), kpi(m("amount", Aggregation.SUM))).size());
        assertEquals(1, problems(real(lendings), chart("direction", m("amount", Aggregation.SUM))).size());
        assertTrue(problems(real(lendings), kpi(m("signedAmount", Aggregation.SUM))).isEmpty());

        NetWorthDatasource nw = new NetWorthDatasource(null, null, null);
        assertBlocked(nw, "value", Aggregation.SUM);
        assertAllowed(nw, "value", Aggregation.SUM, "side");
        assertEquals(List.of("As of is always today, so it can't be filtered."),
                problems(real(nw), chart("side", m("value", Aggregation.SUM), is("asOf", "2026-01-01"))));
    }

    // ---------------------------------------------------------------- series / subDimension / JSON

    @Test
    void aSeriesOrSubDimensionSplitSatisfiesNeeds() {
        SingleDatasourceView v = needsView();
        ChartDefinition series = new ChartDefinition(ChartType.BAR, new DimensionRef("rule", null),
                new DimensionRef("unit", null), m("limit", Aggregation.SUM), List.of(), null, null, null, null,
                null, null, null, null);
        ChartDefinition sub = new ChartDefinition(ChartType.BREAKDOWN, new DimensionRef("rule", null), null,
                m("limit", Aggregation.SUM), List.of(), null, null, null, null,
                new DimensionRef("unit", null), null, null, null);
        assertTrue(problems(v, series).isEmpty());
        assertTrue(problems(v, sub).isEmpty());
    }

    @Test
    void aFieldDefWithGuardsSerialisesSnakeCaseAndOmitsNulls() throws Exception {
        FieldDef f = measure("limit", "Cap limit",
                FieldGuard.needs(List.of("unit"), "mixes"),
                FieldGuard.noAggregation(Set.of(Aggregation.SUM), "bad"),
                FieldGuard.noFilter("always today"));
        com.fasterxml.jackson.databind.JsonNode guards = M.valueToTree(f).path("guards");
        assertEquals("needs", guards.get(0).path("kind").asText());
        assertEquals("unit", guards.get(0).path("fields").get(0).asText());
        assertEquals(4, guards.get(0).path("aggregations").size());
        assertEquals("no_aggregation", guards.get(1).path("kind").asText());
        assertEquals("sum", guards.get(1).path("aggregations").get(0).asText());
        assertTrue(guards.get(1).path("fields").isMissingNode());
        assertEquals("no_filter", guards.get(2).path("kind").asText());
        assertTrue(guards.get(2).path("fields").isMissingNode());
        assertTrue(guards.get(2).path("aggregations").isMissingNode());
        assertTrue(M.valueToTree(dim("a", "A", FieldType.ENUM)).path("guards").isMissingNode());
    }
}
