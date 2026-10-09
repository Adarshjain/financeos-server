package com.financeos.domain.report.underlying;

import com.fasterxml.jackson.databind.JsonNode;
import com.financeos.core.exception.ValidationException;
import com.financeos.core.security.UserContext;
import com.financeos.domain.report.ReportDataService;
import com.financeos.domain.report.ReportDataService.ResolvedDefinition;
import com.financeos.domain.report.ReportDefinitionValidator;
import com.financeos.domain.report.ReportType;
import com.financeos.domain.report.breakdown.RowBreakdownService;
import com.financeos.domain.report.datasource.ComputedReportDatasource;
import com.financeos.domain.report.datasource.DatasourceCatalog.FieldDef;
import com.financeos.domain.report.datasource.FieldRole;
import com.financeos.domain.report.datasource.FieldType;
import com.financeos.domain.report.datasource.ReportDatasource;
import com.financeos.domain.report.definition.FilterClause;
import com.financeos.domain.report.definition.KpiDefinition;
import com.financeos.domain.report.definition.RawTableDefinition;
import com.financeos.domain.report.definition.SortClause;
import com.financeos.domain.report.definition.TableMode;
import com.financeos.domain.report.engine.DateRange;
import com.financeos.domain.report.engine.InMemoryReportExecutor;
import com.financeos.domain.report.engine.KpiPeriodResolver;
import com.financeos.domain.report.engine.KpiPeriods;
import com.financeos.domain.report.engine.KpiReportExecutor;
import com.financeos.domain.report.engine.RuntimeSort;
import com.financeos.domain.report.engine.TableData;
import com.financeos.domain.report.engine.TableReportExecutor;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * A KPI's underlying data ("View underlying data"): the rows one period of the KPI is computed
 * from, as a sortable, paginated raw table or a CSV of every row.
 *
 * <p>The period's filters come from {@link KpiPeriodResolver} and its value from the KPI executor
 * itself ({@link KpiReportExecutor#value} / {@link InMemoryReportExecutor#kpiRows}), so the value
 * shown always equals the KPI's own figure. The rows are the period's rows that have the measure
 * (aggregations skip the rest); for MIN/MAX only the row(s) whose measure equals the value.
 */
@Service
public class KpiUnderlyingService {

    static final int DEFAULT_PAGE_SIZE = 25;
    static final int MAX_PAGE_SIZE = 1000;
    /** Rows read per query while exporting a SQL datasource. */
    static final int CSV_CHUNK = 1000;
    static final int CSV_MAX_ROWS = 100_000;
    /** Fallback columns: the first date field plus up to this many table dimensions. */
    private static final int FALLBACK_DIMENSIONS = 3;
    private static final String TRANSACTIONS = "transactions";

    private final ReportDataService reportDataService;
    private final ReportDefinitionValidator validator;
    private final KpiPeriodResolver periodResolver;
    private final KpiReportExecutor kpiExecutor;
    private final TableReportExecutor tableExecutor;
    private final InMemoryReportExecutor inMemoryExecutor;
    private final UnderlyingFilterChips filterChips;
    private final RowBreakdownService breakdowns;

    public KpiUnderlyingService(ReportDataService reportDataService, ReportDefinitionValidator validator,
            KpiPeriodResolver periodResolver, KpiReportExecutor kpiExecutor, TableReportExecutor tableExecutor,
            InMemoryReportExecutor inMemoryExecutor, UnderlyingFilterChips filterChips, RowBreakdownService breakdowns) {
        this.reportDataService = reportDataService;
        this.validator = validator;
        this.periodResolver = periodResolver;
        this.kpiExecutor = kpiExecutor;
        this.tableExecutor = tableExecutor;
        this.inMemoryExecutor = inMemoryExecutor;
        this.filterChips = filterChips;
        this.breakdowns = breakdowns;
    }

    /** Where a CSV export is written; opened only once the export is known to be valid. */
    @FunctionalInterface
    public interface CsvTarget {
        OutputStream open() throws IOException;
    }

    /**
     * One page of a saved KPI report's underlying data (ownership as for running it).
     *
     * @param period {@code current} (default) or {@code previous}
     * @param sort   {@code <column>,<asc|desc>} over the listed columns; null for the default order
     */
    @Transactional(readOnly = true)
    public KpiUnderlyingResponse saved(UUID reportId, String period, Integer page, Integer size, String sort) {
        Request request = request(period, sort);
        return respond(kpi(reportDataService.resolveSaved(reportId)), request, page, size);
    }

    /** One page of an unsaved KPI definition's underlying data (ad-hoc reports, built-in widgets). */
    @Transactional(readOnly = true)
    public KpiUnderlyingResponse adHoc(ReportType type, String datasource, JsonNode definition,
            String period, Integer page, Integer size, String sort) {
        Request request = request(period, sort);
        return respond(kpi(reportDataService.resolveDefinition(type, datasource, definition)), request, page, size);
    }

    /** Every underlying row of a saved KPI report as CSV (same rows, columns and order as the pages). */
    @Transactional(readOnly = true)
    public void savedCsv(UUID reportId, String period, String sort, CsvTarget target) {
        Request request = request(period, sort);
        export(kpi(reportDataService.resolveSaved(reportId)), request, target);
    }

    /** Every underlying row of an unsaved KPI definition as CSV. */
    @Transactional(readOnly = true)
    public void adHocCsv(ReportType type, String datasource, JsonNode definition, String period, String sort,
            CsvTarget target) {
        Request request = request(period, sort);
        export(kpi(reportDataService.resolveDefinition(type, datasource, definition)), request, target);
    }

    // ------------------------------------------------------------------ assembly

    /** The parsed query parameters, checked before anything is loaded. */
    private record Request(KpiPeriods.Kind period, @Nullable SortClause sort) {
    }

    private static Request request(String period, String sort) {
        return new Request(KpiPeriods.Kind.from(period), RuntimeSort.parse(sort).orElse(null));
    }

    private record Kpi(ReportDatasource datasource, KpiDefinition definition) {
    }

    private static Kpi kpi(ResolvedDefinition resolved) {
        if (!(resolved.definition() instanceof KpiDefinition kpi)) {
            throw new ValidationException("Underlying data is only available for KPI reports");
        }
        return new Kpi(resolved.datasource(), kpi);
    }

    private KpiUnderlyingResponse respond(Kpi kpi, Request request, Integer page, Integer size) {
        ReportDatasource ds = kpi.datasource();
        KpiDefinition def = kpi.definition();
        UUID userId = UserContext.getCurrentUserId();
        KpiPeriods periods = periodResolver.resolve(def, ds, userId);
        KpiPeriods.Period period = periods.select(request.period());
        List<String> columns = columns(ds, def.measure());
        Listing listing = listing(def, ds, period, columns, sort(ds, columns, request.sort()), userId, true);

        int pageNumber = page == null ? 0 : Math.max(0, page);
        int pageSize = Math.max(1, Math.min(size == null ? DEFAULT_PAGE_SIZE : size, MAX_PAGE_SIZE));
        TableData table = listing.pager().page(pageNumber, pageSize);

        FieldDef measure = ds.field(def.measure());
        SortClause sort = request.sort();
        return new KpiUnderlyingResponse(
                request.period().json(),
                ds.name(),
                range(period.range()),
                periods.previousAvailable(),
                periods.previousAvailable() ? range(periods.previous().range()) : null,
                def.measure(),
                measure.label(),
                def.aggregation().json(),
                measure.format(),
                listing.value(),
                table.page().totalElements(),
                UnderlyingOperators.winnerOnly(def.aggregation()),
                listing.summaryLines(),
                filterChips.describe(ds, chipFilters(periods, request.period())),
                rowAction(ds),
                ds.underlyingGroupField(),
                ds instanceof UnderlyingExtras extras ? extras.notCounted() : List.of(),
                sort == null ? null : sort.key(),
                sort == null ? null : sort.direction().json(),
                table);
    }

    /**
     * Writes every listed row as CSV, a chunk at a time (one query per chunk for SQL datasources).
     * The row count is checked against {@link #CSV_MAX_ROWS} before anything is written.
     */
    private void export(Kpi kpi, Request request, CsvTarget target) {
        ReportDatasource ds = kpi.datasource();
        KpiDefinition def = kpi.definition();
        UUID userId = UserContext.getCurrentUserId();
        KpiPeriods.Period period = periodResolver.resolve(def, ds, userId).select(request.period());
        List<String> columns = columns(ds, def.measure());
        Listing listing = listing(def, ds, period, columns, sort(ds, columns, request.sort()), userId, false);

        TableData first = listing.pager().page(0, listing.chunkSize());
        long total = first.page().totalElements();
        if (total > CSV_MAX_ROWS) {
            throw new ValidationException("Too many rows to export (" + total + "). Narrow the report's filters.");
        }
        try {
            UnderlyingCsvWriter csv = new UnderlyingCsvWriter(target.open());
            csv.header(first.columns());
            csv.rows(first);
            for (int p = 1; p < first.page().totalPages(); p++) {
                csv.rows(listing.pager().page(p, listing.chunkSize()));
            }
        } catch (IOException e) {
            throw new UncheckedIOException("Could not write the underlying data CSV", e);
        }
    }

    /** Reads pages of the listed rows. */
    @FunctionalInterface
    private interface Pager {
        TableData page(int page, int size);
    }

    /**
     * The listed rows of one KPI period.
     *
     * @param value        the KPI's figure for the period
     * @param summaryLines datasource totals over all listed rows
     * @param chunkSize    rows per page when exporting everything
     */
    private record Listing(BigDecimal value, List<UnderlyingSummaryLine> summaryLines, int chunkSize, Pager pager) {
    }

    private Listing listing(KpiDefinition def, ReportDatasource ds, KpiPeriods.Period period, List<String> columns,
            List<SortClause> sort, UUID userId, boolean withSummary) {
        if (ds instanceof ComputedReportDatasource) {
            // Computed rows are in memory already: one sort, and the whole list is one export chunk.
            InMemoryReportExecutor.KpiRows rows = inMemoryExecutor.kpiRows(def, ds, period.filters());
            List<UnderlyingSummaryLine> summary = withSummary && ds instanceof UnderlyingExtras extras
                    ? extras.summaryLines(rows.rows()) : List.of();
            return new Listing(rows.value(), summary, Math.max(1, rows.rows().size()),
                    (page, size) -> inMemoryExecutor.rawTable(rows.rows(), columns, sort, ds, page, size));
        }
        BigDecimal value = kpiExecutor.value(def, ds, period.filters(), userId);
        List<FilterClause> filters = new ArrayList<>(period.filters());
        filters.addAll(UnderlyingOperators.listing(def.measure(), def.aggregation(), value));
        RawTableDefinition table = new RawTableDefinition(TableMode.RAW, columns, filters, sort);
        return new Listing(value, List.of(), CSV_CHUNK,
                (page, size) -> (TableData) tableExecutor.execute(table, ds, userId, page, size));
    }

    // ------------------------------------------------------------------ shape

    /**
     * The listed columns: the datasource's identifying columns (or the fallback: its first date
     * field plus up to three table dimensions, never {@code id}), then the measure last.
     */
    static List<String> columns(ReportDatasource ds, String measure) {
        List<String> identifying = ds.underlyingColumns() != null ? ds.underlyingColumns() : fallbackColumns(ds);
        Set<String> columns = new LinkedHashSet<>(identifying);
        columns.remove(measure);
        columns.add(measure);
        return List.copyOf(columns);
    }

    private static List<String> fallbackColumns(ReportDatasource ds) {
        List<String> columns = new ArrayList<>();
        ds.fields().stream()
                .filter(f -> f.type() == FieldType.DATE && listable(f))
                .findFirst()
                .ifPresent(f -> columns.add(f.name()));
        ds.fields().stream()
                .filter(f -> f.type() != FieldType.DATE && f.role() == FieldRole.DIMENSION && listable(f))
                .limit(FALLBACK_DIMENSIONS)
                .forEach(f -> columns.add(f.name()));
        return columns;
    }

    private static boolean listable(FieldDef field) {
        return !"id".equals(field.name()) && field.allowedInReports().contains(ReportType.TABLE);
    }

    /**
     * The row order: the runtime clause (a listed column, else 400), else the datasource's
     * underlying default, else empty for the executor's default (first date field, newest first).
     */
    private List<SortClause> sort(ReportDatasource ds, List<String> columns, @Nullable SortClause runtime) {
        if (runtime != null) {
            validator.validateRuntimeSort(new RawTableDefinition(TableMode.RAW, columns, List.of(), List.of()), runtime);
            return List.of(runtime);
        }
        return ds.underlyingDefaultSort() != null ? ds.underlyingDefaultSort() : List.of();
    }

    /**
     * The filters the chips describe: the period's own, in the definition's order — for the
     * previous period the date filter is shown as the previous window it was swapped for.
     */
    private static List<FilterClause> chipFilters(KpiPeriods periods, KpiPeriods.Kind kind) {
        List<FilterClause> current = periods.current().filters();
        if (kind == KpiPeriods.Kind.CURRENT) {
            return current;
        }
        List<FilterClause> previous = periods.previous().filters();
        FilterClause swapped = previous.stream()
                .filter(f -> current.stream().noneMatch(c -> c == f))
                .findFirst()
                .orElseThrow();
        return current.stream().map(f -> f == periods.dateFilter() ? swapped : f).toList();
    }

    private String rowAction(ReportDatasource ds) {
        if (TRANSACTIONS.equals(ds.name())) {
            return "transaction";
        }
        return breakdowns.supports(ds.name()) ? "breakdown" : null;
    }

    private static UnderlyingRange range(DateRange range) {
        return range.bounded() ? new UnderlyingRange(range.from(), range.to()) : null;
    }
}
