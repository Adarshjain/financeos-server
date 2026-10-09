package com.financeos.domain.report.engine;

import com.financeos.domain.report.datasource.Aggregation;
import com.financeos.domain.report.datasource.ComputedReportDatasource;
import com.financeos.domain.report.datasource.DatasourceCatalog.FieldDef;
import com.financeos.domain.report.datasource.FieldRole;
import com.financeos.domain.report.datasource.FieldType;
import com.financeos.domain.report.definition.AggregatedTableDefinition;
import com.financeos.domain.report.definition.DimensionRef;
import com.financeos.domain.report.definition.Granularity;
import com.financeos.domain.report.definition.MeasureRef;
import com.financeos.domain.report.definition.SortClause;
import com.financeos.domain.report.definition.SortDirection;
import com.financeos.domain.report.definition.TableMode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** In-memory pivot tables honour sort clauses (saved or run-time) with the SQL pivot's semantics. */
class InMemoryReportExecutorPivotSortTest {

    private static final MeasureRef SUM = new MeasureRef("amount", Aggregation.SUM);
    private static final MeasureRef AVG = new MeasureRef("amount", Aggregation.AVG);

    private InMemoryReportExecutor executor;
    private Ds ds;

    @BeforeEach
    void setUp() {
        executor = new InMemoryReportExecutor(new DateRangeResolver(4));
        ds = new Ds();
    }

    private static Map<String, Object> row(String cat, String color, String amount) {
        Map<String, Object> m = new HashMap<>();
        m.put("cat", cat);
        m.put("color", color);
        m.put("amount", amount == null ? null : new BigDecimal(amount));
        return m;
    }

    private static Map<String, Object> dated(LocalDate date, String amount) {
        Map<String, Object> m = new HashMap<>();
        m.put("date", date);
        m.put("amount", new BigDecimal(amount));
        return m;
    }

    private static SortClause asc(String key) {
        return new SortClause(key, SortDirection.ASC);
    }

    private static SortClause desc(String key) {
        return new SortClause(key, SortDirection.DESC);
    }

    private static DimensionRef dim(String field) {
        return new DimensionRef(field, null);
    }

    private PivotTableData pivot(List<DimensionRef> rows, List<DimensionRef> columns, MeasureRef measure,
                                 List<SortClause> sort, int page, int size) {
        return executor.execute(new AggregatedTableDefinition(TableMode.AGGREGATED, rows, columns, List.of(measure),
                List.of(), sort), ds, Map.of(), page, size);
    }

    private List<String> order(List<DimensionRef> rows, List<DimensionRef> columns, MeasureRef measure, SortClause... sort) {
        return keys(pivot(rows, columns, measure, List.of(sort), 0, 50));
    }

    private static List<String> keys(PivotTableData data) {
        return data.rows().stream().map(PivotTableData.Row::key).toList();
    }

    @Test
    void aRowDimensionSortsByItsValue() {
        ds.rows = List.of(row("B", "red", "1"), row("A", "red", "1"), row("C", "red", "1"));

        assertEquals(List.of("C", "B", "A"), order(List.of(dim("cat")), List.of(), SUM, desc("cat")));
        assertEquals(List.of("A", "B", "C"), order(List.of(dim("cat")), List.of(), SUM, asc("cat")));
    }

    @Test
    void aMeasureKeySortsByTheAggregateWithoutColumnDimensions() {
        ds.rows = List.of(row("A", "red", "5"), row("B", "red", "15"), row("B", "red", "5"), row("C", "red", "10"));

        assertEquals(List.of("B", "C", "A"), order(List.of(dim("cat")), List.of(), SUM, desc("amount_sum")));
        assertEquals(List.of("A", "C", "B"), order(List.of(dim("cat")), List.of(), SUM, asc("amount_sum")));
    }

    @Test
    void aMeasureKeyIsIgnoredWhenThereAreColumnDimensions() {
        ds.rows = List.of(row("A", "red", "5"), row("B", "blue", "20"), row("C", "red", "10"));

        assertEquals(List.of("A", "B", "C"),
                order(List.of(dim("cat")), List.of(dim("color")), SUM, desc("amount_sum")));
    }

    @Test
    void aKeyThatIsNeitherARowDimensionNorAMeasureIsIgnored() {
        ds.rows = List.of(row("B", "red", "1"), row("A", "blue", "2"));

        assertEquals(List.of("A", "B"), order(List.of(dim("cat")), List.of(), SUM, desc("color")));
        assertEquals(List.of("A", "B"), order(List.of(dim("cat")), List.of(), SUM, desc("nope")));
    }

    @Test
    void aDateDimensionSortsByItsBucketNotItsLabel() {
        ds.rows = List.of(dated(LocalDate.of(2026, 2, 10), "1"), dated(LocalDate.of(2026, 4, 3), "1"),
                dated(LocalDate.of(2026, 1, 20), "1"));
        List<DimensionRef> byMonth = List.of(new DimensionRef("date", Granularity.MONTH));

        List<String> chronological = order(byMonth, List.of(), SUM);
        List<String> newestFirst = order(byMonth, List.of(), SUM, desc("date"));

        assertEquals(3, chronological.size());
        assertEquals(List.of(chronological.get(2), chronological.get(1), chronological.get(0)), newestFirst);
    }

    @Test
    void nullDimensionValuesSortFirstAscendingAndLastDescending() {
        ds.rows = List.of(row("B", "red", "1"), row(null, "red", "1"), row("A", "red", "1"));

        assertEquals(List.of("(none)", "A", "B"), order(List.of(dim("cat")), List.of(), SUM, asc("cat")));
        assertEquals(List.of("B", "A", "(none)"), order(List.of(dim("cat")), List.of(), SUM, desc("cat")));
    }

    @Test
    void nullAggregatesSortFirstAscendingAndLastDescending() {
        ds.rows = List.of(row("A", "red", "4"), row("B", "red", null), row("C", "red", "2"));

        assertEquals(List.of("B", "C", "A"), order(List.of(dim("cat")), List.of(), AVG, asc("amount_avg")));
        assertEquals(List.of("A", "C", "B"), order(List.of(dim("cat")), List.of(), AVG, desc("amount_avg")));
    }

    @Test
    void rowsTiedOnEveryClauseKeepTheDefaultOrderAndLaterClausesBreakTies() {
        ds.rows = List.of(row("A", "red", "10"), row("A", "blue", "10"), row("B", "red", "5"));
        List<DimensionRef> rows = List.of(dim("cat"), dim("color"));

        assertEquals(List.of("A / blue", "A / red", "B / red"), order(rows, List.of(), SUM, desc("amount_sum")));
        assertEquals(List.of("A / red", "A / blue", "B / red"),
                order(rows, List.of(), SUM, desc("amount_sum"), desc("color")));
    }

    @Test
    void pagesFollowTheSortedOrderWithoutOverlap() {
        ds.rows = List.of(row("A", "red", "1"), row("B", "red", "5"), row("C", "red", "3"),
                row("D", "red", "4"), row("E", "red", "2"));
        List<SortClause> sort = List.of(desc("amount_sum"));

        List<String> paged = new ArrayList<>();
        for (int page = 0; page < 3; page++) {
            PivotTableData data = pivot(List.of(dim("cat")), List.of(), SUM, sort, page, 2);
            assertEquals(5, data.page().totalElements());
            paged.addAll(keys(data));
        }

        assertEquals(List.of("B", "D", "C", "E", "A"), paged);
    }

    private static class Ds implements ComputedReportDatasource {
        List<Map<String, Object>> rows = List.of();

        @Override public String name() { return "pivot_sort"; }
        @Override public String label() { return "Pivot sort"; }
        @Override public List<Map<String, Object>> rows() { return rows; }

        @Override
        public List<FieldDef> fields() {
            return List.of(
                    new FieldDef("cat", "Cat", FieldType.ENUM, FieldRole.DIMENSION, null, null, null, List.of()),
                    new FieldDef("color", "Color", FieldType.ENUM, FieldRole.DIMENSION, null, null, null, List.of()),
                    new FieldDef("date", "Date", FieldType.DATE, FieldRole.DIMENSION, null, null, null, List.of()),
                    new FieldDef("amount", "Amount", FieldType.NUMBER, FieldRole.MEASURE,
                            List.of(Aggregation.SUM, Aggregation.AVG), null, null, List.of(), "currency"));
        }
    }
}
