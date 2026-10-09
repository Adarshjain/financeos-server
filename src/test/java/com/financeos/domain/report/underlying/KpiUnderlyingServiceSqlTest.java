package com.financeos.domain.report.underlying;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
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
import com.financeos.domain.report.datasource.DatasourceRegistry;
import com.financeos.domain.report.datasource.impl.DividendsDatasource;
import com.financeos.domain.report.datasource.impl.TransactionsDatasource;
import com.financeos.domain.report.definition.ChartDefinition;
import com.financeos.domain.report.definition.ChartType;
import com.financeos.domain.report.definition.Comparison;
import com.financeos.domain.report.definition.DimensionRef;
import com.financeos.domain.report.definition.FilterClause;
import com.financeos.domain.report.definition.KpiDefinition;
import com.financeos.domain.report.definition.MeasureRef;
import com.financeos.domain.report.definition.RawTableDefinition;
import com.financeos.domain.report.definition.TableMode;
import com.financeos.domain.report.engine.DateRangeResolver;
import com.financeos.domain.report.engine.InMemoryReportExecutor;
import com.financeos.domain.report.engine.KpiData;
import com.financeos.domain.report.engine.KpiPeriodResolver;
import com.financeos.domain.report.engine.KpiReportExecutor;
import com.financeos.domain.report.engine.SqlPredicates;
import com.financeos.domain.report.engine.TableData;
import com.financeos.domain.report.engine.TableReportExecutor;
import com.financeos.domain.report.engine.TransactionQueryBuilder;
import jakarta.persistence.EntityManager;
import jakarta.persistence.Query;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.test.util.ReflectionTestUtils;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.sql.Date;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * KPI underlying data over a SQL datasource ({@code transactions}): the value comes from the KPI
 * executor's own query and equals the KPI's figure for each period, and the rows are the period's
 * rows that have the measure (MIN/MAX: only the winners), paged, sorted and exported as CSV.
 */
class KpiUnderlyingServiceSqlTest {

    private static final LocalDate MAY_1 = LocalDate.of(2026, 5, 1);
    private static final LocalDate PREV_FROM = LocalDate.of(2026, 3, 31);
    private static final LocalDate CYCLE_START = LocalDate.of(2026, 9, 5);
    private static final LocalDate PREV_CYCLE_START = LocalDate.of(2026, 8, 5);

    private final UUID userId = UUID.randomUUID();
    private final UUID card = UUID.randomUUID();
    private BillingCycleService cycles;
    private TransactionsDatasource ds;
    private KpiReportExecutor kpiExecutor;
    private ReportDataService reportData;
    private KpiUnderlyingService service;

    /** Every native query run, with its bound parameters. */
    private final List<Captured> queries = new ArrayList<>();
    private Function<Captured, BigDecimal> aggregate = q -> null;
    private long total;
    private Function<Captured, List<Object[]>> rawRows = q -> List.of();

    private record Captured(String sql, Map<String, Object> params) {
        boolean binds(Object value) {
            return params.containsValue(value);
        }
    }

    @BeforeEach
    void setUp() {
        UserContext.setCurrentUserId(userId);
        cycles = mock(BillingCycleService.class);
        DateRangeResolver resolver = new DateRangeResolver(4);
        ds = new TransactionsDatasource(new SqlPredicates(resolver), resolver, cycles);
        kpiExecutor = new KpiReportExecutor(resolver, cycles);
        ReflectionTestUtils.setField(kpiExecutor, "em", fakeEntityManager());
        reportData = mock(ReportDataService.class);
        service = service(new RowBreakdownService(mock(DatasourceRegistry.class), List.of()));
    }

    @AfterEach
    void tearDown() {
        UserContext.clear();
    }

    /** The service over the real executors, sharing the fake entity manager. */
    private KpiUnderlyingService service(RowBreakdownService rowBreakdowns) {
        DateRangeResolver resolver = new DateRangeResolver(4);
        TableReportExecutor tableExecutor = new TableReportExecutor(resolver);
        ReflectionTestUtils.setField(tableExecutor, "em", ReflectionTestUtils.getField(kpiExecutor, "em"));
        return new KpiUnderlyingService(reportData, new ReportDefinitionValidator(mock(DatasourceRegistry.class)),
                new KpiPeriodResolver(resolver, cycles), kpiExecutor, tableExecutor,
                new InMemoryReportExecutor(resolver), new UnderlyingFilterChips(mock(ReportFieldValuesService.class)),
                rowBreakdowns);
    }

    private EntityManager fakeEntityManager() {
        EntityManager em = mock(EntityManager.class);
        when(em.createNativeQuery(anyString())).thenAnswer(inv -> {
            Captured captured = new Captured(inv.getArgument(0), new HashMap<>());
            queries.add(captured);
            Query q = mock(Query.class);
            when(q.setParameter(anyString(), any())).thenAnswer(set -> {
                captured.params().put(set.getArgument(0), set.getArgument(1));
                return q;
            });
            when(q.getSingleResult()).thenAnswer(r -> captured.sql().contains("AS agg_value")
                    ? new Object[]{aggregate.apply(captured), 1L}
                    : (Object) total);
            when(q.getResultList()).thenAnswer(r -> rawRows.apply(captured));
            return q;
        });
        return em;
    }

    private List<Captured> rawQueries() {
        return queries.stream().filter(q -> q.sql().startsWith("SELECT t.id AS row_id")).toList();
    }

    private static FilterClause may() {
        ObjectNode value = JsonNodeFactory.instance.objectNode().put("from", "2026-05-01").put("to", "2026-05-31");
        return new FilterClause("date", "between", value);
    }

    private static FilterClause categoryIsFood() {
        return new FilterClause("category", "is", JsonNodeFactory.instance.textNode("Food"));
    }

    private KpiDefinition define(KpiDefinition def) {
        when(reportData.resolveDefinition(ReportType.KPI, "transactions", null)).thenReturn(new ResolvedDefinition(ds, def));
        return def;
    }

    private KpiUnderlyingResponse run(String period, Integer page, Integer size, String sort) {
        return service.adHoc(ReportType.KPI, "transactions", null, period, page, size, sort);
    }

    private static BigDecimal decimal(String raw) {
        return raw == null ? null : new BigDecimal(raw);
    }

    private static String aggregateOf(Captured q) {
        return q.sql().substring("SELECT ".length(), q.sql().indexOf('('));
    }

    // ---- the value equals the KPI's ----

    @ParameterizedTest
    @CsvSource({"SUM, 300.25, 120", "SUM, 300.25,", "AVG, 150.125,", "AVG, 150.125, 99.5", "COUNT, 2,",
            "MIN, -40, 5", "MIN, -40,", "MAX, 280,", "MAX, 280, 310"})
    void theValueOfEachPeriodIsTheKpisValueForThatPeriod(Aggregation aggregation, String current, String previous) {
        KpiDefinition def = define(new KpiDefinition("amount", aggregation, List.of(may()), null));
        aggregate = q -> {
            assertEquals(aggregation.name(), aggregateOf(q));
            return q.binds(MAY_1) ? decimal(current) : q.binds(PREV_FROM) ? decimal(previous) : null;
        };

        KpiData kpi = kpiExecutor.execute(def, ds, userId);
        KpiUnderlyingResponse currentPeriod = run("current", null, null, null);
        KpiUnderlyingResponse previousPeriod = run("previous", null, null, null);

        assertEquals(new BigDecimal(current), kpi.value());
        assertEquals(kpi.value(), currentPeriod.value());
        assertEquals(kpi.comparison().previousValue(), previousPeriod.value());
    }

    @ParameterizedTest
    @CsvSource({"SUM, 900, 450", "AVG, 300.5,", "COUNT, 3, 1", "MIN, 12, 7", "MAX, 640,"})
    void billingCyclePeriodsReadTheSameValuesAsTheKpi(Aggregation aggregation, String current, String previous) {
        stubCycle(0, CYCLE_START, LocalDate.of(2026, 10, 4));
        stubCycle(1, PREV_CYCLE_START, LocalDate.of(2026, 9, 4));
        KpiDefinition def = define(new KpiDefinition("amount", aggregation, List.of(
                new FilterClause("account", "is", JsonNodeFactory.instance.textNode("HDFC Regalia")),
                new FilterClause("date", "this_billing_cycle", null)), null));
        aggregate = q -> q.binds(CYCLE_START) ? decimal(current) : q.binds(PREV_CYCLE_START) ? decimal(previous) : null;

        KpiData kpi = kpiExecutor.execute(def, ds, userId);
        KpiUnderlyingResponse currentPeriod = run("current", null, null, null);
        KpiUnderlyingResponse previousPeriod = run(" Previous ", null, null, null);

        assertEquals(kpi.value(), currentPeriod.value());
        assertEquals(kpi.comparison().previousValue(), previousPeriod.value());
        assertEquals(new UnderlyingRange(CYCLE_START, LocalDate.of(2026, 10, 4)), currentPeriod.range());
        assertEquals(new UnderlyingRange(PREV_CYCLE_START, LocalDate.of(2026, 9, 4)), previousPeriod.range());
        assertEquals("previous", previousPeriod.period());
        assertEquals("Previous billing cycle", previousPeriod.filters().get(1).text());
        assertTrue(rawQueries().get(1).binds(PREV_CYCLE_START), "the previous rows are read from the previous cycle");
    }

    private void stubCycle(int ago, LocalDate start, LocalDate end) {
        CycleWindows windows = new CycleWindows(Map.of(card, new Cycle(start, end, Source.STATEMENT)));
        when(cycles.windows(eq(userId), eq(ago), any(LocalDate.class), eq("HDFC Regalia"))).thenReturn(windows);
        when(cycles.windows(eq(userId), eq(ago), any(LocalDate.class))).thenReturn(windows);
    }

    // ---- the listed rows ----

    @Test
    void theRowsAreThePeriodsRowsThatHaveTheMeasure() {
        define(new KpiDefinition("amount", Aggregation.SUM, List.of(may()), null));
        aggregate = q -> new BigDecimal("10");

        run("current", null, null, null);
        run("previous", null, null, null);

        Captured current = rawQueries().get(0);
        assertTrue(current.sql().contains(" WHERE t.user_id = :userId AND t.transaction_date BETWEEN :f0a AND :f0b AND "
                + TransactionQueryBuilder.SIGNED_AMOUNT + " IS NOT NULL ORDER BY"), current.sql());
        assertEquals(MAY_1, current.params().get("f0a"));
        assertFalse(current.sql().contains(" = :f"), "SUM lists every row with the measure");
        assertEquals(PREV_FROM, rawQueries().get(1).params().get("f0a"));
    }

    @Test
    void maxListsOnlyTheRowsEqualToTheValue() {
        define(new KpiDefinition("spend", Aggregation.MAX, List.of(may(), categoryIsFood()), null));
        aggregate = q -> new BigDecimal("280.50");

        KpiUnderlyingResponse response = run("current", null, null, null);

        Captured raw = rawQueries().get(0);
        assertTrue(raw.sql().contains(TransactionQueryBuilder.SPEND_AMOUNT + " IS NOT NULL AND "
                + TransactionQueryBuilder.SPEND_AMOUNT + " = :f3"), raw.sql());
        assertEquals(0, new BigDecimal("280.50").compareTo((BigDecimal) raw.params().get("f3")));
        assertTrue(response.winnerOnly());
    }

    @Test
    void minWithoutAValueListsNothingButTheMeasuredRows() {
        define(new KpiDefinition("amount", Aggregation.MIN, List.of(may()), null));

        KpiUnderlyingResponse response = run("current", null, null, null);

        assertNull(response.value());
        Captured raw = rawQueries().get(0);
        assertTrue(raw.sql().contains(TransactionQueryBuilder.SIGNED_AMOUNT + " IS NOT NULL ORDER BY"), raw.sql());
        assertFalse(raw.sql().contains(" = :f"));
    }

    @Test
    void listsTheIdentifyingColumnsThenTheMeasureNewestFirst() {
        define(new KpiDefinition("spend", Aggregation.SUM, List.of(may()), null));
        total = 1;
        rawRows = q -> List.<Object[]>of(new Object[]{"t1", Date.valueOf("2026-05-04"), "Swiggy", "HDFC", "Food, Fuel",
                new BigDecimal("450.00")});

        KpiUnderlyingResponse response = run(null, null, null, null);

        TableData table = (TableData) response.table();
        assertEquals(List.of("date", "description", "account", "category", "spend"),
                table.columns().stream().map(TableData.Column::key).toList());
        assertEquals(Map.of("id", "t1", "date", LocalDate.of(2026, 5, 4), "description", "Swiggy", "account", "HDFC",
                "category", "Food, Fuel", "spend", new BigDecimal("450.00")), table.rows().get(0));
        assertTrue(rawQueries().get(0).sql().endsWith(
                " ORDER BY t.transaction_date DESC, t.id DESC OFFSET 0 ROWS FETCH NEXT 25 ROWS ONLY"));
        assertNull(response.sortKey());
        assertNull(response.sortDirection());
    }

    @Test
    void aRuntimeSortOrdersByAListedColumnAndIsEchoed() {
        define(new KpiDefinition("amount", Aggregation.SUM, List.of(may()), null));

        KpiUnderlyingResponse response = run("current", null, null, "amount,DESC");

        assertTrue(rawQueries().get(0).sql().contains(" ORDER BY " + TransactionQueryBuilder.SIGNED_AMOUNT
                + " DESC, t.id ASC OFFSET"), rawQueries().get(0).sql());
        assertEquals("amount", response.sortKey());
        assertEquals("desc", response.sortDirection());
    }

    @Test
    void aRuntimeSortOnAFieldThatIsNotListedIsRejectedBeforeAnyQuery() {
        define(new KpiDefinition("amount", Aggregation.SUM, List.of(may()), null));

        ValidationException e = assertThrows(ValidationException.class, () -> run("current", null, null, "type,asc"));

        assertEquals("Sort key is not an available column: type", e.getMessage());
        assertTrue(queries.isEmpty());
    }

    @Test
    void malformedParametersAreRejectedBeforeTheReportIsLoaded() {
        assertThrows(ValidationException.class, () -> run("current", null, null, "amount"));
        ValidationException period = assertThrows(ValidationException.class, () -> run("last", null, null, null));

        assertEquals("Invalid period 'last': expected 'current' or 'previous'", period.getMessage());
        verifyNoInteractions(reportData);
    }

    @Test
    void thePreviousPeriodOfAKpiWithoutOneIsA400() {
        define(new KpiDefinition("amount", Aggregation.SUM, List.of(may()), new Comparison(false, null, null)));

        ValidationException e = assertThrows(ValidationException.class, () -> run("previous", null, null, null));

        assertEquals("This KPI has no previous period", e.getMessage());
    }

    @Test
    void aNonKpiDefinitionIsA400() {
        when(reportData.resolveDefinition(ReportType.TABLE, "transactions", null)).thenReturn(new ResolvedDefinition(ds,
                new RawTableDefinition(TableMode.RAW, List.of("date"), List.of(), List.of())));

        ValidationException e = assertThrows(ValidationException.class,
                () -> service.adHoc(ReportType.TABLE, "transactions", null, null, null, null, null));

        assertEquals("Underlying data is only available for KPI reports", e.getMessage());
    }

    @Test
    void pagesAreZeroBasedWithADefaultOf25AndACapOf1000() {
        define(new KpiDefinition("amount", Aggregation.SUM, List.of(may()), null));

        run(null, 2, 10, null);
        run(null, -1, 5000, null);
        run(null, null, 0, null);

        List<Captured> raw = rawQueries();
        assertTrue(raw.get(0).sql().endsWith(" OFFSET 20 ROWS FETCH NEXT 10 ROWS ONLY"));
        assertTrue(raw.get(1).sql().endsWith(" OFFSET 0 ROWS FETCH NEXT 1000 ROWS ONLY"));
        assertTrue(raw.get(2).sql().endsWith(" OFFSET 0 ROWS FETCH NEXT 1 ROWS ONLY"));
    }

    @Test
    void describesThePeriodTheMeasureAndTheListing() {
        define(new KpiDefinition("amount", Aggregation.SUM, List.of(categoryIsFood(), may()), null));
        aggregate = q -> new BigDecimal("75");
        total = 42;

        KpiUnderlyingResponse response = run("current", null, null, null);

        assertEquals("current", response.period());
        assertEquals("transactions", response.datasource());
        assertEquals(new UnderlyingRange(MAY_1, LocalDate.of(2026, 5, 31)), response.range());
        assertTrue(response.previousAvailable());
        assertEquals(new UnderlyingRange(PREV_FROM, LocalDate.of(2026, 4, 30)), response.previousRange());
        assertEquals("amount", response.measure());
        assertEquals("Amount", response.measureLabel());
        assertEquals("sum", response.aggregation());
        assertEquals("currency", response.format());
        assertEquals(new BigDecimal("75"), response.value());
        assertEquals(42, response.rowCount());
        assertFalse(response.winnerOnly());
        assertEquals(List.of(), response.summaryLines());
        assertEquals(List.of(), response.notCounted());
        assertEquals("transaction", response.rowAction());
        assertNull(response.groupField());
        assertEquals(List.of(
                new UnderlyingFilterChip("category", "Category", "is", "is Food"),
                new UnderlyingFilterChip("date", "Date", "between", "Between 01/05/2026 and 31/05/2026")),
                response.filters());
    }

    @Test
    void thePreviousPeriodsChipsShowItsWindowInTheDateFiltersPlace() {
        define(new KpiDefinition("amount", Aggregation.SUM, List.of(may(), categoryIsFood()), null));

        KpiUnderlyingResponse response = run("previous", null, null, null);

        assertEquals(List.of("Between 31/03/2026 and 30/04/2026", "is Food"),
                response.filters().stream().map(UnderlyingFilterChip::text).toList());
    }

    @Test
    void anUnboundedKpiHasNoRangeAndNoPreviousPeriod() {
        define(new KpiDefinition("amount", Aggregation.SUM, List.of(categoryIsFood()), null));

        KpiUnderlyingResponse response = run("current", null, null, null);

        assertNull(response.range());
        assertFalse(response.previousAvailable());
        assertNull(response.previousRange());
    }

    @Test
    void savedReportsAreLoadedWithOwnership() {
        UUID reportId = UUID.randomUUID();
        when(reportData.resolveSaved(reportId)).thenReturn(new ResolvedDefinition(ds,
                new KpiDefinition("amount", Aggregation.COUNT, List.of(may()), null)));
        aggregate = q -> new BigDecimal("7");

        KpiUnderlyingResponse response = service.saved(reportId, null, null, null, null);

        assertEquals(new BigDecimal("7"), response.value());
        assertEquals("count", response.aggregation());
    }

    @Test
    void rowsOfOtherSqlDatasourcesOpenABreakdownOnlyWhenOneIsRegistered() {
        DateRangeResolver resolver = new DateRangeResolver(4);
        DividendsDatasource dividends = new DividendsDatasource(new SqlPredicates(resolver), resolver);
        when(reportData.resolveDefinition(ReportType.KPI, "dividends", null)).thenReturn(new ResolvedDefinition(dividends,
                new KpiDefinition("amount", Aggregation.SUM, List.of(), null)));
        RowBreakdownProvider provider = mock(RowBreakdownProvider.class);
        when(provider.datasource()).thenReturn("dividends");

        KpiUnderlyingResponse without = service.adHoc(ReportType.KPI, "dividends", null, null, null, null, null);
        KpiUnderlyingResponse with = service(new RowBreakdownService(mock(DatasourceRegistry.class), List.of(provider)))
                .adHoc(ReportType.KPI, "dividends", null, null, null, null, null);

        assertNull(without.rowAction());
        assertEquals("breakdown", with.rowAction());
        assertEquals(List.of("payDate", "instrument", "type", "broker", "amount"),
                ((TableData) with.table()).columns().stream().map(TableData.Column::key).toList());
    }

    // ---- CSV ----

    private static final class Target implements KpiUnderlyingService.CsvTarget {
        final ByteArrayOutputStream out = new ByteArrayOutputStream();
        boolean opened;

        @Override
        public ByteArrayOutputStream open() {
            opened = true;
            return out;
        }

        String text() {
            return out.toString(StandardCharsets.UTF_8);
        }
    }

    @Test
    void theCsvStreamsEveryRowAThousandAtATimeInTheListedOrder() {
        define(new KpiDefinition("amount", Aggregation.SUM, List.of(may()), null));
        total = 2500;
        rawRows = q -> List.<Object[]>of(new Object[]{"t", Date.valueOf("2026-05-04"), "Rent, May", "HDFC", null,
                new BigDecimal("-20000")});
        Target target = new Target();

        service.adHocCsv(ReportType.KPI, "transactions", null, null, "date,asc", target);

        List<Captured> raw = rawQueries();
        assertEquals(3, raw.size());
        assertTrue(raw.get(0).sql().endsWith(" ORDER BY t.transaction_date ASC, t.id ASC OFFSET 0 ROWS FETCH NEXT 1000 ROWS ONLY"));
        assertTrue(raw.get(1).sql().endsWith(" OFFSET 1000 ROWS FETCH NEXT 1000 ROWS ONLY"));
        assertTrue(raw.get(2).sql().endsWith(" OFFSET 2000 ROWS FETCH NEXT 1000 ROWS ONLY"));
        String row = "04/05/2026,\"Rent, May\",HDFC,,-20000.00\r\n";
        assertEquals("﻿Date,Description,Account,Category,Amount\r\n" + row + row + row, target.text());
    }

    @Test
    void anEmptyCsvIsJustTheHeader() {
        define(new KpiDefinition("amount", Aggregation.SUM, List.of(may()), null));
        Target target = new Target();

        service.adHocCsv(ReportType.KPI, "transactions", null, null, null, target);

        assertEquals("﻿Date,Description,Account,Category,Amount\r\n", target.text());
        assertEquals(1, rawQueries().size());
    }

    @Test
    void moreThanTheExportCapIsA400BeforeAnythingIsWritten() {
        define(new KpiDefinition("amount", Aggregation.SUM, List.of(may()), null));
        total = 100_001;
        Target target = new Target();

        ValidationException e = assertThrows(ValidationException.class,
                () -> service.adHocCsv(ReportType.KPI, "transactions", null, null, null, target));

        assertEquals("Too many rows to export (100001). Narrow the report's filters.", e.getMessage());
        assertFalse(target.opened);
        assertEquals(1, rawQueries().size());
    }

    @Test
    void exactlyTheExportCapIsWritten() {
        define(new KpiDefinition("amount", Aggregation.SUM, List.of(may()), null));
        total = 100_000;
        Target target = new Target();

        service.adHocCsv(ReportType.KPI, "transactions", null, null, null, target);

        assertTrue(target.opened);
        assertEquals(100, rawQueries().size());
    }

    @Test
    void theSavedCsvReadsThePreviousPeriod() {
        UUID reportId = UUID.randomUUID();
        when(reportData.resolveSaved(reportId)).thenReturn(new ResolvedDefinition(ds,
                new KpiDefinition("amount", Aggregation.SUM, List.of(may()), null)));

        service.savedCsv(reportId, "previous", null, new Target());

        assertEquals(PREV_FROM, rawQueries().get(0).params().get("f0a"));
    }

    @Test
    void aFailingTargetSurfacesAsAnUncheckedIoError() {
        define(new KpiDefinition("amount", Aggregation.SUM, List.of(may()), null));

        assertThrows(UncheckedIOException.class, () -> service.adHocCsv(ReportType.KPI, "transactions", null, null, null,
                () -> {
                    throw new IOException("client went away");
                }));
    }

    @Test
    void theCsvRejectsANonKpiDefinition() {
        when(reportData.resolveDefinition(ReportType.CHART, "transactions", null)).thenReturn(new ResolvedDefinition(ds,
                new ChartDefinition(ChartType.BAR, new DimensionRef("category", null), null,
                        new MeasureRef("amount", Aggregation.SUM), List.of())));
        Target target = new Target();

        assertThrows(ValidationException.class,
                () -> service.adHocCsv(ReportType.CHART, "transactions", null, null, null, target));
        assertFalse(target.opened);
    }
}
