package com.financeos.domain.report.engine;

import com.fasterxml.jackson.databind.JsonNode;
import com.financeos.core.exception.ValidationException;
import com.financeos.core.security.UserContext;
import com.financeos.core.time.AppTime;
import com.financeos.domain.account.cycle.BillingCycleService;
import com.financeos.domain.account.cycle.CycleOperators;
import com.financeos.domain.account.cycle.CycleWindows;
import com.financeos.domain.report.datasource.Aggregation;
import com.financeos.domain.report.datasource.ComputedReportDatasource;
import com.financeos.domain.report.datasource.DatasourceCatalog.FieldDef;
import com.financeos.domain.report.datasource.FieldType;
import com.financeos.domain.report.datasource.ReportDatasource;
import com.financeos.domain.report.definition.AggregatedTableDefinition;
import com.financeos.domain.report.definition.ChartDefinition;
import com.financeos.domain.report.definition.ChartType;
import com.financeos.domain.report.definition.ComparisonDisplay;
import com.financeos.domain.report.definition.DimensionRef;
import com.financeos.domain.report.definition.FilterClause;
import com.financeos.domain.report.definition.Granularity;
import com.financeos.domain.report.definition.KpiDefinition;
import com.financeos.domain.report.definition.MeasureRef;
import com.financeos.domain.report.definition.RawTableDefinition;
import com.financeos.domain.report.definition.SortClause;
import com.financeos.domain.report.definition.SortDirection;
import com.financeos.domain.report.definition.TableDefinition;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

@Component
public class InMemoryReportExecutor {

    private static final int DEFAULT_PAGE_SIZE = 50;
    private static final int MAX_PAGE_SIZE = 1000;

    private final DateRangeResolver dateRangeResolver;
    private final BillingCycleService billingCycleService;

    public InMemoryReportExecutor(DateRangeResolver dateRangeResolver) {
        this(dateRangeResolver, null);
    }

    @Autowired
    public InMemoryReportExecutor(DateRangeResolver dateRangeResolver, BillingCycleService billingCycleService) {
        this.dateRangeResolver = dateRangeResolver;
        this.billingCycleService = billingCycleService;
    }

    // ------------------------------------------------------------------
    // KPI
    // ------------------------------------------------------------------

    public KpiData execute(KpiDefinition def, ReportDatasource datasource, Map<String, Object> unusedParams) {
        ComputedReportDatasource computedDs = (ComputedReportDatasource) datasource;
        FilterClause dateFilter = dateRangeResolver.findDateFilter(datasource, def.filters());
        if (CycleOperators.isCycle(dateFilter)) {
            return cycleKpi(def, computedDs, dateFilter);
        }
        DateRange effRange = dateRangeResolver.effectiveRange(dateFilter);
        boolean compare = def.comparison() != null && Boolean.TRUE.equals(def.comparison().enabled())
                && dateFilter != null && effRange.bounded();
        DateRange prevRange = compare ? dateRangeResolver.previousPeriod(dateFilter.operator(), effRange) : DateRange.unbounded();

        // The comparison reads the previous period from the same rows, so the hint spans both.
        ComputedReportDatasource.DateHint hint = hint(dateFilter, effRange);
        if (hint != null && prevRange.bounded()) {
            hint = new ComputedReportDatasource.DateHint(hint.field(),
                    prevRange.from().isBefore(hint.from()) ? prevRange.from() : hint.from(),
                    prevRange.to().isAfter(hint.to()) ? prevRange.to() : hint.to());
        }
        List<Map<String, Object>> allRows = loadRows(computedDs, hint);
        List<Map<String, Object>> filteredRows = filterRows(allRows, def.filters(), computedDs);

        BigDecimal val = calculateAggregate(filteredRows, def.measure(), def.aggregation());

        KpiData.DateRangeView effRangeView = effRange.bounded()
                ? new KpiData.DateRangeView(effRange.from(), effRange.to())
                : null;
        KpiData.Meta meta = new KpiData.Meta(filteredRows.size(), effRangeView);

        KpiData.Comparison comparison = null;
        if (compare) {
            if (prevRange.bounded()) {
                List<Map<String, Object>> prevRows = filterRowsForDateRange(allRows, dateFilter, prevRange, def.filters(), computedDs);
                BigDecimal prevVal = calculateAggregate(prevRows, def.measure(), def.aggregation());
                comparison = comparison(val, prevVal, new KpiData.DateRangeView(prevRange.from(), prevRange.to()), def);
            }
        }

        FieldDef measureFieldDef = datasource.field(def.measure());
        String format = measureFieldDef != null ? measureFieldDef.format() : null;

        return new KpiData("KPI", val, def.measure(), def.aggregation().json(), format, comparison, meta);
    }

    /**
     * KPI over a billing-cycle date filter (limited to one account): the comparison is the
     * cycle before rather than a flat date shift.
     */
    private KpiData cycleKpi(KpiDefinition def, ComputedReportDatasource ds, FilterClause dateFilter) {
        int cyclesAgo = CycleOperators.cyclesAgo(dateFilter);
        CycleWindows current = cycleWindows(cyclesAgo, ds, def.filters());
        boolean compare = def.comparison() != null && Boolean.TRUE.equals(def.comparison().enabled())
                && !current.byAccount().isEmpty();
        CycleWindows previous = compare ? cycleWindows(cyclesAgo + 1, ds, def.filters()) : null;

        ComputedReportDatasource.DateHint hint = spanHint(dateFilter.field(), current, previous);
        List<Map<String, Object>> allRows = loadRows(ds, hint);
        List<Map<String, Object>> filteredRows = filterRows(allRows, def.filters(), ds);
        BigDecimal val = calculateAggregate(filteredRows, def.measure(), def.aggregation());

        KpiData.Comparison comparison = null;
        if (compare) {
            List<FilterClause> previousFilters = KpiReportExecutor.withCyclesAgo(def.filters(), dateFilter, cyclesAgo + 1);
            BigDecimal prevVal = calculateAggregate(filterRows(allRows, previousFilters, ds), def.measure(), def.aggregation());
            KpiData.DateRangeView prevView = new KpiData.DateRangeView(previous.earliestStart(), previous.latestEnd());
            comparison = comparison(val, prevVal, prevView, def);
        }
        KpiData.DateRangeView view = current.byAccount().isEmpty() ? null
                : new KpiData.DateRangeView(current.earliestStart(), current.latestEnd());
        FieldDef measureFieldDef = ds.field(def.measure());
        return new KpiData("KPI", val, def.measure(), def.aggregation().json(),
                measureFieldDef != null ? measureFieldDef.format() : null, comparison,
                new KpiData.Meta(filteredRows.size(), view));
    }

    private KpiData.Comparison comparison(BigDecimal val, BigDecimal prevVal, KpiData.DateRangeView prevView, KpiDefinition def) {
        if (prevVal == null) {
            return null;
        }
        BigDecimal change = val != null ? val.subtract(prevVal) : null;
        BigDecimal changePct = null;
        if (val != null && prevVal.compareTo(BigDecimal.ZERO) != 0) {
            changePct = change.divide(prevVal.abs(), 4, RoundingMode.HALF_UP).multiply(BigDecimal.valueOf(100));
        }
        String direction = change == null || change.compareTo(BigDecimal.ZERO) == 0 ? "flat"
                : (change.compareTo(BigDecimal.ZERO) > 0 ? "up" : "down");
        String sentiment = "neutral";
        if (def.comparison().higherIsBetter() != null && !"flat".equals(direction)) {
            sentiment = ("up".equals(direction) == def.comparison().higherIsBetter()) ? "good" : "bad";
        }
        return new KpiData.Comparison(prevVal, prevView, change, changePct, direction, sentiment,
                ComparisonDisplay.resolve(def.comparison()).json());
    }

    // ------------------------------------------------------------------
    // CHART
    // ------------------------------------------------------------------

    public ChartData execute(ChartDefinition def, ReportDatasource datasource, Map<String, Object> unusedParams) {
        ComputedReportDatasource computedDs = (ComputedReportDatasource) datasource;
        List<Map<String, Object>> allRows = loadRows(computedDs, def.filters());
        List<Map<String, Object>> filteredRows = filterRows(allRows, def.filters(), computedDs);

        DimensionRef dim = def.dimension();
        // Pie/Donut render one dimension as slices; ignore any series split (as the SQL path does).
        boolean pie = def.chartType() == ChartType.PIE || def.chartType() == ChartType.DONUT;
        DimensionRef seriesDim = pie ? null : def.series();
        MeasureRef measure = def.measure();
        FieldDef dimFieldDef = datasource.field(dim.field());

        Map<DimensionKey, List<Map<String, Object>>> groups = new LinkedHashMap<>();
        Set<Object> rawSeriesKeys = new LinkedHashSet<>();

        // Null dimension values group as "(none)", mirroring the SQL chart and the pivot;
        // a multi-valued field counts the row under each of its values.
        for (Map<String, Object> row : filteredRows) {
            for (Object dimRaw : groupValues(row.get(dim.field()))) {
                Object dimGroupVal = processDimensionValue(dimRaw, dimFieldDef, dim.granularity());
                List<Object> seriesVals = seriesDim != null ? groupValues(row.get(seriesDim.field())) : nullList();
                for (Object seriesGroupVal : seriesVals) {
                    if (seriesDim != null) {
                        rawSeriesKeys.add(seriesGroupVal);
                    }
                    DimensionKey key = new DimensionKey(dimGroupVal, seriesGroupVal);
                    groups.computeIfAbsent(key, k -> new ArrayList<>()).add(row);
                }
            }
        }

        List<Object> sortedDimVals = groups.keySet().stream()
                .map(DimensionKey::dimVal)
                .distinct()
                .sorted(comparatorFor(dimFieldDef))
                .toList();

        List<Object> sortedSeriesVals = rawSeriesKeys.stream()
                .sorted(comparatorFor(null))
                .toList();

        List<String> categories = sortedDimVals.stream()
                .map(v -> formatDimensionLabel(v, dimFieldDef, dim.granularity()))
                .toList();

        // Mirror the SQL pivot's missing-cell rule: additive aggregations fill with 0,
        // AVG/MIN/MAX leave gaps (null) rather than fabricating zero points.
        BigDecimal missing = (measure.aggregation() == Aggregation.SUM || measure.aggregation() == Aggregation.COUNT)
                ? BigDecimal.ZERO : null;
        List<ChartData.Series> seriesList = new ArrayList<>();
        if (seriesDim == null) {
            List<BigDecimal> data = new ArrayList<>();
            for (Object dimVal : sortedDimVals) {
                List<Map<String, Object>> groupRows = groups.get(new DimensionKey(dimVal, null));
                BigDecimal aggVal = groupRows != null
                        ? calculateAggregate(groupRows, measure.field(), measure.aggregation())
                        : null;
                data.add(aggVal != null ? aggVal : missing);
            }
            seriesList.add(new ChartData.Series(measure.field(), data));
        } else {
            for (Object seriesVal : sortedSeriesVals) {
                String seriesName = seriesVal == null ? "(none)" : String.valueOf(seriesVal);
                List<BigDecimal> data = new ArrayList<>();
                for (Object dimVal : sortedDimVals) {
                    List<Map<String, Object>> groupRows = groups.get(new DimensionKey(dimVal, seriesVal));
                    BigDecimal aggVal = groupRows != null
                            ? calculateAggregate(groupRows, measure.field(), measure.aggregation())
                            : null;
                    data.add(aggVal != null ? aggVal : missing);
                }
                seriesList.add(new ChartData.Series(seriesName, data));
            }
        }

        DateRange effRange = dateRangeResolver.effectiveRange(dateRangeResolver.findDateFilter(datasource, def.filters()));
        ChartData.DateRangeView dateRangeView = effRange.bounded()
                ? new ChartData.DateRangeView(effRange.from(), effRange.to())
                : null;
        ChartData.Meta meta = new ChartData.Meta(filteredRows.size(), dateRangeView);
        ChartData.MeasureView measureView = new ChartData.MeasureView(measure.field(), measure.aggregation().json());

        return new ChartData("CHART", def.chartType().json(), dim.field(), categories, seriesList, measureView, meta);
    }

    // ------------------------------------------------------------------
    // TABLE
    // ------------------------------------------------------------------

    public TableData execute(RawTableDefinition def, ReportDatasource datasource, Map<String, Object> unusedParams, Integer page, Integer size) {
        return executeRawTable(def, datasource, page, size);
    }

    public PivotTableData execute(AggregatedTableDefinition def, ReportDatasource datasource, Map<String, Object> unusedParams, Integer page, Integer size) {
        return executeAggregatedTable(def, datasource, page, size);
    }

    public PivotTableData execute(AggregatedTableDefinition def, ReportDatasource datasource, Map<String, Object> unusedParams) {
        return executeAggregatedTable(def, datasource, 0, DEFAULT_PAGE_SIZE);
    }

    public ReportData execute(TableDefinition def, ReportDatasource datasource, Map<String, Object> unusedParams, Integer page, Integer size) {
        if (def instanceof RawTableDefinition rawDef) {
            return executeRawTable(rawDef, datasource, page, size);
        }
        if (def instanceof AggregatedTableDefinition aggDef) {
            return executeAggregatedTable(aggDef, datasource, page, size);
        }
        throw new IllegalArgumentException("Unsupported table definition type: " + def.getClass());
    }

    private TableData executeRawTable(RawTableDefinition def, ReportDatasource datasource, Integer page, Integer size) {
        ComputedReportDatasource computedDs = (ComputedReportDatasource) datasource;
        List<Map<String, Object>> allRows = loadRows(computedDs, def.filters());
        List<Map<String, Object>> filteredRows = filterRows(allRows, def.filters(), computedDs);

        List<IndexedRow> indexedRows = new ArrayList<>();
        for (int i = 0; i < filteredRows.size(); i++) {
            indexedRows.add(new IndexedRow(i, filteredRows.get(i)));
        }

        List<SortClause> sort = def.sort() != null && !def.sort().isEmpty()
                ? def.sort()
                : defaultSort(datasource);
        if (!sort.isEmpty()) {
            indexedRows.sort((a, b) -> {
                for (SortClause sc : sort) {
                    Object valA = displayValue(a.row.get(sc.key()));
                    Object valB = displayValue(b.row.get(sc.key()));
                    int cmp = compareValues(valA, valB);
                    if (cmp != 0) {
                        return sc.direction() == SortDirection.DESC ? -cmp : cmp;
                    }
                }
                return Integer.compare(a.index, b.index);
            });
        }

        // Page numbers are 0-based, matching the SQL path and the client's pager.
        int pSize = size != null && size > 0 ? Math.min(size, MAX_PAGE_SIZE) : DEFAULT_PAGE_SIZE;
        int pNum = page != null ? Math.max(0, page) : 0;
        int totalRows = indexedRows.size();
        int totalPages = totalRows == 0 ? 1 : (int) Math.ceil((double) totalRows / pSize);
        int fromIdx = pNum * pSize;
        int toIdx = Math.min(fromIdx + pSize, totalRows);

        List<IndexedRow> pageRows = fromIdx < totalRows ? indexedRows.subList(fromIdx, toIdx) : List.of();

        List<TableData.Column> columns = def.columns().stream().map(colName -> {
            FieldDef f = datasource.field(colName);
            String label = f != null ? f.label() : colName;
            String type = f != null ? f.type().name().toLowerCase() : "string";
            String format = f != null ? f.format() : null;
            return new TableData.Column(colName, label, type, format);
        }).toList();

        List<Map<String, Object>> data = new ArrayList<>();
        for (IndexedRow ir : pageRows) {
            Map<String, Object> map = new LinkedHashMap<>();
            Object idVal = ir.row.get("id");
            map.put("id", idVal != null ? String.valueOf(idVal) : String.valueOf(ir.index));
            for (String colName : def.columns()) {
                map.put(colName, displayValue(ir.row.get(colName)));
            }
            data.add(map);
        }

        TableData.Page pageObj = new TableData.Page(pNum, pSize, totalRows, totalPages);
        return new TableData("TABLE", "raw", columns, data, pageObj);
    }

    private PivotTableData executeAggregatedTable(AggregatedTableDefinition def, ReportDatasource datasource, Integer page, Integer size) {
        ComputedReportDatasource computedDs = (ComputedReportDatasource) datasource;
        List<Map<String, Object>> allRows = loadRows(computedDs, def.filters());
        List<Map<String, Object>> filteredRows = filterRows(allRows, def.filters(), computedDs);

        List<DimensionRef> rowDims = def.rows() != null ? def.rows() : List.of();
        List<DimensionRef> colDims = def.columns() != null ? def.columns() : List.of();
        List<MeasureRef> measures = def.measures() != null ? def.measures() : List.of();

        Map<MultiDimensionKey, List<Map<String, Object>>> cellGroups = new LinkedHashMap<>();
        Set<List<Object>> rawRowKeys = new LinkedHashSet<>();
        Set<List<Object>> rawColKeys = new LinkedHashSet<>();

        for (Map<String, Object> row : filteredRows) {
            // A multi-valued dimension fans the row out: one key combination per value.
            for (List<Object> rKeys : keyCombinations(row, rowDims, datasource)) {
                rawRowKeys.add(rKeys);
                for (List<Object> cKeys : keyCombinations(row, colDims, datasource)) {
                    rawColKeys.add(cKeys);
                    MultiDimensionKey key = new MultiDimensionKey(rKeys, cKeys);
                    cellGroups.computeIfAbsent(key, k -> new ArrayList<>()).add(row);
                }
            }
        }

        List<List<Object>> sortedRowKeys = new ArrayList<>(rawRowKeys);
        sortedRowKeys.sort((a, b) -> compareDimensionLists(a, b, rowDims, datasource));

        List<List<Object>> sortedColKeys = new ArrayList<>(rawColKeys);
        sortedColKeys.sort((a, b) -> compareDimensionLists(a, b, colDims, datasource));

        List<PivotTableData.DimensionInfo> rowDimInfo = rowDims.stream().map(d -> {
            FieldDef f = datasource.field(d.field());
            return new PivotTableData.DimensionInfo(d.field(), f != null ? f.label() : d.field());
        }).toList();

        List<PivotTableData.DimensionInfo> colDimInfo = colDims.stream().map(d -> {
            FieldDef f = datasource.field(d.field());
            return new PivotTableData.DimensionInfo(d.field(), f != null ? f.label() : d.field());
        }).toList();

        List<PivotTableData.MeasureInfo> measureInfos = measures.stream().map(m -> {
            FieldDef f = datasource.field(m.field());
            String fieldLabel = f != null ? f.label() : m.field();
            String mKey = m.field() + "_" + m.aggregation().json();
            String label = fieldLabel + " (" + capitalize(m.aggregation().json()) + ")";
            String format = f != null ? f.format() : null;
            return new PivotTableData.MeasureInfo(mKey, m.field(), m.aggregation().json(), label, format);
        }).toList();

        List<PivotTableData.ColumnHeader> columnHeaders = new ArrayList<>();
        if (colDims.isEmpty()) {
            columnHeaders.add(new PivotTableData.ColumnHeader("", Map.of()));
        } else {
            for (List<Object> cKeys : sortedColKeys) {
                Map<String, String> colVals = new LinkedHashMap<>();
                List<String> cParts = new ArrayList<>();
                for (int i = 0; i < colDims.size(); i++) {
                    DimensionRef cDim = colDims.get(i);
                    FieldDef f = datasource.field(cDim.field());
                    String formatted = formatDimensionLabel(cKeys.get(i), f, cDim.granularity());
                    colVals.put(cDim.field(), formatted);
                    cParts.add(formatted);
                }
                String colKeyStr = String.join(" / ", cParts);
                columnHeaders.add(new PivotTableData.ColumnHeader(colKeyStr, colVals));
            }
        }

        List<PivotTableData.Row> pivotRows = new ArrayList<>();
        int rowIndex = 0;
        for (List<Object> rKeys : sortedRowKeys) {
            Map<String, String> rHeaderVals = new LinkedHashMap<>();
            List<String> rParts = new ArrayList<>();
            for (int i = 0; i < rowDims.size(); i++) {
                DimensionRef rDim = rowDims.get(i);
                FieldDef f = datasource.field(rDim.field());
                String formatted = formatDimensionLabel(rKeys.get(i), f, rDim.granularity());
                rHeaderVals.put(rDim.field(), formatted);
                rParts.add(formatted);
            }
            String rowKeyStr = String.join(" / ", rParts);

            Map<String, Map<String, Object>> cells = new LinkedHashMap<>();
            if (colDims.isEmpty()) {
                List<Map<String, Object>> gRows = cellGroups.get(new MultiDimensionKey(rKeys, List.of()));
                Map<String, Object> measureVals = new LinkedHashMap<>();
                if (gRows != null) {
                    for (MeasureRef m : measures) {
                        String mKey = m.field() + "_" + m.aggregation().json();
                        BigDecimal aggVal = calculateAggregate(gRows, m.field(), m.aggregation());
                        measureVals.put(mKey, aggVal);
                    }
                }
                cells.put("", measureVals);
            } else {
                for (int cIdx = 0; cIdx < sortedColKeys.size(); cIdx++) {
                    List<Object> cKeys = sortedColKeys.get(cIdx);
                    PivotTableData.ColumnHeader colHeader = columnHeaders.get(cIdx);
                    List<Map<String, Object>> gRows = cellGroups.get(new MultiDimensionKey(rKeys, cKeys));
                    Map<String, Object> measureVals = new LinkedHashMap<>();
                    if (gRows != null) {
                        for (MeasureRef m : measures) {
                            String mKey = m.field() + "_" + m.aggregation().json();
                            BigDecimal aggVal = calculateAggregate(gRows, m.field(), m.aggregation());
                            measureVals.put(mKey, aggVal);
                        }
                    }
                    cells.put(colHeader.key(), measureVals);
                }
            }

            pivotRows.add(new PivotTableData.Row(rowKeyStr, rHeaderVals, cells));
        }

        // Page numbers are 0-based, matching the SQL path and the client's pager.
        int pSize = size != null && size > 0 ? Math.min(size, MAX_PAGE_SIZE) : DEFAULT_PAGE_SIZE;
        int pNum = page != null ? Math.max(0, page) : 0;
        int totalRows = pivotRows.size();
        int totalPages = totalRows == 0 ? 1 : (int) Math.ceil((double) totalRows / pSize);
        int fromIdx = pNum * pSize;
        int toIdx = Math.min(fromIdx + pSize, totalRows);

        List<PivotTableData.Row> pageRows = fromIdx < totalRows ? pivotRows.subList(fromIdx, toIdx) : List.of();
        TableData.Page pageObj = new TableData.Page(pNum, pSize, totalRows, totalPages);

        return new PivotTableData("TABLE", "aggregated", rowDimInfo, colDimInfo, measureInfos, columnHeaders, pageRows, pageObj);
    }

    // ------------------------------------------------------------------
    // FILTERING ENGINE
    // ------------------------------------------------------------------

    private List<Map<String, Object>> filterRows(List<Map<String, Object>> rows, List<FilterClause> filters, ComputedReportDatasource ds) {
        if (filters == null || filters.isEmpty()) {
            return rows;
        }
        Map<FilterClause, CycleWindows> cycles = new HashMap<>();
        for (FilterClause f : filters) {
            if (CycleOperators.isCycle(f)) {
                cycles.put(f, cycleWindows(CycleOperators.cyclesAgo(f), ds, filters));
            }
        }
        return rows.stream()
                .filter(row -> matchesFilters(row, filters, ds, cycles))
                .toList();
    }

    private List<Map<String, Object>> filterRowsForDateRange(List<Map<String, Object>> rows, FilterClause dateFilter, DateRange range, List<FilterClause> allFilters, ComputedReportDatasource ds) {
        return rows.stream()
                .filter(row -> {
                    if (!matchesFilters(row, allFilters.stream().filter(f -> f != dateFilter).toList(), ds, Map.of())) {
                        return false;
                    }
                    Object val = row.get(dateFilter.field());
                    LocalDate d = ResultValues.toLocalDate(val);
                    return range.contains(d);
                })
                .toList();
    }

    private boolean matchesFilters(Map<String, Object> row, List<FilterClause> filters, ComputedReportDatasource ds,
                                   Map<FilterClause, CycleWindows> cycles) {
        for (FilterClause f : filters) {
            FieldDef fieldDef = ds.field(f.field());
            Object rowVal = row.get(f.field());
            boolean matches;
            if (cycles.containsKey(f)) {
                // Billing cycle: the row's card must exist and its date fall in that card's window.
                matches = cycles.get(f).contains(accountOf(row, ds), ResultValues.toLocalDate(rowVal));
            } else if (fieldDef != null && fieldDef.idField() != null) {
                // Filters store the stable id; older saved filters hold the label. A positive
                // operator matches either; a negated one must exclude both.
                boolean byId = matchesFilter(row.get(fieldDef.idField()), f, fieldDef);
                boolean byLabel = matchesFilter(rowVal, f, fieldDef);
                boolean negated = "is_not".equals(f.operator()) || "not_in".equals(f.operator());
                matches = negated ? byId && byLabel : byId || byLabel;
            } else {
                matches = matchesFilter(rowVal, f, fieldDef);
            }
            if (!matches) {
                return false;
            }
        }
        return true;
    }

    private boolean matchesFilter(Object rowVal, FilterClause f, FieldDef fieldDef) {
        FieldType type = fieldDef != null ? fieldDef.type() : FieldType.STRING;
        String op = f.operator();

        if (rowVal instanceof Collection<?> values) {
            // Multi-valued: a positive operator matches when ANY value does; a negated one
            // only when NO value hits the excluded set. An empty list behaves like null.
            if (values.isEmpty()) {
                return matchesFilter(null, f, fieldDef);
            }
            if (type == FieldType.ENUM && ("is_not".equals(op) || "not_in".equals(op))) {
                return values.stream().allMatch(v -> matchesEnumFilter(v, op, f.value()));
            }
            return values.stream().anyMatch(v -> matchesFilter(v, f, fieldDef));
        }

        return switch (type) {
            case STRING -> matchesStringFilter(rowVal, op, f.value());
            case ENUM -> matchesEnumFilter(rowVal, op, f.value());
            case NUMBER -> matchesNumberFilter(rowVal, op, f.value());
            case BOOLEAN -> matchesBooleanFilter(rowVal, op, f.value());
            case DATE -> matchesDateFilter(rowVal, op, f.value());
        };
    }

    private boolean matchesStringFilter(Object val, String op, JsonNode node) {
        String s = val != null ? String.valueOf(val) : null;
        if (s == null) return false;
        String target = node != null ? node.asText() : "";

        return switch (op) {
            case "exact" -> s.equals(target);
            case "starts_with" -> s.toLowerCase().startsWith(target.toLowerCase());
            case "ends_with" -> s.toLowerCase().endsWith(target.toLowerCase());
            case "contains" -> s.toLowerCase().contains(target.toLowerCase());
            case "in" -> node != null && node.isArray() && containsNode(node, s);
            default -> true;
        };
    }

    private boolean matchesEnumFilter(Object val, String op, JsonNode node) {
        String s = val != null ? String.valueOf(val) : null;
        if (s == null) {
            // A missing value is never X, so it passes the negated operators.
            return "is_not".equals(op) || "not_in".equals(op);
        }

        return switch (op) {
            case "is" -> node != null && s.equalsIgnoreCase(node.asText());
            case "is_not" -> node == null || !s.equalsIgnoreCase(node.asText());
            case "in" -> node != null && node.isArray() && containsNodeIgnoreCase(node, s);
            case "not_in" -> node == null || !node.isArray() || !containsNodeIgnoreCase(node, s);
            default -> true;
        };
    }

    private boolean matchesNumberFilter(Object val, String op, JsonNode node) {
        BigDecimal num = ResultValues.toBigDecimal(val);
        if (num == null) return false;

        return switch (op) {
            case "equals" -> node != null && num.compareTo(new BigDecimal(node.asText())) == 0;
            case "greater_than" -> node != null && num.compareTo(new BigDecimal(node.asText())) > 0;
            case "less_than" -> node != null && num.compareTo(new BigDecimal(node.asText())) < 0;
            case "between" -> {
                if (node != null && node.has("from") && node.has("to")) {
                    BigDecimal from = new BigDecimal(node.get("from").asText());
                    BigDecimal to = new BigDecimal(node.get("to").asText());
                    yield num.compareTo(from) >= 0 && num.compareTo(to) <= 0;
                }
                yield false;
            }
            default -> true;
        };
    }

    private boolean matchesBooleanFilter(Object val, String op, JsonNode node) {
        Boolean b = ResultValues.toBoolean(val);
        if (b == null) return false;
        Boolean target = node != null ? node.asBoolean() : null;
        return Objects.equals(b, target);
    }

    private boolean matchesDateFilter(Object val, String op, JsonNode node) {
        LocalDate date = ResultValues.toLocalDate(val);
        if (date == null) return false;

        return switch (op) {
            case "is" -> node != null && date.equals(LocalDate.parse(node.asText()));
            case "after" -> node != null && date.isAfter(LocalDate.parse(node.asText()));
            case "before" -> node != null && date.isBefore(LocalDate.parse(node.asText()));
            case "between" -> {
                if (node != null && node.has("from") && node.has("to")) {
                    LocalDate from = LocalDate.parse(node.get("from").asText());
                    LocalDate to = LocalDate.parse(node.get("to").asText());
                    yield !date.isBefore(from) && !date.isAfter(to);
                }
                yield false;
            }
            default -> {
                DateRange range = dateRangeResolver.resolveRelative(op, node);
                yield range.contains(date);
            }
        };
    }

    private boolean containsNode(JsonNode arrayNode, String value) {
        for (JsonNode elem : arrayNode) {
            if (value.equals(elem.asText())) return true;
        }
        return false;
    }

    private boolean containsNodeIgnoreCase(JsonNode arrayNode, String value) {
        for (JsonNode elem : arrayNode) {
            if (value.equalsIgnoreCase(elem.asText())) return true;
        }
        return false;
    }

    // ------------------------------------------------------------------
    // AGGREGATION & HELPER MATH
    // ------------------------------------------------------------------

    private BigDecimal calculateAggregate(List<Map<String, Object>> rows, String field, Aggregation agg) {
        List<BigDecimal> values = new ArrayList<>();
        for (Map<String, Object> r : rows) {
            BigDecimal b = ResultValues.toBigDecimal(r.get(field));
            if (b != null) {
                values.add(b);
            }
        }

        if (values.isEmpty()) {
            // SUM/COUNT of nothing is 0 (mirrors the SQL executors' null coercion); others stay null.
            return (agg == Aggregation.COUNT || agg == Aggregation.SUM) ? BigDecimal.ZERO : null;
        }

        return switch (agg) {
            case SUM -> values.stream().reduce(BigDecimal.ZERO, BigDecimal::add);
            case AVG -> {
                BigDecimal sum = values.stream().reduce(BigDecimal.ZERO, BigDecimal::add);
                yield sum.divide(BigDecimal.valueOf(values.size()), 4, RoundingMode.HALF_UP);
            }
            case COUNT -> BigDecimal.valueOf(values.size());
            case MIN -> Collections.min(values);
            case MAX -> Collections.max(values);
        };
    }

    private Object processDimensionValue(Object rawVal, FieldDef fieldDef, Granularity granularity) {
        if (rawVal == null) return null;
        FieldType type = fieldDef != null ? fieldDef.type() : FieldType.STRING;
        if (type == FieldType.DATE) {
            LocalDate date = ResultValues.toLocalDate(rawVal);
            if (date == null) return null;
            if (granularity == null) granularity = Granularity.MONTH;
            return switch (granularity) {
                case DAY -> date;
                case WEEK -> date.minusDays(date.getDayOfWeek().getValue() - 1L);
                case MONTH -> date.withDayOfMonth(1);
                case QUARTER -> date.withMonth(((date.getMonthValue() - 1) / 3) * 3 + 1).withDayOfMonth(1);
                case YEAR -> date.withDayOfYear(1);
                case FY -> dateRangeResolver.fiscalYearStart(date);
            };
        }
        return rawVal;
    }

    private String formatDimensionLabel(Object val, FieldDef fieldDef, Granularity granularity) {
        if (val == null) return "(none)";
        FieldType type = fieldDef != null ? fieldDef.type() : FieldType.STRING;
        if (type == FieldType.DATE && val instanceof LocalDate d) {
            Granularity g = granularity != null ? granularity : Granularity.MONTH;
            return BucketLabels.bucketLabel(d, g, dateRangeResolver.getFiscalYearStartMonth());
        }
        return String.valueOf(val);
    }

    @SuppressWarnings("unchecked")
    private Comparator<Object> comparatorFor(FieldDef fieldDef) {
        return (a, b) -> {
            if (a == null && b == null) return 0;
            if (a == null) return -1;
            if (b == null) return 1;
            if (a instanceof Comparable cA && b instanceof Comparable cB) {
                return cA.compareTo(cB);
            }
            return String.valueOf(a).compareTo(String.valueOf(b));
        };
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private int compareValues(Object a, Object b) {
        if (a == null && b == null) return 0;
        if (a == null) return -1;
        if (b == null) return 1;
        // Numbers compare numerically; anything else (dates, text) must never be parsed as a
        // number — toBigDecimal throws on "2026-08-01" or "Dining".
        if (a instanceof Number && b instanceof Number) {
            return ResultValues.toBigDecimal(a).compareTo(ResultValues.toBigDecimal(b));
        }
        if (a instanceof Comparable cA && a.getClass().isInstance(b)) {
            return cA.compareTo(b);
        }
        return String.valueOf(a).compareTo(String.valueOf(b));
    }

    private int compareDimensionLists(List<Object> listA, List<Object> listB, List<DimensionRef> dims, ReportDatasource ds) {
        for (int i = 0; i < dims.size(); i++) {
            FieldDef f = ds.field(dims.get(i).field());
            int cmp = comparatorFor(f).compare(listA.get(i), listB.get(i));
            if (cmp != 0) return cmp;
        }
        return 0;
    }

    private static String capitalize(String str) {
        if (str == null || str.isEmpty()) return str;
        return str.substring(0, 1).toUpperCase() + str.substring(1);
    }

    /** Mirror the SQL raw path's default order: the datasource's first date field, newest first. */
    private List<SortClause> defaultSort(ReportDatasource datasource) {
        return datasource.fields().stream()
                .filter(f -> f.type() == FieldType.DATE)
                .findFirst()
                .map(f -> List.of(new SortClause(f.name(), SortDirection.DESC)))
                .orElse(List.of());
    }

    /** Rows for one run, letting the datasource skip work outside the run's date filter. */
    private List<Map<String, Object>> loadRows(ComputedReportDatasource ds, List<FilterClause> filters) {
        FilterClause dateFilter = dateRangeResolver.findDateFilter(ds, filters);
        if (CycleOperators.isCycle(dateFilter)) {
            return loadRows(ds, spanHint(dateFilter.field(), cycleWindows(CycleOperators.cyclesAgo(dateFilter), ds, filters), null));
        }
        return loadRows(ds, hint(dateFilter, dateRangeResolver.effectiveRange(dateFilter)));
    }

    private List<Map<String, Object>> loadRows(ComputedReportDatasource ds, ComputedReportDatasource.DateHint hint) {
        List<Map<String, Object>> rows = ds.rows(hint);
        return rows != null ? rows : List.of();
    }

    /** The current user's windows {@code cyclesAgo} cycles back, for the account the report is limited to. */
    private CycleWindows cycleWindows(int cyclesAgo, ComputedReportDatasource ds, List<FilterClause> filters) {
        if (billingCycleService == null) {
            return new CycleWindows(Map.of());
        }
        String accountRef = CycleOperators.singleAccountRef(ds.billingCycleAccountField(), filters);
        return billingCycleService.windows(UserContext.getCurrentUserId(), cyclesAgo, AppTime.today(), accountRef);
    }

    /**
     * The account a row belongs to: the id held by the datasource's billing-cycle account field
     * (its {@code idField}); null if the datasource has none or the row lacks it.
     */
    private static UUID accountOf(Map<String, Object> row, ComputedReportDatasource ds) {
        String accountField = ds.billingCycleAccountField();
        FieldDef account = accountField != null ? ds.field(accountField) : null;
        Object id = account != null && account.idField() != null ? row.get(account.idField()) : null;
        if (id == null) {
            return null;
        }
        try {
            return id instanceof UUID u ? u : UUID.fromString(String.valueOf(id));
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    /** A hint spanning the cycle windows (of one or two cycle sets); null when there are none. */
    private static ComputedReportDatasource.DateHint spanHint(String field, CycleWindows a, CycleWindows b) {
        LocalDate from = a.earliestStart();
        LocalDate to = a.latestEnd();
        if (b != null && b.earliestStart() != null) {
            from = from == null || b.earliestStart().isBefore(from) ? b.earliestStart() : from;
            to = to == null || b.latestEnd().isAfter(to) ? b.latestEnd() : to;
        }
        return from == null ? null : new ComputedReportDatasource.DateHint(field, from, to);
    }

    private static ComputedReportDatasource.DateHint hint(FilterClause dateFilter, DateRange range) {
        if (dateFilter == null || !range.bounded()) {
            return null;
        }
        return new ComputedReportDatasource.DateHint(dateFilter.field(), range.from(), range.to());
    }

    /** The values a row groups under: each element of a multi-valued field, else the value (null included). */
    private static List<Object> groupValues(Object raw) {
        if (raw instanceof Collection<?> values) {
            if (values.isEmpty()) {
                return nullList();
            }
            return new ArrayList<>(new LinkedHashSet<>(values));
        }
        return raw == null ? nullList() : List.of(raw);
    }

    private static List<Object> nullList() {
        List<Object> single = new ArrayList<>(1);
        single.add(null);
        return single;
    }

    /** Every key combination a row contributes for the given dimensions (fan-out on multi-valued ones). */
    private List<List<Object>> keyCombinations(Map<String, Object> row, List<DimensionRef> dims, ReportDatasource datasource) {
        List<List<Object>> combos = new ArrayList<>();
        combos.add(new ArrayList<>());
        for (DimensionRef dim : dims) {
            FieldDef f = datasource.field(dim.field());
            List<List<Object>> next = new ArrayList<>();
            for (Object value : groupValues(row.get(dim.field()))) {
                Object key = processDimensionValue(value, f, dim.granularity());
                for (List<Object> combo : combos) {
                    List<Object> extended = new ArrayList<>(combo);
                    extended.add(key);
                    next.add(extended);
                }
            }
            combos = next;
        }
        return combos;
    }

    /** Multi-valued cells render and sort as a comma-joined string. */
    private static Object displayValue(Object value) {
        if (value instanceof Collection<?> values) {
            return values.isEmpty() ? null
                    : values.stream().map(String::valueOf).collect(Collectors.joining(", "));
        }
        return value;
    }

    private record IndexedRow(int index, Map<String, Object> row) {}
    private record DimensionKey(Object dimVal, Object seriesVal) {}
    private record MultiDimensionKey(List<Object> rowKeys, List<Object> colKeys) {}
}
