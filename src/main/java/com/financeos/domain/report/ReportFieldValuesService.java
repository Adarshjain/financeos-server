package com.financeos.domain.report;

import com.financeos.api.report.dto.ReportFieldValuesResponse;
import com.financeos.api.report.dto.ReportFieldValuesResponse.Option;
import com.financeos.core.security.UserContext;
import com.financeos.domain.category.CategoryCatalog;
import com.financeos.domain.report.datasource.ComputedReportDatasource;
import com.financeos.domain.report.datasource.DatasourceCatalog.FieldDef;
import com.financeos.domain.report.datasource.DatasourceRegistry;
import com.financeos.domain.report.datasource.ReportDatasource;
import com.financeos.domain.report.engine.ReportQueryBuilder;
import com.financeos.domain.report.engine.TransactionQueryBuilder;
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
            String idExpr = f.idField() != null ? queryBuilder.expression(f.idField(), joins) : null;
            String where = queryBuilder.buildWhere(List.of(), userId, params, joins);
            String select = idExpr != null ? idExpr + " AS i, " + expr + " AS v" : expr + " AS v";
            String sql = "SELECT DISTINCT " + select + queryBuilder.fromClause(joins, params, userId) + where
                    + " ORDER BY " + (idExpr != null ? "2, 1" : "1") + " FETCH FIRST " + MAX_VALUES + " ROWS ONLY";
            Query query = em.createNativeQuery(sql);
            params.forEach(query::setParameter);
            List<Option> values = new ArrayList<>();
            for (Object row : query.getResultList()) {
                if (idExpr != null) {
                    Object[] cols = (Object[]) row;
                    if (cols[0] != null && cols[1] != null) {
                        values.add(new Option(String.valueOf(cols[0]), String.valueOf(cols[1])));
                    }
                } else if (row != null) {
                    values.add(new Option(String.valueOf(row), String.valueOf(row)));
                }
            }
            if ("categoryKey".equals(f.idField())) {
                values = categoryOptions(values);
            } else if ("subcategoryKey".equals(f.idField())) {
                values = subcategoryOptions(values);
            } else {
                values.sort(BY_LABEL);
            }
            out.put(f.name(), values);
        }
        return out;
    }

    /** Categories in the category list's own order; "Uncategorized" is always offered, last. */
    private static List<Option> categoryOptions(List<Option> found) {
        CategoryCatalog catalog = CategoryCatalog.shared();
        Map<String, Integer> order = new HashMap<>();
        List<CategoryCatalog.Cat> all = catalog.all();
        for (int i = 0; i < all.size(); i++) {
            order.put(all.get(i).key(), i);
        }
        List<Option> values = new ArrayList<>(found);
        values.removeIf(o -> "Uncategorized".equals(o.value()));
        values.sort(Comparator.comparingInt((Option o) -> order.getOrDefault(o.value(), Integer.MAX_VALUE))
                .thenComparing(BY_LABEL));
        values.add(new Option("Uncategorized", "Uncategorized"));
        return values;
    }

    /** Sub-categories named "Category · Sub", ordered by category order, then the sub-category's sort order. */
    private static List<Option> subcategoryOptions(List<Option> found) {
        CategoryCatalog catalog = CategoryCatalog.shared();
        Map<String, Integer> categoryOrder = new HashMap<>();
        List<CategoryCatalog.Cat> all = catalog.all();
        for (int i = 0; i < all.size(); i++) {
            categoryOrder.put(all.get(i).key(), i);
        }
        List<Option> named = new ArrayList<>();
        Map<String, int[]> rank = new HashMap<>();
        for (Option o : found) {
            var sub = catalog.subcategory(o.value());
            if (sub.isPresent()) {
                String category = catalog.category(sub.get().categoryKey()).map(CategoryCatalog.Cat::name)
                        .orElse(sub.get().categoryKey());
                named.add(new Option(o.value(), category + TransactionQueryBuilder.SEPARATOR + sub.get().name()));
                rank.put(o.value(), new int[] {
                        categoryOrder.getOrDefault(sub.get().categoryKey(), Integer.MAX_VALUE), sub.get().sortOrder()});
            } else {
                named.add(o);
            }
        }
        int[] last = {Integer.MAX_VALUE, Integer.MAX_VALUE};
        named.sort(Comparator.comparingInt((Option o) -> rank.getOrDefault(o.value(), last)[0])
                .thenComparingInt(o -> rank.getOrDefault(o.value(), last)[1])
                .thenComparing(BY_LABEL));
        return named;
    }
}
