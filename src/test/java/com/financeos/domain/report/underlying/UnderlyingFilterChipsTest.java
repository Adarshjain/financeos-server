package com.financeos.domain.report.underlying;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.financeos.api.report.dto.ReportFieldValuesResponse;
import com.financeos.api.report.dto.ReportFieldValuesResponse.Option;
import com.financeos.domain.report.ReportFieldValuesService;
import com.financeos.domain.report.ReportType;
import com.financeos.domain.report.datasource.DatasourceCatalog.FieldDef;
import com.financeos.domain.report.datasource.FieldRole;
import com.financeos.domain.report.datasource.FieldType;
import com.financeos.domain.report.datasource.ReportDatasource;
import com.financeos.domain.report.definition.FilterClause;
import com.financeos.domain.report.engine.ReportQueryBuilder;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** KPI filter clauses rendered for people. */
class UnderlyingFilterChipsTest {

    private static final JsonNodeFactory JSON = JsonNodeFactory.instance;
    private static final List<ReportType> TABLE = List.of(ReportType.TABLE);

    private ReportFieldValuesService fieldValues;
    private UnderlyingFilterChips chips;
    private final ReportDatasource ds = new ReportDatasource() {
        @Override
        public String name() {
            return "rewards";
        }

        @Override
        public String label() {
            return "Rewards";
        }

        @Override
        public List<FieldDef> fields() {
            return List.of(
                    new FieldDef("date", "Date", FieldType.DATE, FieldRole.DIMENSION, null, null, null, TABLE),
                    new FieldDef("card", "Card", FieldType.ENUM, FieldRole.DIMENSION, null, null, true, TABLE, null, "cardId"),
                    new FieldDef("category", "Category", FieldType.ENUM, FieldRole.DIMENSION, null, null, true, TABLE),
                    new FieldDef("description", "Description", FieldType.STRING, FieldRole.DIMENSION, null, null, null, TABLE),
                    new FieldDef("amount", "Amount", FieldType.NUMBER, FieldRole.MEASURE, null, null, null, TABLE, "currency"),
                    new FieldDef("isExcluded", "Is excluded", FieldType.BOOLEAN, FieldRole.FILTER, null, null, null, List.of()));
        }

        @Override
        public ReportQueryBuilder queryBuilder() {
            return null;
        }
    };

    @BeforeEach
    void setUp() {
        fieldValues = mock(ReportFieldValuesService.class);
        chips = new UnderlyingFilterChips(fieldValues);
    }

    private static FilterClause f(String field, String op, JsonNode value) {
        return new FilterClause(field, op, value);
    }

    private String text(FilterClause clause) {
        return chips.describe(ds, List.of(clause)).get(0).text();
    }

    private static JsonNode array(String... values) {
        var array = JSON.arrayNode();
        for (String v : values) {
            array.add(v);
        }
        return array;
    }

    private static JsonNode range(String from, String to) {
        return JSON.objectNode().put("from", from).put("to", to);
    }

    private static JsonNode amount(int n) {
        return JSON.objectNode().put("amount", n);
    }

    @Test
    void oneChipPerClauseInOrderWithFieldLabelAndOperator() {
        List<UnderlyingFilterChip> out = chips.describe(ds, List.of(
                f("category", "is", JSON.textNode("Food")),
                f("date", "this_month", null)));

        assertEquals(List.of(
                new UnderlyingFilterChip("category", "Category", "is", "is Food"),
                new UnderlyingFilterChip("date", "Date", "this_month", "This month")), out);
    }

    @Test
    void internalClausesAreNotShown() {
        List<UnderlyingFilterChip> out = chips.describe(ds, List.of(
                f("category", "is", JSON.textNode("Food")),
                f("amount", UnderlyingOperators.PRESENT, null),
                f("amount", UnderlyingOperators.EQUAL_TO, JSON.numberNode(5))));

        assertEquals(1, out.size());
        assertEquals("category", out.get(0).field());
    }

    @Test
    void anUnknownFieldFallsBackToItsNameAndTextRendering() {
        UnderlyingFilterChip chip = chips.describe(ds, List.of(f("merchant", "contains", JSON.textNode("zomato")))).get(0);

        assertEquals("merchant", chip.fieldLabel());
        assertEquals("contains zomato", chip.text());
    }

    @Test
    void enumAndStringOperatorsReadWithTheirVerb() {
        assertEquals("is Food", text(f("category", "is", JSON.textNode("Food"))));
        assertEquals("is not Transfers", text(f("category", "is_not", JSON.textNode("Transfers"))));
        assertEquals("in Food, Fuel", text(f("category", "in", array("Food", "Fuel"))));
        assertEquals("not in Food, Fuel", text(f("category", "not_in", array("Food", "Fuel"))));
        assertEquals("is Rent", text(f("description", "exact", JSON.textNode("Rent"))));
        assertEquals("starts with Amazon", text(f("description", "starts_with", JSON.textNode("Amazon"))));
        assertEquals("ends with Ltd", text(f("description", "ends_with", JSON.textNode("Ltd"))));
        assertEquals("contains uber", text(f("description", "contains", JSON.textNode("uber"))));
        assertEquals("in Rent, EMI", text(f("description", "in", array("Rent", "EMI"))));
    }

    @Test
    void numberOperatorsReadPlainNumbers() {
        assertEquals("equals 500", text(f("amount", "equals", JSON.numberNode(new java.math.BigDecimal("500.00")))));
        assertEquals("greater than 1000.5", text(f("amount", "greater_than", JSON.numberNode(1000.5))));
        assertEquals("less than 0", text(f("amount", "less_than", JSON.numberNode(0))));
        assertEquals("between 100 and 2500", text(f("amount", "between",
                JSON.objectNode().put("from", 100).put("to", 2500))));
    }

    @Test
    void booleansReadYesOrNo() {
        assertEquals("is Yes", text(f("isExcluded", "is", JSON.booleanNode(true))));
        assertEquals("is No", text(f("isExcluded", "is", JSON.booleanNode(false))));
    }

    @Test
    void absoluteDatesReadAsWordsWithDayMonthYear() {
        assertEquals("On 09/10/2026", text(f("date", "is", JSON.textNode("2026-10-09"))));
        assertEquals("After 01/04/2026", text(f("date", "after", JSON.textNode("2026-04-01"))));
        assertEquals("Before 31/03/2026", text(f("date", "before", JSON.textNode("2026-03-31"))));
        assertEquals("Between 01/10/2026 and 31/10/2026", text(f("date", "between", range("2026-10-01", "2026-10-31"))));
    }

    @Test
    void namedRelativeDatesReadAsTheirName() {
        assertEquals("Today", text(f("date", "today", null)));
        assertEquals("Yesterday", text(f("date", "yesterday", null)));
        assertEquals("This week", text(f("date", "this_week", null)));
        assertEquals("This month", text(f("date", "this_month", null)));
        assertEquals("This year", text(f("date", "this_year", null)));
        assertEquals("Previous week", text(f("date", "previous_week", null)));
        assertEquals("Previous month", text(f("date", "previous_month", null)));
        assertEquals("Previous year", text(f("date", "previous_year", null)));
        assertEquals("This financial year", text(f("date", "current_fy", null)));
        assertEquals("Previous financial year", text(f("date", "prev_fy", null)));
        assertEquals("All time", text(f("date", "all_time", null)));
    }

    @Test
    void rollingDatesReadTheirCountWithASingularForOne() {
        assertEquals("Last 30 days", text(f("date", "last_x_days", amount(30))));
        assertEquals("Last 1 day", text(f("date", "last_x_days", amount(1))));
        assertEquals("Last 6 months", text(f("date", "last_x_months", amount(6))));
        assertEquals("Last 1 month", text(f("date", "last_x_months", amount(1))));
        assertEquals("Last 2 years", text(f("date", "last_x_years", amount(2))));
        assertEquals("Next 14 days", text(f("date", "next_x_days", amount(14))));
    }

    @Test
    void billingCyclesReadRelativeToThisCycle() {
        assertEquals("This billing cycle", text(f("date", "this_billing_cycle", null)));
        assertEquals("Previous billing cycle", text(f("date", "previous_billing_cycle", null)));
        assertEquals("This billing cycle", text(f("date", "billing_cycles_ago", amount(0))));
        assertEquals("Previous billing cycle", text(f("date", "billing_cycles_ago", amount(1))));
        assertEquals("2 billing cycles ago", text(f("date", "billing_cycles_ago", amount(2))));
    }

    @Test
    void unknownOperatorsAreHumanized() {
        assertEquals("Some window", text(f("date", "some_window", null)));
        assertEquals("Approx 5", text(f("amount", "approx", JSON.numberNode(5))));
        assertEquals("Sounds like foo", text(f("description", "sounds_like", JSON.textNode("foo"))));
    }

    @Test
    void idBackedFiltersShowTheOptionLabelAndFallBackToTheId() {
        when(fieldValues.values("rewards")).thenReturn(new ReportFieldValuesResponse(Map.of(), Map.of(
                "card", List.of(new Option("c1", "HDFC Regalia"), new Option("c2", "Amex Plat")))));

        assertEquals("is HDFC Regalia", text(f("card", "is", JSON.textNode("c1"))));
        assertEquals("is not Amex Plat", text(f("card", "is_not", JSON.textNode("c2"))));
        assertEquals("in HDFC Regalia, gone-id", text(f("card", "in", array("c1", "gone-id"))));
    }

    @Test
    void filterOptionsAreOnlyLoadedForIdBackedFields() {
        chips.describe(ds, List.of(f("category", "in", array("Food")), f("date", "this_month", null)));

        verify(fieldValues, never()).values(anyString());
    }
}
