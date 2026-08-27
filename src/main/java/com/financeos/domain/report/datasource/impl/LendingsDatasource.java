package com.financeos.domain.report.datasource.impl;

import com.financeos.domain.lending.Lending;
import com.financeos.domain.lending.LendingDirection;
import com.financeos.domain.lending.LendingService;
import com.financeos.domain.report.ReportType;
import com.financeos.domain.report.datasource.Aggregation;
import com.financeos.domain.report.datasource.ComputedReportDatasource;
import com.financeos.domain.report.datasource.DatasourceCatalog.FieldDef;
import com.financeos.domain.report.datasource.FieldRole;
import com.financeos.domain.report.datasource.FieldType;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Component
public class LendingsDatasource implements ComputedReportDatasource {

    private static final List<Aggregation> NUMERIC_AGGS = List.of(
            Aggregation.SUM, Aggregation.AVG, Aggregation.COUNT, Aggregation.MIN, Aggregation.MAX);

    private static final List<ReportType> KPI_CHART_TABLE = List.of(ReportType.KPI, ReportType.CHART, ReportType.TABLE);
    private static final List<ReportType> CHART_TABLE = List.of(ReportType.CHART, ReportType.TABLE);
    private static final List<ReportType> TABLE_ONLY = List.of(ReportType.TABLE);

    private final LendingService lendingService;
    private final List<FieldDef> fields;

    public LendingsDatasource(LendingService lendingService) {
        this.lendingService = lendingService;
        this.fields = buildCatalog();
    }

    @Override
    public String name() {
        return "lendings";
    }

    @Override
    public String label() {
        return "Lendings";
    }

    @Override
    public List<FieldDef> fields() {
        return fields;
    }

    @Override
    public List<Map<String, Object>> rows() {
        List<Lending> lendings = lendingService.getAllLendings();
        List<Map<String, Object>> rows = new ArrayList<>();

        for (Lending lending : lendings) {
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("id", lending.getId() != null ? lending.getId().toString() : null);
            map.put("counterpartyId", lending.getCounterparty() != null && lending.getCounterparty().getId() != null
                    ? lending.getCounterparty().getId().toString()
                    : null);
            map.put("counterpartyName", lending.getCounterparty() != null ? lending.getCounterparty().getName() : null);
            map.put("direction", lending.getDirection() != null ? lending.getDirection().name() : null);
            map.put("amount", lending.getAmount());

            BigDecimal signedAmount = null;
            if (lending.getAmount() != null) {
                signedAmount = (lending.getDirection() == LendingDirection.lent)
                        ? lending.getAmount()
                        : lending.getAmount().negate();
            }
            map.put("signedAmount", signedAmount);
            map.put("entryDate", lending.getEntryDate());
            map.put("expectedReturnDate", lending.getExpectedReturnDate());
            map.put("notes", lending.getNotes());

            rows.add(map);
        }

        return rows;
    }

    private List<FieldDef> buildCatalog() {
        List<String> directionValues = Arrays.stream(LendingDirection.values()).map(Enum::name).toList();

        return List.of(
                new FieldDef("entryDate", "Entry Date", FieldType.DATE, FieldRole.DIMENSION, null, null, null, CHART_TABLE),
                new FieldDef("expectedReturnDate", "Expected Return Date", FieldType.DATE, FieldRole.DIMENSION, null, null, null, TABLE_ONLY),
                new FieldDef("counterpartyId", "Counterparty ID", FieldType.STRING, FieldRole.DIMENSION, null, null, null, TABLE_ONLY),
                new FieldDef("counterpartyName", "Counterparty", FieldType.ENUM, FieldRole.DIMENSION, null, null, true, CHART_TABLE),
                new FieldDef("direction", "Direction", FieldType.ENUM, FieldRole.DIMENSION, null, directionValues, null, CHART_TABLE),
                new FieldDef("amount", "Amount", FieldType.NUMBER, FieldRole.MEASURE, NUMERIC_AGGS, null, null, KPI_CHART_TABLE, "currency"),
                new FieldDef("signedAmount", "Signed Amount", FieldType.NUMBER, FieldRole.MEASURE, NUMERIC_AGGS, null, null, KPI_CHART_TABLE, "currency"),
                new FieldDef("notes", "Notes", FieldType.STRING, FieldRole.DIMENSION, null, null, null, TABLE_ONLY)
        );
    }
}
