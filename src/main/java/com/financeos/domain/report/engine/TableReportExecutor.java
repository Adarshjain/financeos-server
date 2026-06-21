package com.financeos.domain.report.engine;

import com.financeos.domain.report.datasource.DatasourceCatalog;
import com.financeos.domain.report.datasource.DatasourceCatalog.FieldDef;
import com.financeos.domain.report.datasource.FieldType;
import com.financeos.domain.report.definition.DimensionRef;
import com.financeos.domain.report.definition.Granularity;
import com.financeos.domain.report.definition.MeasureRef;
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

/** Computes a Table report in either raw (per-transaction) or aggregated (group-by) mode. */
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
    public TableData execute(TableDefinition def, UUID userId, Integer page, Integer size) {
        int pageNumber = page == null ? 0 : Math.max(0, page);
        int pageSize = resolveSize(size, def.pageSize());
        return switch (def.mode()) {
            case RAW -> executeRaw(def, userId, pageNumber, pageSize);
            case AGGREGATED -> executeAggregated(def, userId, pageNumber, pageSize);
        };
    }

    // ------------------------------------------------------------------ raw mode

    private TableData executeRaw(TableDefinition def, UUID userId, int page, int size) {
        boolean includeExcluded = Boolean.TRUE.equals(def.includeExcluded());
        Set<Join> joins = EnumSet.noneOf(Join.class);
        Map<String, Object> params = new HashMap<>();
        String where = queryBuilder.buildWhere(def.filters(), includeExcluded, userId, params, joins);

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
        parts.add("t.id ASC"); // stable tiebreaker for deterministic paging
        return " ORDER BY " + String.join(", ", parts);
    }

    // ------------------------------------------------------------------ aggregated mode

    private TableData executeAggregated(TableDefinition def, UUID userId, int page, int size) {
        boolean includeExcluded = Boolean.TRUE.equals(def.includeExcluded());
        Set<Join> joins = EnumSet.noneOf(Join.class);
        Map<String, Object> params = new HashMap<>();
        String where = queryBuilder.buildWhere(def.filters(), includeExcluded, userId, params, joins);

        List<TableData.Column> columns = new ArrayList<>();
        List<String> selectExprs = new ArrayList<>();
        List<String> groupExprs = new ArrayList<>();
        Map<String, String> keyToExpr = new HashMap<>();

        for (DimensionRef dimension : def.groupBy()) {
            String expr = dimensionExpr(dimension, joins);
            groupExprs.add(expr);
            selectExprs.add(expr);
            FieldDef field = catalog.field(dimension.field());
            String label = dimension.granularity() != null
                    ? granularityLabel(dimension.granularity())
                    : field.label();
            columns.add(new TableData.Column(dimension.field(), label, field.type().json()));
            keyToExpr.put(dimension.field(), expr);
        }
        for (MeasureRef measure : def.measures()) {
            String aggExpr = measure.aggregation().name()
                    + "(" + queryBuilder.expression(measure.field(), joins) + ")";
            selectExprs.add(aggExpr);
            FieldDef field = catalog.field(measure.field());
            String key = measure.field() + "_" + measure.aggregation().json();
            columns.add(new TableData.Column(key,
                    field.label() + " (" + capitalize(measure.aggregation().json()) + ")", "number"));
            keyToExpr.put(key, aggExpr);
        }

        String groupBy = " GROUP BY " + String.join(", ", groupExprs);
        long total = count("SELECT COUNT(*) FROM (SELECT 1" + queryBuilder.fromClause(joins) + where + groupBy + ")",
                params);

        StringBuilder sql = new StringBuilder("SELECT ");
        for (int i = 0; i < selectExprs.size(); i++) {
            if (i > 0) {
                sql.append(", ");
            }
            sql.append(selectExprs.get(i)).append(" AS k").append(i);
        }
        sql.append(queryBuilder.fromClause(joins)).append(where).append(groupBy);
        sql.append(aggregatedOrderBy(def.sort(), keyToExpr, groupExprs));
        sql.append(pagination(page, size));

        Query query = em.createNativeQuery(sql.toString());
        params.forEach(query::setParameter);
        @SuppressWarnings("unchecked")
        List<Object[]> rows = query.getResultList();

        int groupCount = def.groupBy().size();
        List<Map<String, Object>> data = new ArrayList<>();
        for (Object[] row : rows) {
            Map<String, Object> record = new LinkedHashMap<>();
            for (int i = 0; i < columns.size(); i++) {
                Object value;
                if (i < groupCount) {
                    Granularity granularity = def.groupBy().get(i).granularity();
                    value = row[i] == null ? null
                            : (granularity != null
                                    ? TransactionQueryBuilder.bucketLabel(ResultValues.toLocalDate(row[i]), granularity)
                                    : row[i].toString());
                } else {
                    value = ResultValues.toBigDecimal(row[i]);
                }
                record.put(columns.get(i).key(), value);
            }
            data.add(record);
        }
        return new TableData("TABLE", "aggregated", columns, data, page(page, size, total));
    }

    private String aggregatedOrderBy(List<SortClause> sort, Map<String, String> keyToExpr, List<String> groupExprs) {
        List<String> parts = new ArrayList<>();
        if (sort != null) {
            for (SortClause clause : sort) {
                parts.add(keyToExpr.get(clause.key()) + direction(clause.direction()));
            }
        }
        // Group expressions as deterministic tiebreakers for stable paging.
        for (String groupExpr : groupExprs) {
            parts.add(groupExpr + " ASC");
        }
        return " ORDER BY " + String.join(", ", parts);
    }

    // ------------------------------------------------------------------ shared

    private String dimensionExpr(DimensionRef ref, Set<Join> joins) {
        String expr = queryBuilder.expression(ref.field(), joins);
        return ref.granularity() != null ? queryBuilder.bucketExpression(expr, ref.granularity()) : expr;
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

    private static int resolveSize(Integer requested, Integer definitionSize) {
        int size = requested != null ? requested
                : (definitionSize != null ? definitionSize : DEFAULT_PAGE_SIZE);
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
