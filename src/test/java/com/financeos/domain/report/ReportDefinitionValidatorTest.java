package com.financeos.domain.report;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.node.TextNode;
import com.financeos.core.exception.ValidationException;
import com.financeos.domain.report.datasource.Aggregation;
import com.financeos.domain.report.datasource.DatasourceCatalog;
import com.financeos.domain.report.definition.ChartDefinition;
import com.financeos.domain.report.definition.ChartType;
import com.financeos.domain.report.definition.DimensionRef;
import com.financeos.domain.report.definition.FilterClause;
import com.financeos.domain.report.definition.Granularity;
import com.financeos.domain.report.definition.KpiDefinition;
import com.financeos.domain.report.definition.MeasureRef;
import java.util.List;
import org.junit.jupiter.api.Test;

class ReportDefinitionValidatorTest {

    private final ReportDefinitionValidator validator = new ReportDefinitionValidator(new DatasourceCatalog());

    @Test
    void validKpiPasses() {
        KpiDefinition def = new KpiDefinition("amount", Aggregation.SUM, false, List.of(
                new FilterClause("type", "is", TextNode.valueOf("DEBIT")),
                new FilterClause("date", "this_month", null)), null);
        assertDoesNotThrow(() -> validator.validate("transactions", def));
    }

    @Test
    void missingIncludeExcludedFails() {
        KpiDefinition def = new KpiDefinition("amount", Aggregation.SUM, null, List.of(), null);
        ValidationException ex = assertThrows(ValidationException.class,
                () -> validator.validate("transactions", def));
        assertTrue(ex.getMessage().contains("includeExcluded"));
    }

    @Test
    void kpiMeasureMustBeAMeasureField() {
        // 'date' is a dimension, not a measure.
        KpiDefinition def = new KpiDefinition("date", Aggregation.SUM, false, List.of(), null);
        assertThrows(ValidationException.class, () -> validator.validate("transactions", def));
    }

    @Test
    void invalidOperatorForFieldTypeFails() {
        // 'contains' is a string operator, invalid for the numeric 'amount'.
        KpiDefinition def = new KpiDefinition("amount", Aggregation.SUM, false,
                List.of(new FilterClause("amount", "contains", TextNode.valueOf("x"))), null);
        assertThrows(ValidationException.class, () -> validator.validate("transactions", def));
    }

    @Test
    void staticEnumMembershipIsEnforced() {
        KpiDefinition def = new KpiDefinition("amount", Aggregation.SUM, false,
                List.of(new FilterClause("type", "is", TextNode.valueOf("FOO"))), null);
        assertThrows(ValidationException.class, () -> validator.validate("transactions", def));
    }

    @Test
    void chartDateDimensionRequiresGranularity() {
        ChartDefinition def = new ChartDefinition(ChartType.BAR,
                new DimensionRef("date", null), null,
                new MeasureRef("amount", Aggregation.SUM), false, List.of());
        ValidationException ex = assertThrows(ValidationException.class,
                () -> validator.validate("transactions", def));
        assertTrue(ex.getMessage().toLowerCase().contains("granularity"));
    }

    @Test
    void validChartWithSeriesPasses() {
        ChartDefinition def = new ChartDefinition(ChartType.BAR,
                new DimensionRef("date", Granularity.MONTH),
                new DimensionRef("category", null),
                new MeasureRef("amount", Aggregation.SUM), false, List.of());
        assertDoesNotThrow(() -> validator.validate("transactions", def));
    }
}
