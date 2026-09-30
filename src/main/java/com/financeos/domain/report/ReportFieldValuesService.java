package com.financeos.domain.report;

import com.financeos.core.security.UserContext;
import com.financeos.domain.report.datasource.ComputedReportDatasource;
import com.financeos.domain.report.datasource.DatasourceCatalog.FieldDef;
import com.financeos.domain.report.datasource.DatasourceRegistry;
import com.financeos.domain.report.datasource.ReportDatasource;
import com.financeos.domain.report.engine.ReportQueryBuilder;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import jakarta.persistence.Query;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;

/**
 * The selectable values of a datasource's dynamic (user-specific) enum fields — the values
 * that actually occur in the current user's data, exactly as filters compare them. One
 * source for every datasource, so no client needs to know where a field's values live.
 */
@Service
public class ReportFieldValuesService {

    /** Per-field cap; a filter dropdown longer than this is not usable anyway. */
    static final int MAX_VALUES = 500;

    @PersistenceContext
    private EntityManager em;

    private final DatasourceRegistry registry;

    public ReportFieldValuesService(DatasourceRegistry registry) {
        this.registry = registry;
    }

    /** Field name → sorted distinct values, for every dynamic field of the datasource. */
    @Transactional(readOnly = true)
    public Map<String, List<String>> values(String datasourceName) {
        ReportDatasource datasource = registry.byName(datasourceName);
        List<FieldDef> dynamic = datasource.fields().stream()
                .filter(f -> Boolean.TRUE.equals(f.dynamic()))
                .toList();
        if (dynamic.isEmpty()) {
            return Map.of();
        }
        if (datasource instanceof ComputedReportDatasource computed) {
            return computedValues(computed, dynamic);
        }
        return sqlValues(datasource, dynamic);
    }

    private Map<String, List<String>> computedValues(ComputedReportDatasource datasource, List<FieldDef> dynamic) {
        Map<String, Set<String>> collected = new LinkedHashMap<>();
        for (FieldDef f : dynamic) {
            collected.put(f.name(), new TreeSet<>(String.CASE_INSENSITIVE_ORDER));
        }
        List<Map<String, Object>> rows = datasource.rows();
        if (rows != null) {
            for (Map<String, Object> row : rows) {
                for (Map.Entry<String, Set<String>> entry : collected.entrySet()) {
                    Object value = row.get(entry.getKey());
                    if (value instanceof Collection<?> many) {
                        many.stream().filter(v -> v != null).forEach(v -> entry.getValue().add(String.valueOf(v)));
                    } else if (value != null) {
                        entry.getValue().add(String.valueOf(value));
                    }
                }
            }
        }
        Map<String, List<String>> out = new LinkedHashMap<>();
        collected.forEach((name, set) -> out.put(name, set.stream().limit(MAX_VALUES).toList()));
        return out;
    }

    private Map<String, List<String>> sqlValues(ReportDatasource datasource, List<FieldDef> dynamic) {
        UUID userId = UserContext.getCurrentUserId();
        ReportQueryBuilder queryBuilder = datasource.queryBuilder();
        Map<String, List<String>> out = new LinkedHashMap<>();
        for (FieldDef f : dynamic) {
            Set<String> joins = new HashSet<>();
            Map<String, Object> params = new HashMap<>();
            String expr = queryBuilder.expression(f.name(), joins);
            String where = queryBuilder.buildWhere(List.of(), userId, params, joins);
            String sql = "SELECT DISTINCT " + expr + " AS v" + queryBuilder.fromClause(joins) + where
                    + " ORDER BY 1 FETCH FIRST " + MAX_VALUES + " ROWS ONLY";
            Query query = em.createNativeQuery(sql);
            params.forEach(query::setParameter);
            List<String> values = new ArrayList<>();
            for (Object value : query.getResultList()) {
                if (value != null) {
                    values.add(String.valueOf(value));
                }
            }
            values.sort(String.CASE_INSENSITIVE_ORDER);
            out.put(f.name(), values);
        }
        return out;
    }
}
