package com.financeos.domain.report;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.node.BooleanNode;
import com.fasterxml.jackson.databind.node.TextNode;
import com.financeos.core.exception.ValidationException;
import com.financeos.domain.report.datasource.Aggregation;
import com.financeos.domain.report.datasource.DatasourceCatalog;
import com.financeos.domain.report.definition.AggregatedTableDefinition;
import com.financeos.domain.report.definition.ChartDefinition;
import com.financeos.domain.report.definition.ChartType;
import com.financeos.domain.report.definition.DimensionRef;
import com.financeos.domain.report.definition.FilterClause;
import com.financeos.domain.report.definition.Granularity;
import com.financeos.domain.report.definition.KpiDefinition;
import com.financeos.domain.report.definition.MeasureRef;
import com.financeos.domain.report.definition.RawTableDefinition;
import com.financeos.domain.report.definition.SortClause;
import com.financeos.domain.report.definition.SortDirection;
import com.financeos.domain.report.definition.TableMode;
import java.util.List;
import org.junit.jupiter.api.Test;

class ReportDefinitionValidatorTest {

    private final ReportDefinitionValidator validator = new ReportDefinitionValidator(new DatasourceCatalog());

    @Test
    void validKpiPasses() {
        KpiDefinition def = new KpiDefinition("amount", Aggregation.SUM, List.of(
                new FilterClause("type", "is", TextNode.valueOf("DEBIT")),
                new FilterClause("isExcluded", "is", BooleanNode.FALSE),
                new FilterClause("date", "this_month", null)), null);
        assertDoesNotThrow(() -> validator.validate("transactions", def));
    }

    @Test
    void kpiMeasureMustBeAMeasureField() {
        KpiDefinition def = new KpiDefinition("date", Aggregation.SUM, List.of(), null);
        assertThrows(ValidationException.class, () -> validator.validate("transactions", def));
    }

    @Test
    void invalidOperatorForFieldTypeFails() {
        KpiDefinition def = new KpiDefinition("amount", Aggregation.SUM,
                List.of(new FilterClause("amount", "contains", TextNode.valueOf("x"))), null);
        assertThrows(ValidationException.class, () -> validator.validate("transactions", def));
    }

    @Test
    void staticEnumMembershipIsEnforced() {
        KpiDefinition def = new KpiDefinition("amount", Aggregation.SUM,
                List.of(new FilterClause("type", "is", TextNode.valueOf("FOO"))), null);
        assertThrows(ValidationException.class, () -> validator.validate("transactions", def));
    }

    @Test
    void isExcludedIsFilterable() {
        KpiDefinition def = new KpiDefinition("amount", Aggregation.SUM,
                List.of(new FilterClause("isExcluded", "is", BooleanNode.TRUE)), null);
        assertDoesNotThrow(() -> validator.validate("transactions", def));
    }

    @Test
    void chartDateDimensionRequiresGranularity() {
        ChartDefinition def = new ChartDefinition(ChartType.BAR,
                new DimensionRef("date", null), null,
                new MeasureRef("amount", Aggregation.SUM), List.of());
        ValidationException ex = assertThrows(ValidationException.class,
                () -> validator.validate("transactions", def));
        assertTrue(ex.getMessage().toLowerCase().contains("granularity"));
    }

    @Test
    void validChartWithSeriesPasses() {
        ChartDefinition def = new ChartDefinition(ChartType.BAR,
                new DimensionRef("date", Granularity.MONTH),
                new DimensionRef("category", null),
                new MeasureRef("amount", Aggregation.SUM), List.of());
        assertDoesNotThrow(() -> validator.validate("transactions", def));
    }

    @Test
    void validPivotPasses() {
        AggregatedTableDefinition def = new AggregatedTableDefinition(TableMode.AGGREGATED,
                List.of(new DimensionRef("date", Granularity.MONTH)),
                List.of(new DimensionRef("category", null)),
                List.of(new MeasureRef("amount", Aggregation.SUM)),
                List.of(), List.of(new SortClause("date", SortDirection.ASC)));
        assertDoesNotThrow(() -> validator.validate("transactions", def));
    }

    @Test
    void pivotRowAndColumnOverlapFails() {
        AggregatedTableDefinition def = new AggregatedTableDefinition(TableMode.AGGREGATED,
                List.of(new DimensionRef("category", null)),
                List.of(new DimensionRef("category", null)),
                List.of(new MeasureRef("amount", Aggregation.SUM)),
                List.of(), null);
        assertThrows(ValidationException.class, () -> validator.validate("transactions", def));
    }

    @Test
    void rawTableRequiresColumns() {
        RawTableDefinition def = new RawTableDefinition(TableMode.RAW, List.of(), List.of(), null);
        assertThrows(ValidationException.class, () -> validator.validate("transactions", def));
    }

    @Test
    void rawTableRejectsMeasureSortKey() {
        // amount_sum is not a valid raw sort key (only selected column names are).
        RawTableDefinition def = new RawTableDefinition(TableMode.RAW,
                List.of("date", "amount"), List.of(),
                List.of(new SortClause("amount_sum", SortDirection.DESC)));
        assertThrows(ValidationException.class, () -> validator.validate("transactions", def));
    }
}
