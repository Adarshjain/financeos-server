package com.financeos.domain.report.datasource.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.financeos.api.obligations.dto.ObligationItemDto;
import com.financeos.api.obligations.dto.ObligationsResponse;
import com.financeos.core.security.UserContext;
import com.financeos.core.time.AppTime;
import com.financeos.domain.lending.LendingDirection;
import com.financeos.domain.obligations.ObligationsService;
import com.financeos.domain.report.ReportType;
import com.financeos.domain.report.datasource.Aggregation;
import com.financeos.domain.report.datasource.ComputedReportDatasource.DateHint;
import com.financeos.domain.report.datasource.DatasourceCatalog.FieldDef;
import com.financeos.domain.report.datasource.FieldRole;
import com.financeos.domain.report.datasource.FieldType;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class ObligationsDatasourceTest {

    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");
    private static final LocalDate TODAY = LocalDate.of(2026, 10, 8);

    private ObligationsService service;
    private ObligationsDatasource datasource;
    private final UUID userId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        AppTime.useClock(Clock.fixed(TODAY.atTime(10, 0).atZone(IST).toInstant(), IST));
        UserContext.setCurrentUserId(userId);
        service = mock(ObligationsService.class);
        when(service.upcoming(eq(userId), anyInt(), eq(ObligationsService.ALL_KINDS)))
                .thenReturn(new ObligationsResponse(List.of()));
        datasource = new ObligationsDatasource(service);
    }

    @AfterEach
    void tearDown() {
        UserContext.clear();
        AppTime.reset();
    }

    // ------------------------------------------------------------------ catalog

    @Test
    void nameLabelAndCatalog() {
        assertEquals("obligations", datasource.name());
        assertEquals("Upcoming obligations", datasource.label());
        assertEquals(List.of("id", "dueDate", "kind", "title", "accountName", "amount", "status", "daysUntil", "href", "refId"),
                datasource.fields().stream().map(FieldDef::name).toList());

        FieldDef dueDate = datasource.field("dueDate");
        assertEquals(FieldType.DATE, dueDate.type());
        assertEquals(List.of("card_bill", "emi", "lending_return", "statement_expected"), datasource.field("kind").values());
        assertEquals(List.of("overdue", "due_soon", "upcoming"), datasource.field("status").values());

        FieldDef amount = datasource.field("amount");
        assertEquals(FieldRole.MEASURE, amount.role());
        assertEquals(List.of(Aggregation.SUM), amount.aggregations());
        assertEquals(List.of(ReportType.KPI, ReportType.CHART, ReportType.TABLE), amount.allowedInReports());
        assertEquals("currency", amount.format());

        assertEquals(List.of(Aggregation.MIN, Aggregation.MAX), datasource.field("daysUntil").aggregations());

        assertFalse(datasource.field("id").canFilter());
        assertFalse(datasource.field("href").canFilter());
        assertFalse(datasource.field("refId").canFilter());
        assertTrue(datasource.field("kind").canFilter());
        assertTrue(datasource.field("dueDate").canFilter());
    }

    // ------------------------------------------------------------------ monthsFor

    @Test
    void noHintMeansTheFullTwelveMonths() {
        assertEquals(12, ObligationsDatasource.monthsFor(null, TODAY));
    }

    @Test
    void aHintOnAnotherFieldIsIgnored() {
        assertEquals(12, ObligationsDatasource.monthsFor(new DateHint("otherDate", TODAY, TODAY.plusDays(3)), TODAY));
    }

    @Test
    void aHintWithoutAnUpperBoundMeansTwelveMonths() {
        assertEquals(12, ObligationsDatasource.monthsFor(new DateHint("dueDate", TODAY, null), TODAY));
    }

    @Test
    void anUpperBoundOnOrBeforeTodayNeedsOneMonth() {
        assertEquals(1, ObligationsDatasource.monthsFor(new DateHint("dueDate", TODAY.minusDays(30), TODAY), TODAY));
        assertEquals(1, ObligationsDatasource.monthsFor(new DateHint("dueDate", TODAY.minusDays(30), TODAY.minusDays(1)), TODAY));
    }

    @Test
    void partialMonthsRoundUp() {
        assertEquals(1, ObligationsDatasource.monthsFor(new DateHint("dueDate", TODAY, TODAY.plusDays(1)), TODAY));
        assertEquals(1, ObligationsDatasource.monthsFor(new DateHint("dueDate", TODAY, TODAY.plusDays(13)), TODAY));
        assertEquals(1, ObligationsDatasource.monthsFor(new DateHint("dueDate", TODAY, TODAY.plusMonths(1)), TODAY));
        assertEquals(2, ObligationsDatasource.monthsFor(new DateHint("dueDate", TODAY, TODAY.plusMonths(1).plusDays(1)), TODAY));
        assertEquals(3, ObligationsDatasource.monthsFor(new DateHint("dueDate", TODAY, TODAY.plusDays(89)), TODAY));
    }

    @Test
    void monthEndTodayStillCoversTheUpperBound() {
        LocalDate jan31 = LocalDate.of(2027, 1, 31);
        assertEquals(1, ObligationsDatasource.monthsFor(new DateHint("dueDate", jan31, LocalDate.of(2027, 2, 28)), jan31));
        assertEquals(2, ObligationsDatasource.monthsFor(new DateHint("dueDate", jan31, LocalDate.of(2027, 3, 1)), jan31));
    }

    @Test
    void farBoundsAreCappedAtTwelveMonths() {
        assertEquals(12, ObligationsDatasource.monthsFor(new DateHint("dueDate", TODAY, TODAY.plusMonths(12)), TODAY));
        assertEquals(12, ObligationsDatasource.monthsFor(new DateHint("dueDate", TODAY, TODAY.plusYears(30)), TODAY));
    }

    // ------------------------------------------------------------------ rows

    @Test
    void rowsAskTheServiceForTheCurrentUserEveryKindAndTheHintedMonths() {
        datasource.rows(new DateHint("dueDate", TODAY, TODAY.plusDays(13)));
        verify(service).upcoming(userId, 1, ObligationsService.ALL_KINDS);
    }

    @Test
    void rowsWithoutAHintComputeTwelveMonths() {
        datasource.rows();
        verify(service).upcoming(userId, 12, ObligationsService.ALL_KINDS);
    }

    @Test
    void cardBillRow() {
        UUID statementId = UUID.randomUUID();
        UUID accountId = UUID.randomUUID();
        Map<String, Object> row = single(new ObligationItemDto("card_bill", TODAY.plusDays(5), new BigDecimal("4200"), "due_soon",
                null, null, null, null, null, null, null,
                "HDFC ••1234 bill", accountId, "HDFC", statementId, "/upcoming?bill=" + statementId, 5L));

        assertEquals("bill:" + statementId, row.get("id"));
        assertEquals(TODAY.plusDays(5), row.get("dueDate"));
        assertEquals("card_bill", row.get("kind"));
        assertEquals("HDFC ••1234 bill", row.get("title"));
        assertEquals("HDFC", row.get("accountName"));
        assertEquals(new BigDecimal("4200"), row.get("amount"));
        assertEquals("due_soon", row.get("status"));
        assertEquals(BigDecimal.valueOf(5), row.get("daysUntil"));
        assertEquals("/upcoming?bill=" + statementId, row.get("href"));
        assertEquals(statementId.toString(), row.get("refId"));
        assertEquals(List.of("id", "dueDate", "kind", "title", "accountName", "amount", "status", "daysUntil", "href", "refId"),
                List.copyOf(row.keySet()));
    }

    @Test
    void emiRowKeysOnLoanAndInstallment() {
        UUID loanId = UUID.randomUUID();
        Map<String, Object> row = single(new ObligationItemDto("emi", TODAY.minusDays(2), new BigDecimal("9000"), "overdue",
                loanId, "Car", 7, null, null, null, null,
                "Car EMI #7", null, "Car", null, "/loans/" + loanId + "?installment=7", -2L));

        assertEquals("emi:" + loanId + ":7", row.get("id"));
        assertEquals("emi", row.get("kind"));
        assertEquals(loanId.toString(), row.get("refId"));
        assertEquals(BigDecimal.valueOf(-2), row.get("daysUntil"));
        assertEquals("overdue", row.get("status"));
    }

    @Test
    void lendingDueBecomesLendingReturnKeyedOnTheCounterparty() {
        UUID cp = UUID.randomUUID();
        Map<String, Object> row = single(new ObligationItemDto("lending_due", TODAY.plusDays(20), new BigDecimal("500"), "upcoming",
                null, null, null, UUID.randomUUID(), cp, "Asha", LendingDirection.lent,
                "Asha owes you", null, "Asha", null, "/loans/lendings/" + cp, 20L));

        assertEquals("lending:" + cp, row.get("id"));
        assertEquals("lending_return", row.get("kind"));
        assertEquals(cp.toString(), row.get("refId"));
    }

    @Test
    void statementExpectedKeysOnAccountAndDate() {
        UUID accountId = UUID.randomUUID();
        LocalDate expected = LocalDate.of(2026, 10, 15);
        Map<String, Object> row = single(new ObligationItemDto("statement_expected", expected, null, "due_soon",
                null, null, null, null, null, null, null,
                "Axis statement expected", accountId, "Axis", null, "/transactions/import", 7L));

        assertEquals("statement-expected:" + accountId + ":2026-10-15", row.get("id"));
        assertEquals("statement_expected", row.get("kind"));
        assertEquals(accountId.toString(), row.get("refId"));
        assertNull(row.get("amount"));
    }

    @Test
    void undatedRowHasNullDateAndDaysUntil() {
        UUID statementId = UUID.randomUUID();
        Map<String, Object> row = single(new ObligationItemDto("card_bill", null, null, "upcoming",
                null, null, null, null, null, null, null,
                "X bill", UUID.randomUUID(), "X", statementId, "/upcoming?bill=" + statementId, null));

        assertNull(row.get("dueDate"));
        assertNull(row.get("daysUntil"));
        assertNull(row.get("amount"));
    }

    @Test
    void aMissingReferenceLeavesRefIdNull() {
        Map<String, Object> row = single(new ObligationItemDto("emi", TODAY, BigDecimal.ONE, "due_soon",
                null, "L", null, null, null, null, null));

        assertNull(row.get("refId"));
        assertEquals("emi:null:null", row.get("id"));
    }

    @Test
    void anUnknownTypeKeepsItsNameAndHasNoReference() {
        Map<String, Object> row = single(new ObligationItemDto("other", TODAY, BigDecimal.ONE, "due_soon",
                UUID.randomUUID(), null, null, null, null, null, null));

        assertEquals("other", row.get("kind"));
        assertNull(row.get("refId"));
        assertEquals("other:null", row.get("id"));
    }

    @Test
    void rowsKeepTheServiceOrder() {
        UUID a = UUID.randomUUID();
        UUID b = UUID.randomUUID();
        when(service.upcoming(userId, 12, ObligationsService.ALL_KINDS)).thenReturn(new ObligationsResponse(List.of(
                new ObligationItemDto("emi", TODAY, BigDecimal.ONE, "due_soon", a, "A", 1, null, null, null, null),
                new ObligationItemDto("emi", TODAY, BigDecimal.ONE, "due_soon", b, "B", 1, null, null, null, null))));

        List<Map<String, Object>> rows = datasource.rows(null);

        assertEquals(List.of("emi:" + a + ":1", "emi:" + b + ":1"), rows.stream().map(r -> r.get("id")).toList());
    }

    private Map<String, Object> single(ObligationItemDto item) {
        when(service.upcoming(userId, 12, ObligationsService.ALL_KINDS)).thenReturn(new ObligationsResponse(List.of(item)));
        List<Map<String, Object>> rows = datasource.rows();
        assertEquals(1, rows.size());
        return rows.get(0);
    }
}
