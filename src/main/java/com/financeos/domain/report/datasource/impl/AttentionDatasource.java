package com.financeos.domain.report.datasource.impl;

import com.financeos.api.inbox.dto.InboxItemResponse;
import com.financeos.core.security.UserContext;
import com.financeos.domain.inbox.InboxKinds;
import com.financeos.domain.inbox.InboxService;
import com.financeos.domain.report.ReportType;
import com.financeos.domain.report.datasource.Aggregation;
import com.financeos.domain.report.datasource.ComputedReportDatasource;
import com.financeos.domain.report.datasource.DatasourceCatalog.FieldDef;
import com.financeos.domain.report.datasource.FieldRole;
import com.financeos.domain.report.datasource.FieldType;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.stereotype.Component;

/**
 * The inbox rolled up per kind, for the "Needs attention" dashboard widget and reports: one row
 * per kind with the number of things behind it, its worst severity and where it lands. Built from
 * {@link InboxService#list} with the user's snoozes and dismissals already applied.
 */
@Component
public class AttentionDatasource implements ComputedReportDatasource {

    private static final List<ReportType> KPI_CHART_TABLE = List.of(ReportType.KPI, ReportType.CHART, ReportType.TABLE);
    private static final List<ReportType> CHART_TABLE = List.of(ReportType.CHART, ReportType.TABLE);
    private static final List<ReportType> TABLE_ONLY = List.of(ReportType.TABLE);

    /** Each kind as the inbox names it. */
    private static final Map<String, String> KIND_LABELS = InboxKinds.ALL.stream()
            .collect(Collectors.toMap(k -> k, InboxKinds::label, (a, b) -> a, LinkedHashMap::new));
    private static final List<String> SEVERITIES = List.of(
            InboxItemResponse.SEVERITY_CRITICAL, InboxItemResponse.SEVERITY_WARNING, InboxItemResponse.SEVERITY_INFO);
    private static final List<String> SECTIONS = List.of(
            InboxItemResponse.SECTION_ACT_NOW, InboxItemResponse.SECTION_NEEDS_LOOK, InboxItemResponse.SECTION_INFO);

    private final InboxService inboxService;
    private final List<FieldDef> fields;

    public AttentionDatasource(InboxService inboxService) {
        this.inboxService = inboxService;
        this.fields = buildCatalog();
    }

    @Override
    public String name() {
        return "attention";
    }

    @Override
    public String label() {
        return "Needs attention";
    }

    @Override
    public List<FieldDef> fields() {
        return fields;
    }

    @Override
    public List<Map<String, Object>> rows() {
        UUID userId = UserContext.getCurrentUserId();
        if (userId == null) {
            return List.of();
        }
        Map<String, Group> groups = new LinkedHashMap<>();
        for (InboxItemResponse item : inboxService.list(userId).items()) {
            groups.computeIfAbsent(item.kind(), Group::new).add(item);
        }
        List<Map<String, Object>> rows = new ArrayList<>(groups.size());
        for (Group group : groups.values()) {
            rows.add(group.toRow());
        }
        return rows;
    }

    /** One kind's rows: the worst severity and section win, counts add up, a lone row keeps its own link. */
    private static final class Group {
        private final String kind;
        private int severityRank = Integer.MAX_VALUE;
        private int sectionRank = Integer.MAX_VALUE;
        private int count;
        private int rows;
        private String href;

        Group(String kind) {
            this.kind = kind;
        }

        void add(InboxItemResponse item) {
            severityRank = Math.min(severityRank, rank(SEVERITIES, item.severity()));
            sectionRank = Math.min(sectionRank, rank(SECTIONS, item.section()));
            count += item.isSummary() && item.count() != null ? item.count() : 1;
            rows++;
            if (href == null) {
                href = item.href();
            }
        }

        Map<String, Object> toRow() {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("id", kind);
            row.put("kind", kind);
            row.put("label", InboxKinds.label(kind));
            row.put("severity", severityRank < SEVERITIES.size() ? SEVERITIES.get(severityRank) : InboxItemResponse.SEVERITY_INFO);
            row.put("section", sectionRank < SECTIONS.size() ? SECTIONS.get(sectionRank) : InboxItemResponse.SECTION_INFO);
            row.put("count", BigDecimal.valueOf(count));
            row.put("href", rows == 1 && href != null ? href : InboxKinds.landingHref(kind));
            return row;
        }

        private static int rank(List<String> order, String value) {
            int i = order.indexOf(value);
            return i < 0 ? Integer.MAX_VALUE : i;
        }
    }

    private static List<FieldDef> buildCatalog() {
        return List.of(
                new FieldDef("id", "ID", FieldType.STRING, FieldRole.DIMENSION, null, null, null, TABLE_ONLY).notFilterable(),
                new FieldDef("kind", "Kind", FieldType.ENUM, FieldRole.DIMENSION, null, InboxKinds.ALL, null, CHART_TABLE)
                        .withValueLabels(KIND_LABELS),
                new FieldDef("label", "Label", FieldType.STRING, FieldRole.DIMENSION, null, null, null, TABLE_ONLY),
                new FieldDef("severity", "Severity", FieldType.ENUM, FieldRole.DIMENSION, null, SEVERITIES, null, CHART_TABLE),
                new FieldDef("section", "Section", FieldType.ENUM, FieldRole.DIMENSION, null, SECTIONS, null, CHART_TABLE),
                new FieldDef("count", "Count", FieldType.NUMBER, FieldRole.MEASURE, List.of(Aggregation.SUM, Aggregation.COUNT),
                        null, null, KPI_CHART_TABLE, "number"),
                new FieldDef("href", "Link", FieldType.STRING, FieldRole.DIMENSION, null, null, null, TABLE_ONLY).notFilterable()
        );
    }
}
