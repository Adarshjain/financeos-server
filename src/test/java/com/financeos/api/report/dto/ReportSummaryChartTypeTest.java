package com.financeos.api.report.dto;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.financeos.domain.report.Report;
import com.financeos.domain.report.ReportType;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class ReportSummaryChartTypeTest {

    private final ObjectMapper mapper = new ObjectMapper();

    private String chartType(ReportType type, String definition) {
        Report r = new Report(null, "r", type, "transactions", definition);
        return ReportSummaryResponse.from(r, List.of(), mapper).chartType();
    }

    @Test
    void chartReportsExposeWireValue() {
        assertEquals("bar", chartType(ReportType.CHART, "{\"chartType\":\"bar\"}"));
        assertEquals("donut", chartType(ReportType.CHART, "{\"chartType\":\"donut\"}"));
        assertEquals("stackedBar", chartType(ReportType.CHART, "{\"chartType\":\"stackedBar\"}"));
        assertEquals("breakdown", chartType(ReportType.CHART, "{\"chartType\":\"breakdown\"}"));
    }

    @Test
    void nonChartReportsAreNull() {
        assertNull(chartType(ReportType.KPI, "{\"chartType\":\"bar\"}"));
        assertNull(chartType(ReportType.TABLE, "{}"));
    }

    @Test
    void malformedOrUnknownIsNull() {
        assertNull(chartType(ReportType.CHART, "{not json"));
        assertNull(chartType(ReportType.CHART, "{}"));
        assertNull(chartType(ReportType.CHART, "{\"chartType\":\"nope\"}"));
        assertNull(chartType(ReportType.CHART, null));
    }
}
