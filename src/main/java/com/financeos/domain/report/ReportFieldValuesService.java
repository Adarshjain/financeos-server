package com.financeos.domain.report;

import com.financeos.api.report.dto.ReportFieldValuesResponse;
import com.financeos.api.report.dto.ReportFieldValuesResponse.Option;
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
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;

/**
 * The selectable values of a datasource's dynamic (user-specific) enum fields — the values
 * that actually occur in the current user's data, exactly as filters compare them. One
 * source for every datasource, so no client needs to know where a field's values live.
 * A field with an {@code idField} offers (id, label) pairs so filters store the stable id.
 */
@Service
public class ReportFieldValuesService {

    /** Per-field cap; a filter dropdown longer than this is not usable anyway. */
    static final int MAX_VALUES = 500;

    private static final Comparator<Option> BY_LABEL =
            Comparator.comparing(Option::label, String.CASE_INSENSITIVE_ORDER).thenComparing(Option::value);

    @PersistenceContext
    private EntityManager em;

    private final DatasourceRegistry registry;

    public ReportFieldValuesService(DatasourceRegistry registry) {
        this.registry = registry;
    }

    /** Labels and filter options for every dynamic field of the datasource. */
    @Transactional(readOnly = true)
    public ReportFieldValuesResponse values(String datasourceName) {
        ReportDatasource datasource = registry.byName(datasourceName);
        List<FieldDef> dynamic = datasource.fields().stream()
                .filter(f -> Boolean.TRUE.equals(f.dynamic()))
                .toList();
        Map<String, List<Option>> options = dynamic.isEmpty() ? Map.of()
                : datasource instanceof ComputedReportDatasource computed
                        ? computedOptions(computed, dynamic)
                        : sqlOptions(datasource, dynamic);
        Map<String, List<String>> labels = new LinkedHashMap<>();
        options.forEach((field, list) -> labels.put(field, list.stream().map(Option::label).distinct().toList()));
        return new ReportFieldValuesResponse(labels, options);
    }

    private Map<String, List<Option>> computedOptions(ComputedReportDatasource datasource, List<FieldDef> dynamic) {
        // Keyed by filter value (id when the field has one) so each option appears once;
        // case-insensitive like the filters themselves, so "Dining" and "dining" are one option.
        Map<String, Map<String, String>> collected = new LinkedHashMap<>();
        for (FieldDef f : dynamic) {
            collected.put(f.name(), new TreeMap<>(String.CASE_INSENSITIVE_ORDER));
        }
        List<Map<String, Object>> rows = datasource.rows();
        if (rows != null) {
            for (Map<String, Object> row : rows) {
                for (FieldDef f : dynamic) {
                    Map<String, String> byValue = collected.get(f.name());
                    Object label = row.get(f.name());
                    Object id = f.idField() != null ? row.get(f.idField()) : null;
                    if (label instanceof Collection<?> many) {
                        many.stream().filter(v -> v != null).forEach(v -> byValue.putIfAbsent(String.valueOf(v), String.valueOf(v)));
                    } else if (label != null) {
                        String l = String.valueOf(label);
                        byValue.putIfAbsent(id != null ? String.valueOf(id) : l, l);
                    }
                }
            }
        }
        Map<String, List<Option>> out = new LinkedHashMap<>();
        collected.forEach((name, byValue) -> out.put(name, byValue.entrySet().stream()
                .map(e -> new Option(e.getKey(), e.getValue()))
                .sorted(BY_LABEL)
                .limit(MAX_VALUES)
                .toList()));
        return out;
    }

    private Map<String, List<Option>> sqlOptions(ReportDatasource datasource, List<FieldDef> dynamic) {
        UUID userId = UserContext.getCurrentUserId();
        ReportQueryBuilder queryBuilder = datasource.queryBuilder();
        Map<String, List<Option>> out = new LinkedHashMap<>();
        for (FieldDef f : dynamic) {
            Set<String> joins = new HashSet<>();
            Map<String, Object> params = new HashMap<>();
            String expr = queryBuilder.expression(f.name(), joins);
            String where = queryBuilder.buildWhere(List.of(), userId, params, joins);
            String sql = "SELECT DISTINCT " + expr + " AS v" + queryBuilder.fromClause(joins) + where
                    + " ORDER BY 1 FETCH FIRST " + MAX_VALUES + " ROWS ONLY";
            Query query = em.createNativeQuery(sql);
            params.forEach(query::setParameter);
            List<Option> values = new ArrayList<>();
            for (Object value : query.getResultList()) {
                if (value != null) {
                    values.add(new Option(String.valueOf(value), String.valueOf(value)));
                }
            }
            values.sort(BY_LABEL);
            out.put(f.name(), values);
        }
        return out;
    }
}
