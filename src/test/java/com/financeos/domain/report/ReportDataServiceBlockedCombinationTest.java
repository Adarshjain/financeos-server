package com.financeos.domain.report;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.financeos.domain.report.ReportDataService.ResolvedDefinition;
import com.financeos.domain.report.datasource.DatasourceRegistry;
import com.financeos.domain.report.datasource.ReportDatasource;
import com.financeos.domain.report.definition.KpiDefinition;
import com.financeos.domain.report.engine.ChartReportExecutor;
import com.financeos.domain.report.engine.InMemoryReportExecutor;
import com.financeos.domain.report.engine.KpiReportExecutor;
import com.financeos.domain.report.engine.TableReportExecutor;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** Runs never enforce combination guards: a saved report using a blocked combination still resolves. */
class ReportDataServiceBlockedCombinationTest {

    @Test
    void aSavedReportWithABlockedCombinationStillResolves() {
        ReportService reportService = mock(ReportService.class);
        DatasourceRegistry registry = mock(DatasourceRegistry.class);
        when(registry.byName("reward_caps")).thenReturn(mock(ReportDatasource.class));
        ReportDataService service = new ReportDataService(reportService, mock(ReportDefinitionValidator.class),
                registry, new ObjectMapper(), mock(KpiReportExecutor.class), mock(ChartReportExecutor.class),
                mock(TableReportExecutor.class), mock(InMemoryReportExecutor.class));
        UUID id = UUID.randomUUID();
        when(reportService.get(id)).thenReturn(new Report(null, "Caps", ReportType.KPI, "reward_caps",
                "{\"measure\":\"capLimit\",\"aggregation\":\"sum\",\"filters\":[]}"));

        ResolvedDefinition resolved = assertDoesNotThrow(() -> service.resolveSaved(id));

        assertInstanceOf(KpiDefinition.class, resolved.definition());
    }
}
