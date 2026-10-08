package com.financeos.domain.report.datasource.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.financeos.api.inbox.dto.InboxItemResponse;
import com.financeos.api.inbox.dto.InboxRefsResponse;
import com.financeos.api.inbox.dto.InboxResponse;
import com.financeos.api.inbox.dto.InboxSummaryResponse;
import com.financeos.core.security.UserContext;
import com.financeos.domain.inbox.InboxKinds;
import com.financeos.domain.inbox.InboxService;
import com.financeos.domain.inbox.collect.InboxRows;
import com.financeos.domain.report.ReportType;
import com.financeos.domain.report.datasource.Aggregation;
import com.financeos.domain.report.datasource.DatasourceCatalog.FieldDef;
import com.financeos.domain.report.datasource.FieldRole;
import com.financeos.domain.report.datasource.FieldType;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class AttentionDatasourceTest {

    private static final String ACT = InboxItemResponse.SECTION_ACT_NOW;
    private static final String LOOK = InboxItemResponse.SECTION_NEEDS_LOOK;
    private static final String INFO = InboxItemResponse.SECTION_INFO;
    private static final String CRIT = InboxItemResponse.SEVERITY_CRITICAL;
    private static final String WARN = InboxItemResponse.SEVERITY_WARNING;
    private static final String SEV_INFO = InboxItemResponse.SEVERITY_INFO;

    private final UUID userId = UUID.randomUUID();
    private InboxService inboxService;
    private AttentionDatasource datasource;

    @BeforeEach
    void setUp() {
        inboxService = mock(InboxService.class);
        datasource = new AttentionDatasource(inboxService);
        UserContext.setCurrentUserId(userId);
    }

    @AfterEach
    void tearDown() {
        UserContext.clear();
    }

    private void inbox(InboxItemResponse... items) {
        when(inboxService.list(userId)).thenReturn(new InboxResponse(List.of(items), InboxSummaryResponse.EMPTY, Instant.now()));
    }

    private static InboxItemResponse item(String kind, String key, String section, String severity, String href) {
        return InboxRows.item(key, kind, severity, section, "t", null, href, null, LocalDate.of(2026, 10, 8), List.of(),
                InboxRefsResponse.NONE);
    }

    private static InboxItemResponse summary(String kind, String key, String section, String severity, Integer count) {
        return new InboxItemResponse(key, kind, InboxItemResponse.ROW_SUMMARY, severity, section, "t", null, "/summary-href",
                null, null, count, List.of(), null, InboxRefsResponse.NONE);
    }

    @Test
    void identity() {
        assertEquals("attention", datasource.name());
        assertEquals("Needs attention", datasource.label());
    }

    @Test
    void noSignedInUserMeansNoRowsAndNoInboxRead() {
        UserContext.clear();
        assertTrue(datasource.rows().isEmpty());
        verifyNoInteractions(inboxService);
    }

    @Test
    void anEmptyInboxHasNoRows() {
        inbox();
        assertTrue(datasource.rows().isEmpty());
    }

    @Test
    void oneRowPerKindInTheInboxsOrder() {
        inbox(item(InboxKinds.BILL, "bill:1", ACT, CRIT, "/b1"),
                item(InboxKinds.EMI, "emi:1:1", ACT, WARN, "/e1"),
                item(InboxKinds.BILL, "bill:2", LOOK, WARN, "/b2"),
                item(InboxKinds.JOB, "job:1", INFO, SEV_INFO, "/j1"));

        List<Map<String, Object>> rows = datasource.rows();

        assertEquals(List.of("bill", "emi", "job"), rows.stream().map(r -> r.get("kind")).toList());
    }

    @Test
    void aLoneItemRowKeepsItsOwnFieldsAndLink() {
        inbox(item(InboxKinds.EMI, "emi:1:1", LOOK, WARN, "/loans/1?installment=1"));

        Map<String, Object> row = datasource.rows().get(0);

        assertEquals("emi", row.get("id"));
        assertEquals("emi", row.get("kind"));
        assertEquals("EMIs", row.get("label"));
        assertEquals("warning", row.get("severity"));
        assertEquals("needs_look", row.get("section"));
        assertEquals(BigDecimal.ONE, row.get("count"));
        assertEquals("/loans/1?installment=1", row.get("href"));
        assertEquals(List.of("id", "kind", "label", "severity", "section", "count", "href"), List.copyOf(row.keySet()));
    }

    @Test
    void aLoneRowWithNoLinkLandsOnTheKindsPage() {
        inbox(item(InboxKinds.LENDING, "lending:1", INFO, SEV_INFO, null));
        assertEquals("/loans/lendings", datasource.rows().get(0).get("href"));
    }

    @Test
    void severalRowsOfAKindLandOnTheKindsPage() {
        inbox(item(InboxKinds.BILL, "bill:1", ACT, CRIT, "/b1"), item(InboxKinds.BILL, "bill:2", ACT, CRIT, "/b2"));
        assertEquals("/upcoming", datasource.rows().get(0).get("href"));
    }

    @Test
    void theWorstSeverityAndMostUrgentSectionWin() {
        inbox(item(InboxKinds.BILL, "bill:1", INFO, SEV_INFO, "/b1"),
                item(InboxKinds.BILL, "bill:2", ACT, WARN, "/b2"),
                item(InboxKinds.BILL, "bill:3", LOOK, CRIT, "/b3"));

        Map<String, Object> row = datasource.rows().get(0);

        assertEquals("critical", row.get("severity"));
        assertEquals("act_now", row.get("section"));
        assertEquals(new BigDecimal(3), row.get("count"));
    }

    @Test
    void itemRowsCountOneEach() {
        inbox(item(InboxKinds.JOB, "job:1", INFO, SEV_INFO, "/a"),
                item(InboxKinds.JOB, "job:2", INFO, SEV_INFO, "/b"));
        assertEquals(new BigDecimal(2), datasource.rows().get(0).get("count"));
    }

    @Test
    void aSummaryRowCountsTheThingsBehindIt() {
        inbox(summary(InboxKinds.REVIEW, InboxKinds.KEY_REVIEW, LOOK, WARN, 17));

        Map<String, Object> row = datasource.rows().get(0);

        assertEquals(new BigDecimal(17), row.get("count"));
        assertEquals("/summary-href", row.get("href"));
        assertEquals("Transactions to review", row.get("label"));
    }

    @Test
    void aSummaryRowWithNoCountCountsAsOne() {
        inbox(summary(InboxKinds.GMAIL_ATTENTION, InboxKinds.KEY_GMAIL_ATTENTION, LOOK, WARN, null));
        assertEquals(BigDecimal.ONE, datasource.rows().get(0).get("count"));
    }

    @Test
    void summaryAndItemRowsOfOneKindAddUp() {
        inbox(summary(InboxKinds.GMAIL_ATTENTION, InboxKinds.KEY_GMAIL_ATTENTION, LOOK, WARN, 4),
                item(InboxKinds.GMAIL_ATTENTION, "x", LOOK, WARN, "/x"));

        Map<String, Object> row = datasource.rows().get(0);

        assertEquals(new BigDecimal(5), row.get("count"));
        assertEquals("/settings/gmail?focus=attention", row.get("href"));
    }

    @Test
    void anUnknownSeverityOrSectionFallsBackToInfo() {
        inbox(item(InboxKinds.JOB, "job:1", "elsewhere", "loud", "/j"));

        Map<String, Object> row = datasource.rows().get(0);

        assertEquals("info", row.get("severity"));
        assertEquals("info", row.get("section"));
    }

    @Test
    void catalogDescribesEveryRowField() {
        Map<String, FieldDef> fields = datasource.fields().stream().collect(Collectors.toMap(FieldDef::name, Function.identity()));
        assertEquals(List.of("id", "kind", "label", "severity", "section", "count", "href"),
                datasource.fields().stream().map(FieldDef::name).toList());

        assertEquals(FieldType.ENUM, fields.get("kind").type());
        assertEquals(InboxKinds.ALL, fields.get("kind").values());
        assertEquals(List.of("critical", "warning", "info"), fields.get("severity").values());
        assertEquals(List.of("act_now", "needs_look", "info"), fields.get("section").values());

        FieldDef count = fields.get("count");
        assertEquals(FieldRole.MEASURE, count.role());
        assertEquals(FieldType.NUMBER, count.type());
        assertEquals(List.of(Aggregation.SUM, Aggregation.COUNT), count.aggregations());
        assertEquals(List.of(ReportType.KPI, ReportType.CHART, ReportType.TABLE), count.allowedInReports());
        assertEquals("number", count.format());

        assertEquals(Boolean.FALSE, fields.get("id").filterable());
        assertEquals(Boolean.FALSE, fields.get("href").filterable());
        assertNull(fields.get("kind").filterable());
        assertNull(fields.get("severity").filterable());
        assertNull(fields.get("section").filterable());
        assertNull(fields.get("label").filterable());
        assertEquals(List.of(ReportType.TABLE), fields.get("id").allowedInReports());
        assertEquals(List.of(ReportType.TABLE), fields.get("label").allowedInReports());
        assertEquals(List.of(ReportType.TABLE), fields.get("href").allowedInReports());
        assertEquals(List.of(ReportType.CHART, ReportType.TABLE), fields.get("kind").allowedInReports());
        assertFalse(fields.get("kind").role() == FieldRole.MEASURE);
    }
}
