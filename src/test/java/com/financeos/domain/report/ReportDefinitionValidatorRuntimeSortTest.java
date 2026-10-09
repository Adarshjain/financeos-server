package com.financeos.domain.report;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;

import com.financeos.core.exception.ValidationException;
import com.financeos.domain.report.datasource.Aggregation;
import com.financeos.domain.report.datasource.DatasourceRegistry;
import com.financeos.domain.report.definition.AggregatedTableDefinition;
import com.financeos.domain.report.definition.DimensionRef;
import com.financeos.domain.report.definition.Granularity;
import com.financeos.domain.report.definition.MeasureRef;
import com.financeos.domain.report.definition.RawTableDefinition;
import com.financeos.domain.report.definition.SortClause;
import com.financeos.domain.report.definition.SortDirection;
import com.financeos.domain.report.definition.TableMode;
import java.util.List;
import org.junit.jupiter.api.Test;

/** {@link ReportDefinitionValidator#validateRuntimeSort}: a header sort follows the saved-sort rules. */
class ReportDefinitionValidatorRuntimeSortTest {

    private final ReportDefinitionValidator validator = new ReportDefinitionValidator(mock(DatasourceRegistry.class));

    private static final RawTableDefinition RAW = new RawTableDefinition(TableMode.RAW,
            List.of("date", "description", "amount"), List.of(), List.of(new SortClause("date", SortDirection.DESC)));

    private static AggregatedTableDefinition pivot(List<DimensionRef> columns) {
        return new AggregatedTableDefinition(TableMode.AGGREGATED,
                List.of(new DimensionRef("category", null), new DimensionRef("date", Granularity.MONTH)), columns,
                List.of(new MeasureRef("amount", Aggregation.SUM), new MeasureRef("amount", Aggregation.COUNT)),
                List.of(), List.of());
    }

    private static SortClause asc(String key) {
        return new SortClause(key, SortDirection.ASC);
    }

    private void assertRejected(Runnable check, String key) {
        ValidationException e = assertThrows(ValidationException.class, check::run);
        assertEquals("Sort key is not an available column: " + key, e.getMessage());
    }

    @Test
    void aRawTableSortsByAnyOfItsColumns() {
        for (String column : RAW.columns()) {
            assertDoesNotThrow(() -> validator.validateRuntimeSort(RAW, asc(column)));
        }
    }

    @Test
    void aRawTableRejectsAKeyThatIsNotOneOfItsColumns() {
        assertRejected(() -> validator.validateRuntimeSort(RAW, asc("category")), "category");
        assertRejected(() -> validator.validateRuntimeSort(RAW, asc("amount_sum")), "amount_sum");
    }

    @Test
    void aPivotSortsByItsRowDimensionsAndMeasuresWhenItHasNoColumns() {
        AggregatedTableDefinition flat = pivot(List.of());

        for (String key : List.of("category", "date", "amount_sum", "amount_count")) {
            assertDoesNotThrow(() -> validator.validateRuntimeSort(flat, asc(key)));
        }
    }

    @Test
    void aPivotWithColumnDimensionsSortsByRowDimensionsOnly() {
        AggregatedTableDefinition crossTab = pivot(List.of(new DimensionRef("type", null)));

        assertDoesNotThrow(() -> validator.validateRuntimeSort(crossTab, asc("category")));
        assertRejected(() -> validator.validateRuntimeSort(crossTab, asc("amount_sum")), "amount_sum");
        assertRejected(() -> validator.validateRuntimeSort(crossTab, asc("type")), "type");
    }

    @Test
    void aPivotRejectsAMeasureKeyWithAnotherAggregation() {
        assertRejected(() -> validator.validateRuntimeSort(pivot(List.of()), asc("amount_avg")), "amount_avg");
    }
}
