package com.financeos.domain.dashboard;

import static net.logstash.logback.argument.StructuredArguments.keyValue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.financeos.api.dashboard.dto.CreateDashboardRequest;
import com.financeos.api.dashboard.dto.DashboardResponse;
import com.financeos.api.report.dto.CreateReportRequest;
import com.financeos.core.exception.ValidationException;
import com.financeos.domain.report.Report;
import com.financeos.domain.report.ReportDefinitionValidator;
import com.financeos.domain.report.ReportRepository;
import com.financeos.domain.report.ReportService;
import com.financeos.domain.report.ReportType;
import com.financeos.domain.report.datasource.Aggregation;
import com.financeos.domain.report.datasource.DatasourceCatalog;
import com.financeos.domain.report.datasource.DatasourceCatalog.FieldDef;
import com.financeos.domain.report.datasource.DatasourceRegistry;
import com.financeos.domain.report.datasource.FieldType;
import com.financeos.domain.report.datasource.ReportDatasource;
import com.financeos.domain.report.definition.ChartType;
import com.financeos.domain.report.definition.ReportDefinition;
import com.financeos.domain.report.definition.ReportDefinitions;
import com.financeos.domain.user.UserRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Seeds the "Home" dashboard: the four built-ins (net worth, needs attention, bills due,
 * upcoming) plus a "Spend this month" chart report, set as the user's default.
 *
 * <p>{@link #seedIfNeeded} runs once per user: it claims {@code users.home_seeded_at} with a
 * conditional UPDATE, so concurrent first requests seed exactly once and a failed seed rolls the
 * claim back for the next request. {@link #restore} seeds unconditionally (a fresh Home each time).
 */
@Service
@Transactional
@Slf4j
public class HomeDashboardSeeder {

    public static final String HOME_NAME = "Home";
    static final String HOME_DESCRIPTION = "Your money at a glance";
    static final String SPEND_REPORT_NAME = "Spend this month";

    static final String EVENT_HOME_SEEDED = com.financeos.core.observability.Events.DASHBOARD_HOME_SEEDED;
    static final String EVENT_SPEND_REPORT_SKIPPED = com.financeos.core.observability.Events.DASHBOARD_HOME_SPEND_REPORT_SKIPPED;

    private final UserRepository userRepository;
    private final DashboardService dashboardService;
    private final ReportService reportService;
    private final ReportRepository reportRepository;
    private final ReportDefinitionValidator definitionValidator;
    private final DatasourceRegistry datasourceRegistry;
    private final ObjectMapper mapper;

    public HomeDashboardSeeder(UserRepository userRepository, DashboardService dashboardService,
            ReportService reportService, ReportRepository reportRepository,
            ReportDefinitionValidator definitionValidator,
            DatasourceRegistry datasourceRegistry, ObjectMapper mapper) {
        this.userRepository = userRepository;
        this.dashboardService = dashboardService;
        this.reportService = reportService;
        this.reportRepository = reportRepository;
        this.definitionValidator = definitionValidator;
        this.datasourceRegistry = datasourceRegistry;
        this.mapper = mapper;
    }

    /** Seeds the Home dashboard the first time a user is seen; a no-op afterwards. */
    public void seedIfNeeded(UUID userId) {
        if (userId == null) {
            return;
        }
        if (userRepository.markHomeSeeded(userId, Instant.now()) != 1) {
            return;
        }
        seed(userId, "first_visit");
    }

    /** Creates a fresh Home dashboard (same widgets) and makes it the default, every time. */
    public DashboardResponse restore(UUID userId) {
        userRepository.markHomeSeeded(userId, Instant.now());
        return seed(userId, "restore");
    }

    private DashboardResponse seed(UUID userId, String trigger) {
        List<DashboardWidget> widgets = new ArrayList<>();
        widgets.add(builtin(BuiltinWidgetRegistry.NET_WORTH, 0, 0, 50, 16, null));
        widgets.add(builtin(BuiltinWidgetRegistry.ATTENTION, 50, 0, 50, 16, null));
        widgets.add(builtin(BuiltinWidgetRegistry.BILLS_DUE, 0, 16, 100, 22, null));
        widgets.add(builtin(BuiltinWidgetRegistry.UPCOMING, 0, 38, 100, 22,
                mapper.createObjectNode().put("days", BuiltinWidgetRegistry.UPCOMING_DEFAULT_DAYS)));

        UUID spendReportId = createSpendThisMonthReport(userId);
        if (spendReportId != null) {
            widgets.add(new DashboardWidget("spend_this_month", spendReportId, SPEND_REPORT_NAME,
                    new WidgetLayout(0, 60, 100, 28)));
        }

        DashboardResponse home = dashboardService.create(
                new CreateDashboardRequest(HOME_NAME, HOME_DESCRIPTION, true, widgets));
        log.info("Home dashboard seeded",
                keyValue("event", EVENT_HOME_SEEDED),
                keyValue("userId", userId),
                keyValue("dashboardId", home.id()),
                keyValue("trigger", trigger),
                keyValue("widgets", widgets.size()));
        return home;
    }

    private static DashboardWidget builtin(String key, int x, int y, int w, int h, @Nullable JsonNode params) {
        return new DashboardWidget(key, null, null, new WidgetLayout(x, y, w, h),
                DashboardWidget.KIND_BUILTIN, key, params);
    }

    /**
     * A bar chart of this month's debits by category over the transactions datasource, leaving out
     * excluded transactions and transfer legs. A report the user already owns under the same name
     * and datasource (an earlier seed) is reused, so Restore never piles up duplicates. A new
     * definition is validated before anything is persisted; when it cannot be built the report is
     * skipped (with a warning) and the four built-ins still seed.
     */
    @Nullable
    private UUID createSpendThisMonthReport(UUID userId) {
        String datasourceName = DatasourceCatalog.TRANSACTIONS;
        java.util.Optional<Report> existing = reportRepository
                .findFirstByUser_IdAndNameAndDatasourceOrderByCreatedAtAsc(userId, SPEND_REPORT_NAME, datasourceName);
        if (existing.isPresent()) {
            return existing.get().getId();
        }
        if (!datasourceRegistry.isKnown(datasourceName)) {
            log.warn("Skipping the seeded '{}' report: datasource '{}' is not registered",
                    SPEND_REPORT_NAME, datasourceName,
                    keyValue("event", EVENT_SPEND_REPORT_SKIPPED), keyValue("userId", userId));
            return null;
        }
        ReportDatasource transactions = datasourceRegistry.byName(datasourceName);

        ObjectNode definition = mapper.createObjectNode();
        definition.put("chartType", ChartType.BAR.json());
        definition.putObject("dimension").put("field", "category");
        ObjectNode measure = definition.putObject("measure");
        measure.put("field", "spend");
        measure.put("aggregation", Aggregation.SUM.json());
        ArrayNode filters = definition.putArray("filters");
        ObjectNode thisMonth = filters.addObject();
        thisMonth.put("field", "date");
        thisMonth.put("operator", "this_month");
        FieldDef type = transactions.field("type");
        if (type != null && type.values() != null && type.values().contains("DEBIT")) {
            ObjectNode debitsOnly = filters.addObject();
            debitsOnly.put("field", "type");
            debitsOnly.put("operator", "is");
            debitsOnly.put("value", "DEBIT");
        }
        addIsFalseFilter(transactions, filters, "isExcluded");
        addIsFalseFilter(transactions, filters, "isTransferLeg");

        try {
            ReportDefinition parsed = ReportDefinitions.parse(ReportType.CHART, definition, mapper);
            definitionValidator.validate(datasourceName, parsed);
        } catch (ValidationException | IllegalArgumentException e) {
            // Validated here (outside any transactional proxy) so a bad definition never marks the
            // seeding transaction rollback-only.
            log.warn("Skipping the seeded '{}' report: {}", SPEND_REPORT_NAME, e.getMessage(),
                    keyValue("event", EVENT_SPEND_REPORT_SKIPPED), keyValue("userId", userId));
            return null;
        }

        Report report = reportService.create(new CreateReportRequest(SPEND_REPORT_NAME,
                "Debits this month, by category", ReportType.CHART, datasourceName, definition));
        return report.getId();
    }

    /** {@code <field> is false}, only when the datasource exposes it as a filterable boolean. */
    private static void addIsFalseFilter(ReportDatasource datasource, ArrayNode filters, String fieldName) {
        FieldDef field = datasource.field(fieldName);
        if (field == null || field.type() != FieldType.BOOLEAN || !field.canFilter()) {
            return;
        }
        ObjectNode clause = filters.addObject();
        clause.put("field", fieldName);
        clause.put("operator", "is");
        clause.put("value", false);
    }
}
