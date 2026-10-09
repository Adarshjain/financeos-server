package com.financeos.domain.report;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.financeos.core.exception.ResourceNotFoundException;
import com.financeos.core.exception.ValidationException;
import com.financeos.domain.report.ReportDataService.ResolvedDefinition;
import com.financeos.domain.report.datasource.Aggregation;
import com.financeos.domain.report.datasource.DatasourceRegistry;
import com.financeos.domain.report.datasource.ReportDatasource;
import com.financeos.domain.report.definition.KpiDefinition;
import com.financeos.domain.report.engine.ChartReportExecutor;
import com.financeos.domain.report.engine.InMemoryReportExecutor;
import com.financeos.domain.report.engine.KpiReportExecutor;
import com.financeos.domain.report.engine.TableReportExecutor;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/** Resolving a report definition without running it (used by the KPI underlying data). */
class ReportDataServiceResolveTest {

    private static final String KPI = "{\"measure\":\"amount\",\"aggregation\":\"sum\",\"filters\":[]}";

    private final ObjectMapper mapper = new ObjectMapper();
    private ReportService reportService;
    private ReportDefinitionValidator validator;
    private ReportDatasource datasource;
    private ReportDataService service;

    @BeforeEach
    void setUp() {
        reportService = mock(ReportService.class);
        validator = mock(ReportDefinitionValidator.class);
        DatasourceRegistry registry = mock(DatasourceRegistry.class);
        datasource = mock(ReportDatasource.class);
        when(registry.byName("transactions")).thenReturn(datasource);
        service = new ReportDataService(reportService, validator, registry, mapper, mock(KpiReportExecutor.class),
                mock(ChartReportExecutor.class), mock(TableReportExecutor.class), mock(InMemoryReportExecutor.class));
    }

    @Test
    void aSavedReportIsLoadedThroughOwnershipParsedAndValidated() {
        UUID id = UUID.randomUUID();
        when(reportService.get(id)).thenReturn(new Report(null, "Spend", ReportType.KPI, "transactions", KPI));

        ResolvedDefinition resolved = service.resolveSaved(id);

        assertSame(datasource, resolved.datasource());
        assertEquals(new KpiDefinition("amount", Aggregation.SUM, List.of(), null), resolved.definition());
        verify(validator).validate("transactions", resolved.definition());
    }

    @Test
    void aMissingOrForeignSavedReportFailsAsTheReportServiceDecides() {
        UUID id = UUID.randomUUID();
        when(reportService.get(id)).thenThrow(new ResourceNotFoundException("Report", id));

        assertThrows(ResourceNotFoundException.class, () -> service.resolveSaved(id));
        verifyNoInteractions(validator);
    }

    @Test
    void aDefinitionIsParsedAsItsTypeAndValidated() throws Exception {
        ResolvedDefinition resolved = service.resolveDefinition(ReportType.KPI, "transactions", mapper.readTree(KPI));

        assertSame(datasource, resolved.datasource());
        assertEquals(new KpiDefinition("amount", Aggregation.SUM, List.of(), null), resolved.definition());
        verify(validator).validate("transactions", resolved.definition());
    }

    @Test
    void aMissingTypeIsA400() throws Exception {
        ValidationException e = assertThrows(ValidationException.class,
                () -> service.resolveDefinition(null, "transactions", mapper.readTree(KPI)));

        assertEquals("type is required", e.getMessage());
    }

    @Test
    void anInvalidDefinitionIsTheValidatorsError() throws Exception {
        doThrow(new ValidationException("Unknown field: amount")).when(validator).validate(eq("transactions"), any());

        assertThrows(ValidationException.class,
                () -> service.resolveDefinition(ReportType.KPI, "transactions", mapper.readTree(KPI)));
    }
}
