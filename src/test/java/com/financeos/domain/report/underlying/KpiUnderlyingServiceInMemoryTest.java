package com.financeos.domain.report.underlying;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.financeos.api.report.dto.ReportFieldValuesResponse;
import com.financeos.core.exception.ValidationException;
import com.financeos.core.security.UserContext;
import com.financeos.domain.account.cycle.BillingCycleService;
import com.financeos.domain.account.cycle.BillingCycles.Cycle;
import com.financeos.domain.account.cycle.BillingCycles.Source;
import com.financeos.domain.account.cycle.CycleWindows;
import com.financeos.domain.report.ReportDataService;
import com.financeos.domain.report.ReportDataService.ResolvedDefinition;
import com.financeos.domain.report.ReportDefinitionValidator;
import com.financeos.domain.report.ReportFieldValuesService;
import com.financeos.domain.report.ReportType;
import com.financeos.domain.report.breakdown.RowBreakdownProvider;
import com.financeos.domain.report.breakdown.RowBreakdownService;
import com.financeos.domain.report.datasource.Aggregation;
import com.financeos.domain.report.datasource.ComputedReportDatasource;
import com.financeos.domain.report.datasource.DatasourceCatalog.FieldDef;
import com.financeos.domain.report.datasource.DatasourceRegistry;
import com.financeos.domain.report.datasource.FieldRole;
import com.financeos.domain.report.datasource.FieldType;
import com.financeos.domain.report.definition.FilterClause;
import com.financeos.domain.report.definition.KpiDefinition;
import com.financeos.domain.report.definition.SortClause;
import com.financeos.domain.report.definition.SortDirection;
import com.financeos.domain.report.engine.DateRangeResolver;
import com.financeos.domain.report.engine.InMemoryReportExecutor;
import com.financeos.domain.report.engine.KpiData;
import com.financeos.domain.report.engine.KpiPeriodResolver;
import com.financeos.domain.report.engine.KpiReportExecutor;
import com.financeos.domain.report.engine.TableData;
import com.financeos.domain.report.engine.TableReportExecutor;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.io.ByteArrayOutputStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * KPI underlying data over computed (in-memory) datasources: the value is the in-memory KPI's own
 * figure for each period, rows are listed through the datasource's hooks (or the fallback
 * columns), and {@link UnderlyingExtras} adds the datasource's totals and left-out items.
 */
class KpiUnderlyingServiceInMemoryTest {

    private static final List<ReportType> ALL = List.of(ReportType.KPI, ReportType.CHART, ReportType.TABLE);
    private static final List<ReportType> TABLE = List.of(ReportType.TABLE);

    private final UUID userId = UUID.randomUUID();
    private final UUID card = UUID.randomUUID();
    private BillingCycleService cycles;
    private InMemoryReportExecutor inMemory;
    private ReportDataService reportData;
    private ReportFieldValuesService fieldValues;
    private KpiUnderlyingService service;

    @BeforeEach
    void setUp() {
        UserContext.setCurrentUserId(userId);
        cycles = mock(BillingCycleService.class);
        when(cycles.windows(any(), anyInt(), any(), any())).thenReturn(new CycleWindows(Map.of()));
        DateRangeResolver resolver = new DateRangeResolver(4);
        inMemory = new InMemoryReportExecutor(resolver, cycles);
        reportData = mock(ReportDataService.class);
        fieldValues = mock(ReportFieldValuesService.class);
        service = service(List.of());
    }

    @AfterEach
    void tearDown() {
        UserContext.clear();
    }

    private KpiUnderlyingService service(List<RowBreakdownProvider> providers) {
        DateRangeResolver resolver = new DateRangeResolver(4);
        return new KpiUnderlyingService(reportData, new ReportDefinitionValidator(mock(DatasourceRegistry.class)),
                new KpiPeriodResolver(resolver, cycles), new KpiReportExecutor(resolver, cycles),
                new TableReportExecutor(resolver), inMemory,
                new UnderlyingFilterChips(fieldValues),
                new RowBreakdownService(mock(DatasourceRegistry.class), providers));
    }

    private KpiDefinition define(ComputedReportDatasource ds, KpiDefinition def) {
        when(reportData.resolveDefinition(ReportType.KPI, ds.name(), null)).thenReturn(new ResolvedDefinition(ds, def));
        return def;
    }

    private KpiUnderlyingResponse run(ComputedReportDatasource ds, String period, Integer page, Integer size, String sort) {
        return service.adHoc(ReportType.KPI, ds.name(), null, period, page, size, sort);
    }

    private static Map<String, Object> row(String id, LocalDate date, Object card, String kind, String value) {
        Map<String, Object> m = new HashMap<>();
        m.put("id", id);
        m.put("date", date);
        m.put("cardId", card);
        m.put("card", "HDFC Regalia");
        m.put("kind", kind);
        m.put("value", value == null ? null : new BigDecimal(value));
        return m;
    }

    private static FilterClause may() {
        return new FilterClause("date", "between",
                JsonNodeFactory.instance.objectNode().put("from", "2026-05-01").put("to", "2026-05-31"));
    }

    private static List<String> ids(KpiUnderlyingResponse response) {
        return ((TableData) response.table()).rows().stream().map(r -> (String) r.get("id")).toList();
    }

    private static List<String> columnKeys(KpiUnderlyingResponse response) {
        return ((TableData) response.table()).columns().stream().map(TableData.Column::key).toList();
    }

    // ---- the value equals the KPI's ----

    @ParameterizedTest
    @EnumSource(Aggregation.class)
    void theValueOfEachPeriodIsTheKpisValueForThatPeriod(Aggregation aggregation) {
        Plain ds = new Plain();
        ds.rows = List.of(row("a", LocalDate.of(2026, 5, 3), card, "x", "12.5"), row("b", LocalDate.of(2026, 5, 9), card, "x", "-4"),
                row("c", LocalDate.of(2026, 5, 9), card, "x", null), row("d", LocalDate.of(2026, 4, 20), card, "y", "30"),
                row("e", LocalDate.of(2026, 3, 1), card, "y", "999"));
        KpiDefinition def = define(ds, new KpiDefinition("value", aggregation, List.of(may()), null));

        KpiData kpi = inMemory.execute(def, ds, Map.of());

        assertEquals(kpi.value(), run(ds, "current", null, null, null).value());
        assertEquals(kpi.comparison().previousValue(), run(ds, "previous", null, null, null).value());
    }

    @ParameterizedTest
    @EnumSource(value = Aggregation.class, names = {"AVG", "MIN", "MAX"})
    void anEmptyPreviousPeriodHasNoValueLikeTheKpi(Aggregation aggregation) {
        Plain ds = new Plain();
        ds.rows = List.of(row("a", LocalDate.of(2026, 5, 3), card, "x", "12.5"));
        KpiDefinition def = define(ds, new KpiDefinition("value", aggregation, List.of(may()), null));

        KpiData kpi = inMemory.execute(def, ds, Map.of());
        KpiUnderlyingResponse previous = run(ds, "previous", null, null, null);

        assertNull(kpi.comparison().previousValue(), "an empty previous period has no previous value, as on the SQL path");
        assertNull(previous.value());
        assertTrue(previous.previousAvailable());
        assertEquals(0, previous.rowCount());
    }

    @ParameterizedTest
    @EnumSource(Aggregation.class)
    void billingCyclePeriodsReadTheSameValuesAsTheKpi(Aggregation aggregation) {
        windows(0, LocalDate.of(2026, 9, 5), LocalDate.of(2026, 10, 4));
        windows(1, LocalDate.of(2026, 8, 5), LocalDate.of(2026, 9, 4));
        when(fieldValues.values("plain")).thenReturn(new ReportFieldValuesResponse(Map.of(),
                Map.of("card", List.of(new ReportFieldValuesResponse.Option(card.toString(), "HDFC Regalia")))));
        Plain ds = new Plain();
        ds.rows = List.of(row("a", LocalDate.of(2026, 9, 5), card, "x", "100"), row("b", LocalDate.of(2026, 10, 4), card, "x", "7"),
                row("c", LocalDate.of(2026, 8, 5), card, "x", "40"), row("d", LocalDate.of(2026, 9, 4), card, "x", "2"),
                row("e", LocalDate.of(2026, 9, 10), UUID.randomUUID(), "x", "5000"));
        KpiDefinition def = define(ds, new KpiDefinition("value", aggregation, List.of(
                new FilterClause("card", "is", JsonNodeFactory.instance.textNode(card.toString())),
                new FilterClause("date", "this_billing_cycle", null)), null));

        KpiData kpi = inMemory.execute(def, ds, Map.of());
        KpiUnderlyingResponse current = run(ds, "current", null, null, null);
        KpiUnderlyingResponse previous = run(ds, "previous", null, null, null);

        assertEquals(kpi.value(), current.value());
        assertEquals(kpi.comparison().previousValue(), previous.value());
        assertEquals(new UnderlyingRange(LocalDate.of(2026, 8, 5), LocalDate.of(2026, 9, 4)), previous.range());
        assertEquals(new UnderlyingRange(LocalDate.of(2026, 8, 5), LocalDate.of(2026, 9, 4)), current.previousRange());
        assertEquals(List.of("is HDFC Regalia", "Previous billing cycle"),
                previous.filters().stream().map(UnderlyingFilterChip::text).toList());
    }

    private void windows(int ago, LocalDate start, LocalDate end) {
        when(cycles.windows(eq(userId), eq(ago), any(LocalDate.class), eq(card.toString())))
                .thenReturn(new CycleWindows(Map.of(card, new Cycle(start, end, Source.STATEMENT))));
    }

    // ---- rows ----

    @Test
    void listsOnlyRowsWithTheMeasure() {
        Plain ds = new Plain();
        ds.rows = List.of(row("a", LocalDate.of(2026, 5, 3), card, "x", "1"), row("b", LocalDate.of(2026, 5, 4), card, "x", null),
                row("c", LocalDate.of(2026, 5, 5), card, "x", "2"));
        define(ds, new KpiDefinition("value", Aggregation.COUNT, List.of(may()), null));

        KpiUnderlyingResponse response = run(ds, "current", null, null, null);

        assertEquals(List.of("c", "a"), ids(response));
        assertEquals(2, response.rowCount());
        assertEquals(new BigDecimal("2"), response.value());
    }

    @Test
    void maxListsEveryTiedWinner() {
        Plain ds = new Plain();
        ds.rows = List.of(row("a", LocalDate.of(2026, 5, 3), card, "x", "9"), row("b", LocalDate.of(2026, 5, 4), card, "x", "9.00"),
                row("c", LocalDate.of(2026, 5, 5), card, "x", "2"));
        define(ds, new KpiDefinition("value", Aggregation.MAX, List.of(may()), null));

        KpiUnderlyingResponse response = run(ds, "current", null, null, null);

        assertEquals(List.of("b", "a"), ids(response));
        assertTrue(response.winnerOnly());
    }

    @Test
    void withoutHooksTheFirstDateAndThreeTableDimensionsAreListedThenTheMeasure() {
        Plain ds = new Plain();
        define(ds, new KpiDefinition("value", Aggregation.SUM, List.of(), null));

        KpiUnderlyingResponse response = run(ds, null, null, null, null);

        assertEquals(List.of("date", "card", "kind", "note", "value"), columnKeys(response));
        assertNull(response.groupField());
        assertEquals(List.of(), response.summaryLines());
        assertEquals(List.of(), response.notCounted());
        assertNull(response.rowAction());
    }

    @Test
    void theResponseNamesTheKpisDatasource() {
        Plain ds = new Plain();
        define(ds, new KpiDefinition("value", Aggregation.SUM, List.of(), null));

        assertEquals("plain", run(ds, null, null, null, null).datasource());
    }

    @Test
    void theHookColumnsAreListedWithTheMeasureLastAndOnce() {
        Hooked ds = new Hooked();
        define(ds, new KpiDefinition("value", Aggregation.SUM, List.of(), null));

        assertEquals(List.of("kind", "card", "value"), columnKeys(run(ds, null, null, null, null)));
    }

    @Test
    void theHookOrderAppliesByDefaultEvenOnUnlistedFieldsAndTheGroupFieldIsEchoed() {
        Hooked ds = new Hooked();
        ds.rows = List.of(row("a", LocalDate.of(2026, 5, 3), card, "y", "5"), row("b", LocalDate.of(2026, 5, 4), card, "x", "1"),
                row("c", LocalDate.of(2026, 5, 5), card, "x", "3"));
        define(ds, new KpiDefinition("value", Aggregation.SUM, List.of(), null));

        KpiUnderlyingResponse response = run(ds, null, null, null, null);

        assertEquals(List.of("c", "b", "a"), ids(response));
        assertEquals("kind", response.groupField());
        assertNull(response.sortKey());
    }

    @Test
    void aRuntimeSortReplacesTheHookOrder() {
        Hooked ds = new Hooked();
        ds.rows = List.of(row("a", LocalDate.of(2026, 5, 3), card, "y", "5"), row("b", LocalDate.of(2026, 5, 4), card, "x", "1"),
                row("c", LocalDate.of(2026, 5, 5), card, "x", "3"));
        define(ds, new KpiDefinition("value", Aggregation.SUM, List.of(), null));

        KpiUnderlyingResponse response = run(ds, null, null, null, "value,desc");

        assertEquals(List.of("a", "c", "b"), ids(response));
        assertEquals("value", response.sortKey());
        assertEquals("desc", response.sortDirection());
    }

    @Test
    void aRuntimeSortMustBeAListedColumnEvenWhenTheHookOrdersByIt() {
        Hooked ds = new Hooked();
        define(ds, new KpiDefinition("value", Aggregation.SUM, List.of(), null));

        ValidationException e = assertThrows(ValidationException.class, () -> run(ds, null, null, null, "date,asc"));

        assertEquals("Sort key is not an available column: date", e.getMessage());
    }

    @Test
    void summaryLinesCoverEveryListedRowNotOnlyThePageAndNotCountedIsPassedThrough() {
        Hooked ds = new Hooked();
        ds.rows = List.of(row("a", LocalDate.of(2026, 5, 3), card, "y", "5"), row("b", LocalDate.of(2026, 5, 4), card, "x", "1"),
                row("c", LocalDate.of(2026, 5, 5), card, "x", null));
        define(ds, new KpiDefinition("value", Aggregation.SUM, List.of(), null));

        KpiUnderlyingResponse response = run(ds, null, 0, 1, null);

        assertEquals(1, ((TableData) response.table()).rows().size());
        assertEquals(List.of(new UnderlyingSummaryLine("Listed", new BigDecimal("6"), "currency")), response.summaryLines());
        assertEquals(List.of(Hooked.LEFT_OUT), response.notCounted());
    }

    @Test
    void rowsOpenABreakdownWhenTheDatasourceHasAProvider() {
        Plain ds = new Plain();
        define(ds, new KpiDefinition("value", Aggregation.SUM, List.of(), null));
        RowBreakdownProvider provider = mock(RowBreakdownProvider.class);
        when(provider.datasource()).thenReturn(ds.name());
        service = service(List.of(provider));

        assertEquals("breakdown", run(ds, null, null, null, null).rowAction());
    }

    // ---- CSV ----

    @Test
    void theCsvListsEveryRowInTheListedOrder() {
        Hooked ds = new Hooked();
        ds.rows = List.of(row("a", LocalDate.of(2026, 5, 3), card, "y", "5"), row("b", LocalDate.of(2026, 5, 4), card, "x", "1"));
        define(ds, new KpiDefinition("value", Aggregation.SUM, List.of(), null));
        ByteArrayOutputStream out = new ByteArrayOutputStream();

        service.adHocCsv(ReportType.KPI, ds.name(), null, null, null, () -> out);

        assertEquals("﻿Kind,Card,Value\r\nx,HDFC Regalia,1.00\r\ny,HDFC Regalia,5.00\r\n",
                out.toString(StandardCharsets.UTF_8));
    }

    @Test
    void moreThanTheExportCapIsA400BeforeAnythingIsWritten() {
        Plain ds = new Plain();
        List<Map<String, Object>> rows = new ArrayList<>();
        for (int i = 0; i <= KpiUnderlyingService.CSV_MAX_ROWS; i++) {
            rows.add(row("r" + i, LocalDate.of(2026, 5, 3), card, "x", "1"));
        }
        ds.rows = rows;
        define(ds, new KpiDefinition("value", Aggregation.SUM, List.of(), null));
        boolean[] opened = {false};

        ValidationException e = assertThrows(ValidationException.class, () -> service.adHocCsv(ReportType.KPI, ds.name(),
                null, null, null, () -> {
                    opened[0] = true;
                    return new ByteArrayOutputStream();
                }));

        assertEquals("Too many rows to export (100001). Narrow the report's filters.", e.getMessage());
        assertFalse(opened[0]);
    }

    // ---- datasources ----

    /** A computed datasource without underlying hooks. */
    static class Plain implements ComputedReportDatasource {
        List<Map<String, Object>> rows = List.of();

        @Override
        public String name() {
            return "plain";
        }

        @Override
        public String label() {
            return "Plain";
        }

        @Override
        public List<FieldDef> fields() {
            return List.of(
                    new FieldDef("id", "Id", FieldType.STRING, FieldRole.DIMENSION, null, null, null, TABLE).notFilterable(),
                    new FieldDef("hidden", "Hidden", FieldType.ENUM, FieldRole.DIMENSION, null, null, null, List.of(ReportType.CHART)),
                    new FieldDef("flag", "Flag", FieldType.BOOLEAN, FieldRole.FILTER, null, null, null, List.of()),
                    new FieldDef("value", "Value", FieldType.NUMBER, FieldRole.MEASURE, List.of(Aggregation.values()), null, null, ALL, "currency"),
                    new FieldDef("date", "Date", FieldType.DATE, FieldRole.DIMENSION, null, null, null, ALL, null, null, true),
                    new FieldDef("card", "Card", FieldType.ENUM, FieldRole.DIMENSION, null, null, true, ALL, null, "cardId"),
                    new FieldDef("settled", "Settled", FieldType.DATE, FieldRole.DIMENSION, null, null, null, ALL),
                    new FieldDef("kind", "Kind", FieldType.ENUM, FieldRole.DIMENSION, null, List.of("x", "y"), null, ALL),
                    new FieldDef("note", "Note", FieldType.STRING, FieldRole.DIMENSION, null, null, null, TABLE),
                    new FieldDef("extra", "Extra", FieldType.STRING, FieldRole.DIMENSION, null, null, null, TABLE));
        }

        @Override
        public String billingCycleAccountField() {
            return "card";
        }

        @Override
        public List<Map<String, Object>> rows() {
            return rows;
        }
    }

    /** A computed datasource with every underlying hook and extras. */
    static class Hooked extends Plain implements UnderlyingExtras {
        static final UnderlyingExcludedItem LEFT_OUT =
                new UnderlyingExcludedItem("z", "Closed card", "card", "closed", "Closed on 01/10/2026", null);

        @Override
        public String name() {
            return "hooked";
        }

        @Override
        public List<String> underlyingColumns() {
            return List.of("kind", "value", "card");
        }

        @Override
        public List<SortClause> underlyingDefaultSort() {
            return List.of(new SortClause("kind", SortDirection.ASC), new SortClause("date", SortDirection.DESC));
        }

        @Override
        public String underlyingGroupField() {
            return "kind";
        }

        @Override
        public List<UnderlyingSummaryLine> summaryLines(List<Map<String, Object>> listedRows) {
            BigDecimal sum = listedRows.stream().map(r -> (BigDecimal) r.get("value")).reduce(BigDecimal.ZERO, BigDecimal::add);
            return List.of(new UnderlyingSummaryLine("Listed", sum, "currency"));
        }

        @Override
        public List<UnderlyingExcludedItem> notCounted() {
            return List.of(LEFT_OUT);
        }
    }
}
