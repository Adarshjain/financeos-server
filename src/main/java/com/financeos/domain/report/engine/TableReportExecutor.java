package com.financeos.domain.report.engine;

import com.financeos.domain.report.datasource.DatasourceCatalog;
import com.financeos.domain.report.datasource.DatasourceCatalog.FieldDef;
import com.financeos.domain.report.datasource.FieldType;
import com.financeos.domain.report.definition.AggregatedTableDefinition;
import com.financeos.domain.report.definition.DimensionRef;
import com.financeos.domain.report.definition.Granularity;
import com.financeos.domain.report.definition.MeasureRef;
import com.financeos.domain.report.definition.RawTableDefinition;
import com.financeos.domain.report.definition.SortClause;
import com.financeos.domain.report.definition.SortDirection;
import com.financeos.domain.report.definition.TableDefinition;
import com.financeos.domain.report.engine.TransactionQueryBuilder.Join;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import jakarta.persistence.Query;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** Computes a Table report: raw (per-transaction) or aggregated (pivot: rows × columns × measures). */
@Service
public class TableReportExecutor {

    private static final int DEFAULT_PAGE_SIZE = 50;
    private static final int MAX_PAGE_SIZE = 1000;

    @PersistenceContext
    private EntityManager em;

    private final TransactionQueryBuilder queryBuilder;
    private final DatasourceCatalog catalog;

    public TableReportExecutor(TransactionQueryBuilder queryBuilder, DatasourceCatalog catalog) {
        this.queryBuilder = queryBuilder;
        this.catalog = catalog;
    }

    @Transactional(readOnly = true)
    public ReportData execute(TableDefinition def, UUID userId, Integer page, Integer size) {
        int pageNumber = page == null ? 0 : Math.max(0, page);
        int pageSize = resolveSize(size);
        if (def instanceof RawTableDefinition raw) {
            return executeRaw(raw, userId, pageNumber, pageSize);
        }
        if (def instanceof AggregatedTableDefinition aggregated) {
            return executeAggregated(aggregated, userId, pageNumber, pageSize);
        }
        throw new IllegalStateException("Unsupported table definition: " + def.getClass());
    }

    // ------------------------------------------------------------------ raw mode

    private TableData executeRaw(RawTableDefinition def, UUID userId, int page, int size) {
        Set<Join> joins = EnumSet.noneOf(Join.class);
        Map<String, Object> params = new HashMap<>();
        String where = queryBuilder.buildWhere(def.filters(), userId, params, joins);

        List<TableData.Column> columns = new ArrayList<>();
        List<String> selectExprs = new ArrayList<>();
        List<FieldType> colTypes = new ArrayList<>();
        for (String column : def.columns()) {
            FieldDef field = catalog.field(column);
            selectExprs.add(rawColumnExpr(column, joins));
            columns.add(new TableData.Column(column, field.label(), field.type().json()));
            colTypes.add(field.type());
        }

        long total = count("SELECT COUNT(*)" + queryBuilder.fromClause(joins) + where, params);

        StringBuilder sql = new StringBuilder("SELECT t.id AS row_id");
        for (int i = 0; i < selectExprs.size(); i++) {
            sql.append(", ").append(selectExprs.get(i)).append(" AS c").append(i);
        }
        sql.append(queryBuilder.fromClause(joins)).append(where);
        sql.append(rawOrderBy(def.sort(), joins));
        sql.append(pagination(page, size));

        Query query = em.createNativeQuery(sql.toString());
        params.forEach(query::setParameter);
        @SuppressWarnings("unchecked")
        List<Object[]> rows = query.getResultList();

        List<Map<String, Object>> data = new ArrayList<>();
        for (Object[] row : rows) {
            Map<String, Object> record = new LinkedHashMap<>();
            record.put("id", row[0] == null ? null : row[0].toString());
            for (int i = 0; i < columns.size(); i++) {
                record.put(columns.get(i).key(), convert(colTypes.get(i), row[i + 1]));
            }
            data.add(record);
        }
        return new TableData("TABLE", "raw", columns, data, page(page, size, total));
    }

    private String rawColumnExpr(String field, Set<Join> joins) {
        return "category".equals(field)
                ? TransactionQueryBuilder.CATEGORY_LISTAGG
                : queryBuilder.expression(field, joins);
    }

    private String rawOrderBy(List<SortClause> sort, Set<Join> joins) {
        if (sort == null || sort.isEmpty()) {
            return " ORDER BY t.transaction_date DESC, t.id DESC";
        }
        List<String> parts = new ArrayList<>();
        for (SortClause clause : sort) {
            parts.add(rawColumnExpr(clause.key(), joins) + direction(clause.direction()));
        }
        parts.add("t.id ASC");
        return " ORDER BY " + String.join(", ", parts);
    }

    // ------------------------------------------------------------------ aggregated (pivot) mode

    private PivotTableData executeAggregated(AggregatedTableDefinition def, UUID userId, int page, int size) {
        Set<Join> joins = EnumSet.noneOf(Join.class);
        Map<String, Object> params = new HashMap<>();
        String where = queryBuilder.buildWhere(def.filters(), userId, params, joins);

        List<DimensionRef> rowDims = def.rows();
        List<DimensionRef> colDims = def.columns() == null ? List.of() : def.columns();
        List<MeasureRef> measures = def.measures();

        List<String> rowExprs = new ArrayList<>();
        for (DimensionRef d : rowDims) {
            rowExprs.add(dimensionExpr(d, joins));
        }
        List<String> colExprs = new ArrayList<>();
        for (DimensionRef d : colDims) {
            colExprs.add(dimensionExpr(d, joins));
        }
        List<String> measureExprs = new ArrayList<>();
        List<String> measureKeys = new ArrayList<>();
        for (MeasureRef m : measures) {
            measureExprs.add(m.aggregation().name() + "(" + queryBuilder.expression(m.field(), joins) + ")");
            measureKeys.add(m.field() + "_" + m.aggregation().json());
        }

        List<String> selects = new ArrayList<>();
        for (int i = 0; i < rowExprs.size(); i++) {
            selects.add(rowExprs.get(i) + " AS r" + i);
        }
        for (int i = 0; i < colExprs.size(); i++) {
            selects.add(colExprs.get(i) + " AS c" + i);
        }
        for (int i = 0; i < measureExprs.size(); i++) {
            selects.add(measureExprs.get(i) + " AS m" + i);
        }

        List<String> groupExprs = new ArrayList<>();
        groupExprs.addAll(rowExprs);
        groupExprs.addAll(colExprs);

        StringBuilder sql = new StringBuilder("SELECT ").append(String.join(", ", selects));
        sql.append(queryBuilder.fromClause(joins)).append(where);
        sql.append(" GROUP BY ").append(String.join(", ", groupExprs));
        sql.append(aggregatedOrderBy(def.sort(), rowDims, rowExprs, colExprs, measureExprs, measureKeys,
                colDims.isEmpty()));

        Query query = em.createNativeQuery(sql.toString());
        params.forEach(query::setParameter);
        @SuppressWarnings("unchecked")
        List<Object[]> resultRows = query.getResultList();

        int rc = rowExprs.size();
        int cc = colExprs.size();
        int mc = measureExprs.size();

        LinkedHashMap<String, PivotTableData.Row> rowMap = new LinkedHashMap<>();
        LinkedHashMap<String, PivotTableData.ColumnHeader> colMap = new LinkedHashMap<>();

        for (Object[] row : resultRows) {
            Map<String, String> rowValues = new LinkedHashMap<>();
            List<String> rowParts = new ArrayList<>();
            for (int i = 0; i < rc; i++) {
                String labelValue = label(rowDims.get(i), row[i]);
                rowValues.put(rowDims.get(i).field(), labelValue);
                rowParts.add(labelValue);
            }
            String rowKey = String.join("|", rowParts);

            Map<String, String> colValues = new LinkedHashMap<>();
            List<String> colParts = new ArrayList<>();
            for (int i = 0; i < cc; i++) {
                String labelValue = label(colDims.get(i), row[rc + i]);
                colValues.put(colDims.get(i).field(), labelValue);
                colParts.add(labelValue);
            }
            String colKey = cc == 0 ? "" : String.join("|", colParts);
            colMap.putIfAbsent(colKey, new PivotTableData.ColumnHeader(colKey, colValues));

            Map<String, Object> measureValues = new LinkedHashMap<>();
            for (int i = 0; i < mc; i++) {
                measureValues.put(measureKeys.get(i), ResultValues.toBigDecimal(row[rc + cc + i]));
            }

            PivotTableData.Row pivotRow = rowMap.computeIfAbsent(rowKey,
                    k -> new PivotTableData.Row(k, rowValues, new LinkedHashMap<>()));
            pivotRow.cells().put(colKey, measureValues);
        }

        // Pagination over distinct row groups (in query order).
        List<PivotTableData.Row> allRows = new ArrayList<>(rowMap.values());
        int total = allRows.size();
        int from = Math.min((int) Math.min((long) page * size, Integer.MAX_VALUE), total);
        int to = Math.min(from + size, total);
        List<PivotTableData.Row> pageRows = new ArrayList<>(allRows.subList(from, to));

        List<PivotTableData.DimensionInfo> rowDimInfo = new ArrayList<>();
        for (DimensionRef d : rowDims) {
            rowDimInfo.add(dimInfo(d));
        }
        List<PivotTableData.DimensionInfo> colDimInfo = new ArrayList<>();
        for (DimensionRef d : colDims) {
            colDimInfo.add(dimInfo(d));
        }
        List<PivotTableData.MeasureInfo> measureInfo = new ArrayList<>();
        for (MeasureRef m : measures) {
            measureInfo.add(new PivotTableData.MeasureInfo(
                    m.field() + "_" + m.aggregation().json(), m.field(), m.aggregation().json(),
                    catalog.field(m.field()).label() + " (" + capitalize(m.aggregation().json()) + ")"));
        }

        return new PivotTableData("TABLE", "aggregated", rowDimInfo, colDimInfo, measureInfo,
                new ArrayList<>(colMap.values()), pageRows, page(page, size, total));
    }

    private String aggregatedOrderBy(List<SortClause> sort, List<DimensionRef> rowDims, List<String> rowExprs,
            List<String> colExprs, List<String> measureExprs, List<String> measureKeys, boolean noColumns) {
        Map<String, String> sortable = new HashMap<>();
        for (int i = 0; i < rowDims.size(); i++) {
            sortable.put(rowDims.get(i).field(), rowExprs.get(i));
        }
        if (noColumns) {
            for (int i = 0; i < measureKeys.size(); i++) {
                sortable.put(measureKeys.get(i), measureExprs.get(i));
            }
        }
        List<String> parts = new ArrayList<>();
        if (sort != null) {
            for (SortClause clause : sort) {
                String expr = sortable.get(clause.key());
                if (expr != null) {
                    parts.add(expr + direction(clause.direction()));
                }
            }
        }
        // Deterministic tiebreakers: row dimensions, then column dimensions.
        for (String expr : rowExprs) {
            parts.add(expr + " ASC");
        }
        for (String expr : colExprs) {
            parts.add(expr + " ASC");
        }
        return " ORDER BY " + String.join(", ", parts);
    }

    // ------------------------------------------------------------------ shared

    private String dimensionExpr(DimensionRef ref, Set<Join> joins) {
        String expr = queryBuilder.expression(ref.field(), joins);
        return ref.granularity() != null ? queryBuilder.bucketExpression(expr, ref.granularity()) : expr;
    }

    private PivotTableData.DimensionInfo dimInfo(DimensionRef ref) {
        String label = ref.granularity() != null
                ? granularityLabel(ref.granularity())
                : catalog.field(ref.field()).label();
        return new PivotTableData.DimensionInfo(ref.field(), label);
    }

    private String label(DimensionRef ref, Object raw) {
        if (raw == null) {
            return "(none)";
        }
        if (ref.granularity() != null) {
            return TransactionQueryBuilder.bucketLabel(ResultValues.toLocalDate(raw), ref.granularity());
        }
        return String.valueOf(raw);
    }

    private long count(String sql, Map<String, Object> params) {
        Query query = em.createNativeQuery(sql);
        params.forEach(query::setParameter);
        return ((Number) query.getSingleResult()).longValue();
    }

    private static String pagination(int page, int size) {
        return " OFFSET " + ((long) page * size) + " ROWS FETCH NEXT " + size + " ROWS ONLY";
    }

    private static TableData.Page page(int page, int size, long total) {
        int totalPages = size == 0 ? 0 : (int) ((total + size - 1) / size);
        return new TableData.Page(page, size, total, totalPages);
    }

    private static int resolveSize(Integer requested) {
        int size = requested != null ? requested : DEFAULT_PAGE_SIZE;
        return Math.max(1, Math.min(size, MAX_PAGE_SIZE));
    }

    private static Object convert(FieldType type, Object raw) {
        if (raw == null) {
            return null;
        }
        return switch (type) {
            case NUMBER -> ResultValues.toBigDecimal(raw);
            case DATE -> ResultValues.toLocalDate(raw);
            case BOOLEAN -> raw instanceof Number n ? n.intValue() != 0 : Boolean.parseBoolean(raw.toString());
            case STRING, ENUM -> raw.toString();
        };
    }

    private static String direction(SortDirection direction) {
        return direction == SortDirection.DESC ? " DESC" : " ASC";
    }

    private static String granularityLabel(Granularity granularity) {
        return switch (granularity) {
            case DAY -> "Day";
            case WEEK -> "Week";
            case MONTH -> "Month";
            case QUARTER -> "Quarter";
            case YEAR -> "Year";
        };
    }

    private static String capitalize(String s) {
        return s.isEmpty() ? s : Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }
}
