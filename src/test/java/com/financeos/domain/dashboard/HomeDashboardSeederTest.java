package com.financeos.domain.dashboard;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.financeos.api.dashboard.dto.CreateDashboardRequest;
import com.financeos.api.dashboard.dto.DashboardResponse;
import com.financeos.api.report.dto.CreateReportRequest;
import com.financeos.core.exception.ValidationException;
import com.financeos.domain.report.Report;
import com.financeos.domain.report.ReportDefinitionValidator;
import com.financeos.domain.report.ReportRepository;
import com.financeos.domain.report.ReportService;
import com.financeos.domain.report.ReportType;
import com.financeos.domain.report.datasource.DatasourceCatalog;
import com.financeos.domain.report.datasource.DatasourceCatalog.FieldDef;
import com.financeos.domain.report.datasource.DatasourceRegistry;
import com.financeos.domain.report.datasource.FieldRole;
import com.financeos.domain.report.datasource.FieldType;
import com.financeos.domain.report.datasource.ReportDatasource;
import com.financeos.domain.report.datasource.impl.TransactionsDatasource;
import com.financeos.domain.report.definition.ReportDefinition;
import com.financeos.domain.report.definition.ReportDefinitions;
import com.financeos.domain.report.engine.DateRangeResolver;
import com.financeos.domain.report.engine.SqlPredicates;
import com.financeos.domain.user.UserRepository;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class HomeDashboardSeederTest {

    private final ObjectMapper mapper = new ObjectMapper();
    private final UUID userId = UUID.randomUUID();
    private final UUID createdReportId = UUID.randomUUID();

    private UserRepository userRepository;
    private DashboardService dashboardService;
    private ReportService reportService;
    private ReportRepository reportRepository;
    private DatasourceRegistry realRegistry;
    private ReportDefinitionValidator realValidator;
    private DashboardResponse created;

    @BeforeEach
    void setUp() {
        userRepository = mock(UserRepository.class);
        dashboardService = mock(DashboardService.class);
        reportService = mock(ReportService.class);
        reportRepository = mock(ReportRepository.class);
        DateRangeResolver resolver = new DateRangeResolver(4);
        realRegistry = new DatasourceRegistry(List.of(
                new TransactionsDatasource(new SqlPredicates(resolver), resolver)), new DatasourceCatalog());
        realValidator = new ReportDefinitionValidator(realRegistry);

        created = new DashboardResponse(UUID.randomUUID(), "Home", "Your money at a glance", true, List.of(),
                Instant.now(), Instant.now());
        when(dashboardService.create(any())).thenReturn(created);
        when(reportRepository.findFirstByUser_IdAndNameAndDatasourceOrderByCreatedAtAsc(any(), anyString(), anyString()))
                .thenReturn(Optional.empty());
        when(reportService.create(any())).thenAnswer(inv -> {
            Report r = new Report(null, "Spend this month", ReportType.CHART, "transactions", "{}");
            r.setId(createdReportId);
            return r;
        });
    }

    private HomeDashboardSeeder seeder(DatasourceRegistry registry, ReportDefinitionValidator validator) {
        return new HomeDashboardSeeder(userRepository, dashboardService, reportService, reportRepository,
                validator, registry, mapper);
    }

    private HomeDashboardSeeder realSeeder() {
        return seeder(realRegistry, realValidator);
    }

    private CreateDashboardRequest capturedDashboard() {
        ArgumentCaptor<CreateDashboardRequest> captor = ArgumentCaptor.forClass(CreateDashboardRequest.class);
        verify(dashboardService).create(captor.capture());
        return captor.getValue();
    }

    private CreateReportRequest capturedReport() {
        ArgumentCaptor<CreateReportRequest> captor = ArgumentCaptor.forClass(CreateReportRequest.class);
        verify(reportService).create(captor.capture());
        return captor.getValue();
    }

    private static List<String> filterFields(JsonNode definition) {
        List<String> fields = new ArrayList<>();
        definition.get("filters").forEach(f -> fields.add(f.get("field").asText()));
        return fields;
    }

    private static void assertWidget(DashboardWidget w, String key, int x, int y, int width, int height) {
        assertEquals(key, w.id());
        assertEquals(DashboardWidget.KIND_BUILTIN, w.kind());
        assertEquals(key, w.builtinKey());
        assertNull(w.reportId());
        assertNull(w.title());
        assertEquals(new WidgetLayout(x, y, width, height), w.layout());
    }

    // ------------------------------------------------------------------ seedIfNeeded

    @Test
    void seedIfNeededIgnoresANullUser() {
        realSeeder().seedIfNeeded(null);
        verifyNoInteractions(userRepository, dashboardService, reportService, reportRepository);
    }

    @Test
    void seedIfNeededDoesNothingWhenTheUserWasAlreadySeeded() {
        when(userRepository.markHomeSeeded(eq(userId), any())).thenReturn(0);

        realSeeder().seedIfNeeded(userId);

        verify(userRepository).markHomeSeeded(eq(userId), any(Instant.class));
        verifyNoInteractions(dashboardService, reportService, reportRepository);
    }

    @Test
    void seedIfNeededSeedsHomeAsTheDefaultWhenTheClaimSucceeds() {
        when(userRepository.markHomeSeeded(eq(userId), any())).thenReturn(1);

        realSeeder().seedIfNeeded(userId);

        CreateDashboardRequest req = capturedDashboard();
        assertEquals("Home", req.name());
        assertEquals("Your money at a glance", req.description());
        assertTrue(req.isDefault());
    }

    @Test
    void seededLayoutIsTheFourBuiltinsThenTheSpendReport() {
        when(userRepository.markHomeSeeded(eq(userId), any())).thenReturn(1);

        realSeeder().seedIfNeeded(userId);

        List<DashboardWidget> widgets = capturedDashboard().widgets();
        assertEquals(5, widgets.size());
        assertWidget(widgets.get(0), "net_worth", 0, 0, 50, 16);
        assertNull(widgets.get(0).params());
        assertWidget(widgets.get(1), "attention", 50, 0, 50, 16);
        assertNull(widgets.get(1).params());
        assertWidget(widgets.get(2), "bills_due", 0, 16, 100, 22);
        assertNull(widgets.get(2).params());
        assertWidget(widgets.get(3), "upcoming", 0, 38, 100, 22);
        assertEquals(mapper.createObjectNode().put("days", 14), widgets.get(3).params());

        DashboardWidget spend = widgets.get(4);
        assertEquals("spend_this_month", spend.id());
        assertEquals(DashboardWidget.KIND_REPORT, spend.kind());
        assertEquals(createdReportId, spend.reportId());
        assertEquals("Spend this month", spend.title());
        assertEquals(new WidgetLayout(0, 60, 100, 28), spend.layout());
    }

    @Test
    void seededWidgetsPassTheDashboardValidator() {
        when(userRepository.markHomeSeeded(eq(userId), any())).thenReturn(1);

        realSeeder().seedIfNeeded(userId);

        CreateDashboardRequest req = capturedDashboard();
        assertDoesNotThrow(() -> new DashboardValidator(new BuiltinWidgetRegistry(mapper)).validate(req.name(), req.widgets()));
    }

    // ------------------------------------------------------------------ spend report

    @Test
    void spendReportIsAThisMonthDebitBarChartBySpendPerCategory() {
        when(userRepository.markHomeSeeded(eq(userId), any())).thenReturn(1);

        realSeeder().seedIfNeeded(userId);

        CreateReportRequest req = capturedReport();
        assertEquals("Spend this month", req.name());
        assertEquals("Debits this month, by category", req.description());
        assertEquals(ReportType.CHART, req.type());
        assertEquals("transactions", req.datasource());

        JsonNode def = req.definition();
        assertEquals("bar", def.get("chartType").asText());
        assertEquals("category", def.get("dimension").get("field").asText());
        assertEquals("spend", def.get("measure").get("field").asText());
        assertEquals("sum", def.get("measure").get("aggregation").asText());

        JsonNode filters = def.get("filters");
        assertEquals(List.of("date", "type", "isExcluded", "isTransferLeg"), filterFields(def));
        assertEquals("this_month", filters.get(0).get("operator").asText());
        assertTrue(filters.get(0).get("value") == null || filters.get(0).get("value").isNull());
        assertEquals("is", filters.get(1).get("operator").asText());
        assertEquals("DEBIT", filters.get(1).get("value").asText());
        assertEquals("is", filters.get(2).get("operator").asText());
        assertTrue(filters.get(2).get("value").isBoolean());
        assertEquals(false, filters.get(2).get("value").asBoolean());
        assertEquals("is", filters.get(3).get("operator").asText());
        assertTrue(filters.get(3).get("value").isBoolean());
        assertEquals(false, filters.get(3).get("value").asBoolean());

        ReportDefinition parsed = ReportDefinitions.parse(ReportType.CHART, def, mapper);
        assertDoesNotThrow(() -> realValidator.validate("transactions", parsed));
    }

    @Test
    void anExistingSpendReportIsReusedInsteadOfCreated() {
        UUID existingId = UUID.randomUUID();
        Report existing = new Report(null, "Spend this month", ReportType.CHART, "transactions", "{}");
        existing.setId(existingId);
        when(reportRepository.findFirstByUser_IdAndNameAndDatasourceOrderByCreatedAtAsc(
                userId, "Spend this month", "transactions")).thenReturn(Optional.of(existing));
        DatasourceRegistry registry = mock(DatasourceRegistry.class);

        seeder(registry, realValidator).restore(userId);

        verify(reportService, never()).create(any());
        verifyNoInteractions(registry);
        DashboardWidget spend = capturedDashboard().widgets().get(4);
        assertEquals(existingId, spend.reportId());
    }

    @Test
    void restoreAfterAnEarlierSeedReusesTheReportFromThatSeed() {
        Report first = new Report(null, "Spend this month", ReportType.CHART, "transactions", "{}");
        first.setId(createdReportId);
        when(reportRepository.findFirstByUser_IdAndNameAndDatasourceOrderByCreatedAtAsc(
                userId, "Spend this month", "transactions")).thenReturn(Optional.empty(), Optional.of(first));
        HomeDashboardSeeder seeder = realSeeder();

        seeder.restore(userId);
        seeder.restore(userId);

        verify(reportService, times(1)).create(any());
        ArgumentCaptor<CreateDashboardRequest> captor = ArgumentCaptor.forClass(CreateDashboardRequest.class);
        verify(dashboardService, times(2)).create(captor.capture());
        assertEquals(createdReportId, captor.getAllValues().get(0).widgets().get(4).reportId());
        assertEquals(createdReportId, captor.getAllValues().get(1).widgets().get(4).reportId());
    }

    @Test
    void unknownTransactionsDatasourceSkipsTheReportButSeedsTheBuiltins() {
        when(userRepository.markHomeSeeded(eq(userId), any())).thenReturn(1);
        DatasourceRegistry empty = new DatasourceRegistry(List.of(), new DatasourceCatalog());

        seeder(empty, new ReportDefinitionValidator(empty)).seedIfNeeded(userId);

        verify(reportService, never()).create(any());
        List<DashboardWidget> widgets = capturedDashboard().widgets();
        assertEquals(List.of("net_worth", "attention", "bills_due", "upcoming"),
                widgets.stream().map(DashboardWidget::id).toList());
    }

    @Test
    void anInvalidDefinitionSkipsTheReportButSeedsTheBuiltins() {
        when(userRepository.markHomeSeeded(eq(userId), any())).thenReturn(1);
        ReportDefinitionValidator rejecting = mock(ReportDefinitionValidator.class);
        doThrow(new ValidationException("Unknown field 'spend'")).when(rejecting).validate(anyString(), any());

        seeder(realRegistry, rejecting).seedIfNeeded(userId);

        verify(reportService, never()).create(any());
        assertEquals(4, capturedDashboard().widgets().size());
        assertTrue(capturedDashboard().isDefault());
    }

    @Test
    void anUnparseableDefinitionSkipsTheReportButSeedsTheBuiltins() {
        when(userRepository.markHomeSeeded(eq(userId), any())).thenReturn(1);
        ReportDefinitionValidator rejecting = mock(ReportDefinitionValidator.class);
        doThrow(new IllegalArgumentException("bad definition")).when(rejecting).validate(anyString(), any());

        seeder(realRegistry, rejecting).seedIfNeeded(userId);

        verify(reportService, never()).create(any());
        assertEquals(4, capturedDashboard().widgets().size());
    }

    @Test
    void filtersAreSkippedWhenTheirFieldsAreMissing() {
        ReportDatasource bare = mock(ReportDatasource.class);
        when(bare.field(anyString())).thenReturn(null);
        DatasourceRegistry registry = mock(DatasourceRegistry.class);
        when(registry.isKnown("transactions")).thenReturn(true);
        when(registry.byName("transactions")).thenReturn(bare);

        seeder(registry, mock(ReportDefinitionValidator.class)).restore(userId);

        assertEquals(List.of("date"), filterFields(capturedReport().definition()));
    }

    @Test
    void filtersAreSkippedWhenTheirFieldsAreIneligible() {
        // type without DEBIT, isExcluded not a boolean, isTransferLeg not filterable
        ReportDatasource odd = mock(ReportDatasource.class);
        when(odd.field("type")).thenReturn(new FieldDef("type", "Type", FieldType.ENUM, FieldRole.DIMENSION,
                null, List.of("CREDIT"), null, List.of()));
        when(odd.field("isExcluded")).thenReturn(new FieldDef("isExcluded", "Ex", FieldType.STRING, FieldRole.FILTER,
                null, null, null, List.of()));
        when(odd.field("isTransferLeg")).thenReturn(new FieldDef("isTransferLeg", "Leg", FieldType.BOOLEAN,
                FieldRole.FILTER, null, null, null, List.of()).notFilterable());
        DatasourceRegistry registry = mock(DatasourceRegistry.class);
        when(registry.isKnown("transactions")).thenReturn(true);
        when(registry.byName("transactions")).thenReturn(odd);

        seeder(registry, mock(ReportDefinitionValidator.class)).restore(userId);

        assertEquals(List.of("date"), filterFields(capturedReport().definition()));
    }

    @Test
    void typeFilterIsSkippedWhenTheTypeFieldHasNoStaticValues() {
        ReportDatasource dynamicType = mock(ReportDatasource.class);
        when(dynamicType.field("type")).thenReturn(new FieldDef("type", "Type", FieldType.ENUM, FieldRole.DIMENSION,
                null, null, true, List.of()));
        when(dynamicType.field("isExcluded")).thenReturn(new FieldDef("isExcluded", "Ex", FieldType.BOOLEAN,
                FieldRole.FILTER, null, null, null, List.of()));
        DatasourceRegistry registry = mock(DatasourceRegistry.class);
        when(registry.isKnown("transactions")).thenReturn(true);
        when(registry.byName("transactions")).thenReturn(dynamicType);

        seeder(registry, mock(ReportDefinitionValidator.class)).restore(userId);

        assertEquals(List.of("date", "isExcluded"), filterFields(capturedReport().definition()));
    }

    // ------------------------------------------------------------------ restore

    @Test
    void restoreAlwaysSeedsEvenWhenAlreadySeeded() {
        when(userRepository.markHomeSeeded(eq(userId), any())).thenReturn(0);

        DashboardResponse response = realSeeder().restore(userId);

        assertSame(created, response);
        verify(userRepository).markHomeSeeded(eq(userId), any(Instant.class));
        CreateDashboardRequest req = capturedDashboard();
        assertEquals("Home", req.name());
        assertTrue(req.isDefault());
        assertEquals(5, req.widgets().size());
    }

    @Test
    void restoreSeedsAFirstTimeUserToo() {
        when(userRepository.markHomeSeeded(eq(userId), any())).thenReturn(1);

        assertSame(created, realSeeder().restore(userId));

        verify(dashboardService, times(1)).create(any());
    }
}
