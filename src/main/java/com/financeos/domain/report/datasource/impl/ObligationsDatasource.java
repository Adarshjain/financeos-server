package com.financeos.domain.report.datasource.impl;

import com.financeos.api.obligations.dto.ObligationItemDto;
import com.financeos.core.security.UserContext;
import com.financeos.core.time.AppTime;
import com.financeos.domain.obligations.ObligationsService;
import com.financeos.domain.report.ReportType;
import com.financeos.domain.report.datasource.Aggregation;
import com.financeos.domain.report.datasource.ComputedReportDatasource;
import com.financeos.domain.report.datasource.DatasourceCatalog.FieldDef;
import com.financeos.domain.report.datasource.FieldRole;
import com.financeos.domain.report.datasource.FieldType;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Computed report datasource over {@link ObligationsService}: one row per upcoming obligation
 * (card bill, EMI, lending return, expected statement) for the current user, up to 12 months
 * ahead plus everything overdue. {@code dueDate} is the date field; a run whose date filter is
 * bounded only computes as many months as its upper bound needs.
 */
@Component
public class ObligationsDatasource implements ComputedReportDatasource {

    static final String DATE_FIELD = "dueDate";

    /** Datasource kind for the endpoint's {@code lending_due} type. */
    static final String KIND_LENDING_RETURN = "lending_return";
    static final List<String> KIND_VALUES = List.of(
            ObligationsService.KIND_CARD_BILL, ObligationsService.KIND_EMI, KIND_LENDING_RETURN,
            ObligationsService.KIND_STATEMENT_EXPECTED);
    static final List<String> STATUS_VALUES = List.of(
            ObligationsService.STATUS_OVERDUE, ObligationsService.STATUS_DUE_SOON, ObligationsService.STATUS_UPCOMING);

    private static final List<ReportType> KPI_CHART_TABLE = List.of(ReportType.KPI, ReportType.CHART, ReportType.TABLE);
    private static final List<ReportType> CHART_TABLE = List.of(ReportType.CHART, ReportType.TABLE);
    private static final List<ReportType> TABLE_ONLY = List.of(ReportType.TABLE);

    private final ObligationsService obligationsService;
    private final List<FieldDef> fields;

    public ObligationsDatasource(ObligationsService obligationsService) {
        this.obligationsService = obligationsService;
        this.fields = buildCatalog();
    }

    @Override
    public String name() {
        return "obligations";
    }

    @Override
    public String label() {
        return "Upcoming obligations";
    }

    @Override
    public List<FieldDef> fields() {
        return fields;
    }

    @Override
    public List<Map<String, Object>> rows() {
        return rows(null);
    }

    @Override
    public List<Map<String, Object>> rows(@Nullable DateHint hint) {
        UUID userId = UserContext.getCurrentUserId();
        int months = monthsFor(hint, AppTime.today());
        List<ObligationItemDto> items = obligationsService.upcoming(userId, months, ObligationsService.ALL_KINDS).items();
        List<Map<String, Object>> rows = new ArrayList<>(items.size());
        for (ObligationItemDto item : items) {
            rows.add(toRow(item));
        }
        return rows;
    }

    /** The look-ahead the hint's upper bound needs (whole months, rounded up), clamped to the service's range. */
    static int monthsFor(@Nullable DateHint hint, LocalDate today) {
        if (hint == null || !DATE_FIELD.equals(hint.field()) || hint.to() == null) {
            return ObligationsService.MAX_MONTHS;
        }
        LocalDate to = hint.to();
        if (!to.isAfter(today)) {
            return ObligationsService.MIN_MONTHS;
        }
        long whole = ChronoUnit.MONTHS.between(today, to);
        long months = today.plusMonths(whole).isBefore(to) ? whole + 1 : whole;
        return ObligationsService.clampMonths((int) Math.min(months, ObligationsService.MAX_MONTHS));
    }

    private static Map<String, Object> toRow(ObligationItemDto item) {
        String kind = ObligationsService.KIND_LENDING_DUE.equals(item.type()) ? KIND_LENDING_RETURN : item.type();
        String refId = refIdOf(item);
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("id", idOf(item, refId));
        row.put(DATE_FIELD, item.date());
        row.put("kind", kind);
        row.put("title", item.title());
        row.put("accountName", item.accountName());
        row.put("amount", item.amount());
        row.put("status", item.status());
        row.put("daysUntil", item.daysUntil() != null ? BigDecimal.valueOf(item.daysUntil()) : null);
        row.put("href", item.href());
        row.put("refId", refId);
        return row;
    }

    /** The id of what the obligation is about: statement, loan, counterparty or account. */
    @Nullable
    private static String refIdOf(ObligationItemDto item) {
        UUID ref = switch (item.type()) {
            case ObligationsService.KIND_CARD_BILL -> item.statementId();
            case ObligationsService.KIND_EMI -> item.loanId();
            case ObligationsService.KIND_LENDING_DUE -> item.counterpartyId();
            case ObligationsService.KIND_STATEMENT_EXPECTED -> item.accountId();
            default -> null;
        };
        return ref != null ? ref.toString() : null;
    }

    /** Stable row id, the same key the inbox uses for the item. */
    private static String idOf(ObligationItemDto item, @Nullable String refId) {
        return switch (item.type()) {
            case ObligationsService.KIND_CARD_BILL -> "bill:" + refId;
            case ObligationsService.KIND_EMI -> "emi:" + refId + ":" + item.installmentSeq();
            case ObligationsService.KIND_LENDING_DUE -> "lending:" + refId;
            case ObligationsService.KIND_STATEMENT_EXPECTED -> "statement-expected:" + refId + ":" + item.date();
            default -> item.type() + ":" + refId;
        };
    }

    private static List<FieldDef> buildCatalog() {
        return List.of(
                new FieldDef("id", "ID", FieldType.STRING, FieldRole.DIMENSION, null, null, null, TABLE_ONLY).notFilterable(),
                new FieldDef(DATE_FIELD, "Due date", FieldType.DATE, FieldRole.DIMENSION, null, null, null, CHART_TABLE),
                new FieldDef("kind", "Kind", FieldType.ENUM, FieldRole.DIMENSION, null, KIND_VALUES, null, CHART_TABLE),
                new FieldDef("title", "Title", FieldType.STRING, FieldRole.DIMENSION, null, null, null, TABLE_ONLY),
                new FieldDef("accountName", "Account", FieldType.STRING, FieldRole.DIMENSION, null, null, null, CHART_TABLE),
                new FieldDef("amount", "Amount", FieldType.NUMBER, FieldRole.MEASURE, List.of(Aggregation.SUM), null, null,
                        KPI_CHART_TABLE, "currency"),
                new FieldDef("status", "Status", FieldType.ENUM, FieldRole.DIMENSION, null, STATUS_VALUES, null, CHART_TABLE),
                new FieldDef("daysUntil", "Days until due", FieldType.NUMBER, FieldRole.MEASURE,
                        List.of(Aggregation.MIN, Aggregation.MAX), null, null, TABLE_ONLY, "number"),
                new FieldDef("href", "Link", FieldType.STRING, FieldRole.DIMENSION, null, null, null, TABLE_ONLY).notFilterable(),
                new FieldDef("refId", "Reference ID", FieldType.STRING, FieldRole.DIMENSION, null, null, null, TABLE_ONLY).notFilterable()
        );
    }
}
