package com.financeos.domain.report.datasource.impl;

import com.financeos.domain.instrument.InstrumentType;
import com.financeos.domain.investment.InvestmentService;
import com.financeos.domain.investment.dto.RealizedLot;
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

/**
 * Computed report datasource: one row per FIFO buy↔sell match on delivery trades, from the lot
 * engine in {@link InvestmentService} (corporate-action adjusted).
 *
 * <p>Known simplifications:
 * <ul>
 *   <li>Delivery matches only — intraday realized is a same-day netting total, not lots.</li>
 *   <li>{@code term} is a flat &gt;365-day short/long split; real ST/LT thresholds vary by asset
 *       class and year.</li>
 *   <li>Fractional cash-in-lieu realized (merger/demerger share flooring) never produces a lot,
 *       so SUM(realizedPnl) here excludes those small amounts; the holding-level realized figure
 *       includes them.</li>
 *   <li>CA-seeded lots (demerger child / merger target) use the CA ex-date as {@code buyDate},
 *       resetting the holding-period clock; tax law would carry the original acquisition date.</li>
 *   <li>Lot P&L is sell value − buy value, allocation-free — charges are not apportioned to lots
 *       (matches the holding-level realized figure, which also excludes charges).</li>
 * </ul>
 */
@Component
public class RealizedLotsDatasource implements ComputedReportDatasource {

    private static final List<ReportType> CHART_TABLE = List.of(ReportType.CHART, ReportType.TABLE);
    private static final List<ReportType> KPI_CHART_TABLE = List.of(ReportType.KPI, ReportType.CHART, ReportType.TABLE);
    private static final List<ReportType> TABLE_ONLY = List.of(ReportType.TABLE);

    private final InvestmentService investmentService;
    private final List<FieldDef> fields;

    public RealizedLotsDatasource(InvestmentService investmentService) {
        this.investmentService = investmentService;
        this.fields = buildCatalog();
    }

    @Override
    public String name() {
        return "realized_lots";
    }

    @Override
    public String label() {
        return "Realized P&L";
    }

    @Override
    public List<FieldDef> fields() {
        return fields;
    }

    @Override
    public List<Map<String, Object>> rows() {
        List<RealizedLot> lots = investmentService.getAllRealizedLots();
        List<Map<String, Object>> rows = new ArrayList<>();

        int idx = 0;
        for (RealizedLot lot : lots) {
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("id", lot.holdingId() + "_" + idx++);
            map.put("sellDate", lot.sellDate());
            map.put("buyDate", lot.buyDate());
            map.put("instrument", lot.instrumentName());
            map.put("instrumentType", lot.instrumentType() != null ? lot.instrumentType().name() : null);
            map.put("broker", lot.brokerName());
            map.put("term", lot.term());
            map.put("realizedPnl", lot.realizedPnl());
            map.put("buyValue", lot.buyValue());
            map.put("sellValue", lot.sellValue());
            map.put("quantity", lot.quantity());
            map.put("holdingDays", BigDecimal.valueOf(lot.holdingDays()));

            rows.add(map);
        }

        return rows;
    }

    private List<FieldDef> buildCatalog() {
        List<String> instTypeValues = Arrays.stream(InstrumentType.values()).map(Enum::name).toList();
        List<String> termValues = List.of("short", "long");

        return List.of(
                new FieldDef("sellDate", "Sell Date", FieldType.DATE, FieldRole.DIMENSION, null, null, null, CHART_TABLE),
                new FieldDef("buyDate", "Buy Date", FieldType.DATE, FieldRole.DIMENSION, null, null, null, TABLE_ONLY),
                new FieldDef("instrument", "Instrument", FieldType.ENUM, FieldRole.DIMENSION, null, null, true, CHART_TABLE),
                new FieldDef("instrumentType", "Instrument Type", FieldType.ENUM, FieldRole.DIMENSION, null, instTypeValues, null, CHART_TABLE),
                new FieldDef("broker", "Broker", FieldType.ENUM, FieldRole.DIMENSION, null, null, true, CHART_TABLE),
                new FieldDef("term", "Term (ST / LT)", FieldType.ENUM, FieldRole.DIMENSION, null, termValues, null, CHART_TABLE),
                new FieldDef("realizedPnl", "Realized P&L", FieldType.NUMBER, FieldRole.MEASURE, List.of(Aggregation.SUM, Aggregation.AVG, Aggregation.COUNT, Aggregation.MIN, Aggregation.MAX), null, null, KPI_CHART_TABLE, "currency"),
                new FieldDef("buyValue", "Buy Value", FieldType.NUMBER, FieldRole.MEASURE, List.of(Aggregation.SUM, Aggregation.AVG, Aggregation.MIN, Aggregation.MAX), null, null, KPI_CHART_TABLE, "currency"),
                new FieldDef("sellValue", "Sell Value", FieldType.NUMBER, FieldRole.MEASURE, List.of(Aggregation.SUM, Aggregation.AVG, Aggregation.MIN, Aggregation.MAX), null, null, KPI_CHART_TABLE, "currency"),
                new FieldDef("quantity", "Quantity", FieldType.NUMBER, FieldRole.MEASURE, List.of(Aggregation.SUM, Aggregation.AVG, Aggregation.MIN, Aggregation.MAX), null, null, TABLE_ONLY, "number"),
                new FieldDef("holdingDays", "Holding Days", FieldType.NUMBER, FieldRole.MEASURE, List.of(Aggregation.AVG, Aggregation.MIN, Aggregation.MAX), null, null, TABLE_ONLY, "number")
        );
    }
}
