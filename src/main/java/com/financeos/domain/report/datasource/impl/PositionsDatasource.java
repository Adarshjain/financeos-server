package com.financeos.domain.report.datasource.impl;

import com.financeos.api.investment.dto.PositionDto;
import com.financeos.domain.instrument.InstrumentType;
import com.financeos.domain.investment.InvestmentService;
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
public class PositionsDatasource implements ComputedReportDatasource {

    private static final List<ReportType> CHART_TABLE = List.of(ReportType.CHART, ReportType.TABLE);
    private static final List<ReportType> KPI_CHART_TABLE = List.of(ReportType.KPI, ReportType.CHART, ReportType.TABLE);
    private static final List<ReportType> TABLE_ONLY = List.of(ReportType.TABLE);
    private static final List<ReportType> NONE = List.of();

    private final InvestmentService investmentService;
    private final List<FieldDef> fields;

    public PositionsDatasource(InvestmentService investmentService) {
        this.investmentService = investmentService;
        this.fields = buildCatalog();
    }

    @Override
    public String name() {
        return "positions";
    }

    @Override
    public String label() {
        return "Positions";
    }

    @Override
    public List<FieldDef> fields() {
        return fields;
    }

    @Override
    public List<Map<String, Object>> rows() {
        List<PositionDto> positions = investmentService.getAllPositions();
        List<Map<String, Object>> rows = new ArrayList<>();

        for (PositionDto p : positions) {
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("id", p.holdingId() != null ? p.holdingId().toString() : null);
            map.put("broker", p.brokerName());
            map.put("instrument", p.instrument() != null ? p.instrument().name() : null);
            map.put("instrumentType", p.instrument() != null && p.instrument().type() != null ? p.instrument().type().name() : null);
            map.put("invested", p.invested());
            map.put("currentValue", p.currentValue());
            map.put("unrealizedGainLoss", p.unrealizedGainLoss());
            map.put("realizedGainLoss", p.realizedGainLoss());
            map.put("dividends", p.dividends());
            map.put("totalCharges", p.totalCharges());
            map.put("quantity", p.quantity());
            map.put("xirr", p.xirr() != null ? BigDecimal.valueOf(p.xirr()) : null);
            map.put("unrealizedPercent", p.unrealizedGainLossPercent());
            map.put("isOpen", p.quantity() != null && p.quantity().compareTo(BigDecimal.ZERO) > 0);

            rows.add(map);
        }

        return rows;
    }

    private List<FieldDef> buildCatalog() {
        List<String> instTypeValues = Arrays.stream(InstrumentType.values()).map(Enum::name).toList();

        return List.of(
                new FieldDef("broker", "Broker", FieldType.ENUM, FieldRole.DIMENSION, null, null, true, CHART_TABLE),
                new FieldDef("instrument", "Instrument", FieldType.ENUM, FieldRole.DIMENSION, null, null, true, CHART_TABLE),
                new FieldDef("instrumentType", "Instrument Type", FieldType.ENUM, FieldRole.DIMENSION, null, instTypeValues, null, CHART_TABLE),
                new FieldDef("invested", "Invested Amount", FieldType.NUMBER, FieldRole.MEASURE, List.of(Aggregation.SUM, Aggregation.AVG, Aggregation.COUNT, Aggregation.MIN, Aggregation.MAX), null, null, KPI_CHART_TABLE, "currency"),
                new FieldDef("currentValue", "Current Value", FieldType.NUMBER, FieldRole.MEASURE, List.of(Aggregation.SUM, Aggregation.AVG, Aggregation.COUNT, Aggregation.MIN, Aggregation.MAX), null, null, KPI_CHART_TABLE, "currency"),
                new FieldDef("unrealizedGainLoss", "Unrealized P&L", FieldType.NUMBER, FieldRole.MEASURE, List.of(Aggregation.SUM, Aggregation.AVG, Aggregation.COUNT, Aggregation.MIN, Aggregation.MAX), null, null, KPI_CHART_TABLE, "currency"),
                new FieldDef("realizedGainLoss", "Realized P&L", FieldType.NUMBER, FieldRole.MEASURE, List.of(Aggregation.SUM, Aggregation.AVG, Aggregation.COUNT, Aggregation.MIN, Aggregation.MAX), null, null, KPI_CHART_TABLE, "currency"),
                new FieldDef("dividends", "Dividends Received", FieldType.NUMBER, FieldRole.MEASURE, List.of(Aggregation.SUM, Aggregation.AVG, Aggregation.COUNT, Aggregation.MIN, Aggregation.MAX), null, null, KPI_CHART_TABLE, "currency"),
                new FieldDef("totalCharges", "Total Charges", FieldType.NUMBER, FieldRole.MEASURE, List.of(Aggregation.SUM, Aggregation.AVG, Aggregation.COUNT, Aggregation.MIN, Aggregation.MAX), null, null, KPI_CHART_TABLE, "currency"),
                new FieldDef("quantity", "Quantity", FieldType.NUMBER, FieldRole.MEASURE, List.of(Aggregation.SUM, Aggregation.AVG, Aggregation.MIN, Aggregation.MAX), null, null, TABLE_ONLY, "number"),
                new FieldDef("xirr", "XIRR", FieldType.NUMBER, FieldRole.MEASURE, List.of(Aggregation.AVG, Aggregation.MIN, Aggregation.MAX), null, null, TABLE_ONLY, "percent"),
                new FieldDef("unrealizedPercent", "Unrealized P&L %", FieldType.NUMBER, FieldRole.MEASURE, List.of(Aggregation.AVG, Aggregation.MIN, Aggregation.MAX), null, null, TABLE_ONLY, "percent"),
                new FieldDef("isOpen", "Is Open Holding", FieldType.BOOLEAN, FieldRole.FILTER, null, null, null, NONE)
        );
    }
}
