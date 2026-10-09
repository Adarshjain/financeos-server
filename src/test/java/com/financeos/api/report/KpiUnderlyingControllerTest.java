package com.financeos.api.report;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.financeos.api.dashboard.dto.BuiltinDataRequest;
import com.financeos.api.report.dto.RunReportRequest;
import com.financeos.core.exception.ResourceNotFoundException;
import com.financeos.core.exception.ValidationException;
import com.financeos.domain.dashboard.BuiltinWidgetRegistry;
import com.financeos.domain.report.ReportType;
import com.financeos.domain.report.underlying.KpiUnderlyingResponse;
import com.financeos.domain.report.underlying.KpiUnderlyingService;
import com.financeos.domain.report.underlying.KpiUnderlyingService.CsvTarget;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.mock.web.MockHttpServletResponse;

import java.io.OutputStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/** The KPI underlying-data endpoints hand their parameters to the service; CSV goes out as an attachment. */
class KpiUnderlyingControllerTest {

    private final ObjectMapper mapper = new ObjectMapper();
    private KpiUnderlyingService service;
    private BuiltinWidgetRegistry builtins;
    private KpiUnderlyingController controller;
    private final ArgumentCaptor<CsvTarget> target = ArgumentCaptor.forClass(CsvTarget.class);

    @BeforeEach
    void setUp() {
        service = mock(KpiUnderlyingService.class);
        builtins = new BuiltinWidgetRegistry(mapper);
        controller = new KpiUnderlyingController(service, builtins);
    }

    private RunReportRequest kpiRequest() throws Exception {
        return new RunReportRequest(ReportType.KPI, "transactions",
                mapper.readTree("{\"measure\":\"amount\",\"aggregation\":\"sum\",\"filters\":[]}"));
    }

    private static KpiUnderlyingResponse response() {
        return new KpiUnderlyingResponse("current", "transactions", null, false, null, "amount", "Amount", "sum", "currency",
                BigDecimal.TEN, 0, false, List.of(), List.of(), null, null, List.of(), null, null, null);
    }

    @Test
    void aSavedReportPassesPeriodPagingAndSortThrough() {
        UUID id = UUID.randomUUID();
        KpiUnderlyingResponse body = response();
        when(service.saved(id, "previous", 2, 25, "amount,desc")).thenReturn(body);

        assertSame(body, controller.savedUnderlying(id, "previous", 2, 25, "amount,desc").getBody());
    }

    @Test
    void anAdHocDefinitionPassesItsTypeDatasourceAndDefinitionThrough() throws Exception {
        RunReportRequest request = kpiRequest();
        KpiUnderlyingResponse body = response();
        when(service.adHoc(ReportType.KPI, "transactions", request.definition(), null, 0, 50, null)).thenReturn(body);

        assertSame(body, controller.adHocUnderlying(request, null, 0, 50, null).getBody());
    }

    @Test
    void aBuiltinRunsItsTemplateWithTheWidgetsParams() {
        KpiUnderlyingResponse body = response();
        BuiltinWidgetRegistry.Entry netWorth = builtins.require(BuiltinWidgetRegistry.NET_WORTH);
        JsonNode definition = builtins.resolveDefinition(netWorth, null);
        when(service.adHoc(ReportType.KPI, "net_worth", definition, "current", null, null, "name,asc")).thenReturn(body);

        assertSame(body, controller.builtinUnderlying(BuiltinWidgetRegistry.NET_WORTH, new BuiltinDataRequest(null),
                "current", null, null, "name,asc").getBody());
        assertSame(body, controller.builtinUnderlying(BuiltinWidgetRegistry.NET_WORTH, null,
                "current", null, null, "name,asc").getBody());
    }

    @Test
    void anUnknownBuiltinIsA404AndAComponentBuiltinA400() {
        assertThrows(ResourceNotFoundException.class,
                () -> controller.builtinUnderlying("nope", null, null, null, null, null));
        assertThrows(ValidationException.class,
                () -> controller.builtinUnderlying(BuiltinWidgetRegistry.ATTENTION, null, null, null, null, null));
        assertThrows(ValidationException.class, () -> controller.builtinUnderlyingCsv(BuiltinWidgetRegistry.ATTENTION,
                null, null, null, new MockHttpServletResponse()));
        verifyNoInteractions(service);
    }

    @Test
    void theSavedCsvIsAnAttachmentWrittenToTheResponse() throws Exception {
        UUID id = UUID.randomUUID();
        MockHttpServletResponse response = new MockHttpServletResponse();

        controller.savedUnderlyingCsv(id, "previous", "date,asc", response);

        verify(service).savedCsv(eq(id), eq("previous"), eq("date,asc"), target.capture());
        assertNull(response.getContentType(), "nothing is set until the export starts writing");
        writeThrough(target.getValue(), response);
    }

    @Test
    void theAdHocCsvIsAnAttachmentWrittenToTheResponse() throws Exception {
        RunReportRequest request = kpiRequest();
        MockHttpServletResponse response = new MockHttpServletResponse();

        controller.adHocUnderlyingCsv(request, null, null, response);

        verify(service).adHocCsv(eq(ReportType.KPI), eq("transactions"), eq(request.definition()), eq(null), eq(null),
                target.capture());
        writeThrough(target.getValue(), response);
    }

    @Test
    void theBuiltinCsvRunsItsTemplate() throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();
        JsonNode definition = builtins.resolveDefinition(builtins.require(BuiltinWidgetRegistry.NET_WORTH), null);

        controller.builtinUnderlyingCsv(BuiltinWidgetRegistry.NET_WORTH, null, "current", null, response);

        verify(service).adHocCsv(eq(ReportType.KPI), eq("net_worth"), eq(definition), eq("current"), eq(null),
                target.capture());
        writeThrough(target.getValue(), response);
    }

    private static void writeThrough(CsvTarget target, MockHttpServletResponse response) throws Exception {
        OutputStream out = target.open();
        out.write("a,b\r\n".getBytes(StandardCharsets.UTF_8));
        out.flush();

        assertEquals("text/csv;charset=UTF-8", response.getContentType().replace(" ", ""));
        assertEquals("attachment; filename=\"underlying.csv\"", response.getHeader("Content-Disposition"));
        assertEquals("a,b\r\n", response.getContentAsString(StandardCharsets.UTF_8));
    }
}
