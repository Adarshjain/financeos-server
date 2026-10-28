package com.financeos.domain.report;

import com.financeos.core.exception.ValidationException;
import com.financeos.domain.report.datasource.DatasourceCatalog.FieldDef;
import com.financeos.domain.report.datasource.DatasourceCatalog.FieldGuard;
import com.financeos.domain.report.datasource.DatasourceCatalog.GuardKind;
import com.financeos.domain.report.datasource.DatasourceCatalog.SingleDatasourceView;
import com.financeos.domain.report.datasource.DatasourceRegistry;
import com.financeos.domain.report.datasource.FieldType;
import com.financeos.domain.report.datasource.ReportDatasource;
import com.financeos.domain.report.definition.AggregatedTableDefinition;
import com.financeos.domain.report.definition.ChartDefinition;
import com.financeos.domain.report.definition.FilterClause;
import com.financeos.domain.report.definition.KpiDefinition;
import com.financeos.domain.report.definition.MeasureRef;
import com.financeos.domain.report.definition.ReportDefinition;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import static com.financeos.domain.report.ReportDefinitionValidator.filtersOf;
import static com.financeos.domain.report.ReportDefinitionValidator.groupedFields;
import static com.financeos.domain.report.ReportDefinitionValidator.isSingleValue;

/**
 * Checks a report definition against the combination guards of the catalog fields it uses (see
 * {@link FieldGuard}). Enforced when a report is saved or drafted, never when a saved report runs.
 */
@Component
public class ReportCombinationGuard {

    private final DatasourceRegistry registry;

    public ReportCombinationGuard(DatasourceRegistry registry) {
        this.registry = registry;
    }

    /** Throws {@link ValidationException} with the first problem of {@code def} on {@code datasourceName}. */
    public void enforce(String datasourceName, ReportDefinition def) {
        ReportDatasource ds = registry.byName(datasourceName);
        enforce(new SingleDatasourceView(ds.name(), ds.label(), ds.fields(), ds.billingCycleAccountField()), def);
    }

    public void enforce(SingleDatasourceView ds, ReportDefinition def) {
        List<String> problems = problems(ds, def);
        if (!problems.isEmpty()) {
            throw new ValidationException(problems.get(0));
        }
    }

    public List<String> problems(SingleDatasourceView ds, ReportDefinition def) {
        Set<String> out = new LinkedHashSet<>();
        List<FilterClause> filters = filtersOf(def);

        // no_filter applies to every report type, raw tables included.
        for (FilterClause f : filters) {
            FieldDef field = field(ds, f.field());
            if (field == null || field.guards() == null) {
                continue;
            }
            for (FieldGuard g : field.guards()) {
                if (g.kind() == GuardKind.NO_FILTER) {
                    out.add(field.label() + " " + g.reason() + ", so it can't be filtered.");
                }
            }
        }

        Set<String> split = new LinkedHashSet<>(groupedFields(def));
        for (MeasureRef m : measuresOf(def)) {
            FieldDef field = field(ds, m.field());
            if (field == null || field.guards() == null || m.aggregation() == null) {
                continue;
            }
            for (FieldGuard g : field.guards()) {
                switch (g.kind()) {
                    case NO_AGGREGATION -> {
                        if (g.covers(m.aggregation())) {
                            out.add(field.label() + " " + g.reason() + ".");
                        }
                    }
                    case NEEDS -> {
                        if (g.covers(m.aggregation()) && !satisfied(ds, g, split, filters)) {
                            out.add(field.label() + " " + g.reason() + ". Split by " + labels(ds, g.fields(), " or ")
                                    + ", or filter it to one value.");
                        }
                    }
                    case AVOID -> {
                        if (g.covers(m.aggregation())) {
                            List<String> conflicts = new ArrayList<>();
                            for (String name : g.fields()) {
                                boolean filtered = filters.stream().anyMatch(f -> name.equals(f.field()));
                                if (split.contains(name) || filtered) {
                                    conflicts.add(name);
                                }
                            }
                            if (!conflicts.isEmpty()) {
                                out.add(field.label() + " " + g.reason() + ". Remove the split or filter on "
                                        + labels(ds, conflicts, " and ") + ".");
                            }
                        }
                    }
                    case NO_FILTER -> {
                        // handled above
                    }
                }
            }
        }
        return List.copyOf(out);
    }

    private static boolean satisfied(SingleDatasourceView ds, FieldGuard g, Set<String> split, List<FilterClause> filters) {
        for (String name : g.fields()) {
            if (split.contains(name)) {
                return true;
            }
            FieldDef target = field(ds, name);
            boolean date = target != null && target.type() == FieldType.DATE;
            for (FilterClause f : filters) {
                if (!name.equals(f.field())) {
                    continue;
                }
                if (date ? "is".equals(f.operator()) && isSingleValue(f) : isSingle(f)) {
                    return true;
                }
            }
        }
        return false;
    }

    /** One value: "is" / single-element "in" (see the validator), or a string "exact". */
    private static boolean isSingle(FilterClause f) {
        if ("exact".equals(f.operator())) {
            return f.value() != null && !f.value().isArray();
        }
        return isSingleValue(f);
    }

    private static List<MeasureRef> measuresOf(ReportDefinition def) {
        List<MeasureRef> out = new ArrayList<>();
        if (def instanceof KpiDefinition kpi) {
            out.add(new MeasureRef(kpi.measure(), kpi.aggregation()));
        } else if (def instanceof ChartDefinition chart) {
            if (chart.measure() != null) out.add(chart.measure());
        } else if (def instanceof AggregatedTableDefinition table) {
            if (table.measures() != null) out.addAll(table.measures());
        }
        return out;
    }

    private static FieldDef field(SingleDatasourceView ds, String name) {
        if (name == null || ds.fields() == null) {
            return null;
        }
        return ds.fields().stream().filter(f -> name.equals(f.name())).findFirst().orElse(null);
    }

    private static String labels(SingleDatasourceView ds, List<String> names, String joiner) {
        List<String> labels = new ArrayList<>();
        for (String n : names) {
            FieldDef f = field(ds, n);
            labels.add(f != null ? f.label() : n);
        }
        return String.join(joiner, labels);
    }
}
