package com.financeos.domain.report.datasource.impl;

import com.financeos.domain.instrument.InstrumentType;
import com.financeos.domain.investment.InvestmentTransactionType;
import com.financeos.domain.investment.SettlementType;
import com.financeos.domain.investment.imports.ImportSource;
import com.financeos.domain.report.ReportType;
import com.financeos.domain.report.datasource.Aggregation;
import com.financeos.domain.report.datasource.DatasourceCatalog.FieldDef;
import com.financeos.domain.report.datasource.FieldRole;
import com.financeos.domain.report.datasource.FieldType;
import com.financeos.domain.report.datasource.ReportDatasource;
import com.financeos.domain.report.engine.AbstractReportQueryBuilder;
import com.financeos.domain.report.engine.DateRangeResolver;
import com.financeos.domain.report.engine.ReportQueryBuilder;
import com.financeos.domain.report.engine.SqlPredicates;
import org.springframework.stereotype.Component;

import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import java.util.stream.Stream;

@Component
public class InvestmentTradesDatasource implements ReportDatasource {

    private static final List<Aggregation> NUMERIC_AGGS = List.of(
            Aggregation.SUM, Aggregation.AVG, Aggregation.COUNT, Aggregation.MIN, Aggregation.MAX);
    private static final List<Aggregation> NON_SUM_AGGS = List.of(
            Aggregation.AVG, Aggregation.MIN, Aggregation.MAX);

    private static final List<ReportType> ALL = List.of(ReportType.KPI, ReportType.CHART, ReportType.TABLE);
    private static final List<ReportType> CHART_TABLE = List.of(ReportType.CHART, ReportType.TABLE);
    private static final List<ReportType> TABLE_ONLY = List.of(ReportType.TABLE);

    private static final List<String> TYPE_VALUES = Arrays.stream(InvestmentTransactionType.values())
            .map(Enum::name)
            .toList();
    private static final List<String> SETTLEMENT_VALUES = Arrays.stream(SettlementType.values())
            .map(Enum::name)
            .toList();
    private static final List<String> INSTRUMENT_TYPE_VALUES = Arrays.stream(InstrumentType.values())
            .map(Enum::name)
            .toList();
    private static final List<String> SOURCE_VALUES = Stream.concat(
                    Stream.of("manual"),
                    Arrays.stream(ImportSource.values()).map(Enum::name))
            .distinct()
            .toList();

    private static final List<FieldDef> FIELDS = List.of(
            new FieldDef("tradeValue", "Trade value", FieldType.NUMBER, FieldRole.MEASURE, NUMERIC_AGGS, null, null, ALL, "currency"),
            new FieldDef("cashflow", "Cash flow", FieldType.NUMBER, FieldRole.MEASURE, NUMERIC_AGGS, null, null, ALL, "currency"),
            new FieldDef("quantity", "Quantity", FieldType.NUMBER, FieldRole.MEASURE, NUMERIC_AGGS, null, null, ALL, "number"),
            new FieldDef("price", "Price", FieldType.NUMBER, FieldRole.MEASURE, NON_SUM_AGGS, null, null, TABLE_ONLY, "currency"),
            new FieldDef("totalCharges", "Charges", FieldType.NUMBER, FieldRole.MEASURE, NUMERIC_AGGS, null, null, ALL, "currency"),
            new FieldDef("tradeDate", "Trade date", FieldType.DATE, FieldRole.DIMENSION, null, null, null, CHART_TABLE),
            new FieldDef("type", "Type", FieldType.ENUM, FieldRole.DIMENSION, null, TYPE_VALUES, null, CHART_TABLE),
            new FieldDef("settlementType", "Settlement", FieldType.ENUM, FieldRole.DIMENSION, null, SETTLEMENT_VALUES, null, CHART_TABLE),
            new FieldDef("broker", "Broker", FieldType.ENUM, FieldRole.DIMENSION, null, null, true, CHART_TABLE),
            new FieldDef("instrument", "Instrument", FieldType.ENUM, FieldRole.DIMENSION, null, null, true, CHART_TABLE),
            new FieldDef("instrumentType", "Instrument type", FieldType.ENUM, FieldRole.DIMENSION, null, INSTRUMENT_TYPE_VALUES, null, CHART_TABLE),
            new FieldDef("source", "Source", FieldType.ENUM, FieldRole.DIMENSION, null, SOURCE_VALUES, null, CHART_TABLE)
    );

    public static class InvestmentTradesQueryBuilder extends AbstractReportQueryBuilder {

        public static final String JOIN_HOLDINGS = "HOLDINGS";
        public static final String JOIN_INSTRUMENTS = "INSTRUMENTS";
        public static final String JOIN_ACCOUNTS = "ACCOUNTS";

        private static final Map<String, Mapping> MAPPINGS = Map.ofEntries(
                Map.entry("tradeValue", new Mapping("(it.quantity * it.price)", null)),
                Map.entry("cashflow", new Mapping("(CASE WHEN it.type = 'sell' THEN it.quantity * it.price ELSE -(it.quantity * it.price) END)", null)),
                Map.entry("quantity", new Mapping("it.quantity", null)),
                Map.entry("price", new Mapping("it.price", null)),
                Map.entry("totalCharges", new Mapping("NVL(it.total_charges, 0)", null)),
                Map.entry("tradeDate", new Mapping("it.trade_date", null)),
                Map.entry("type", new Mapping("it.type", null)),
                Map.entry("settlementType", new Mapping("it.settlement_type", null)),
                Map.entry("broker", new Mapping("acc.name", JOIN_ACCOUNTS)),
                Map.entry("instrument", new Mapping("ins.name", JOIN_INSTRUMENTS)),
                Map.entry("instrumentType", new Mapping("ins.type", JOIN_INSTRUMENTS)),
                Map.entry("source", new Mapping("it.source", null))
        );

        public InvestmentTradesQueryBuilder(Map<String, FieldDef> fieldsMap, DateRangeResolver dateRangeResolver, SqlPredicates sqlPredicates) {
            super(MAPPINGS, fieldsMap, sqlPredicates, dateRangeResolver);
        }

        @Override
        protected void recordJoin(String joinKey, Set<String> joins) {
            if (JOIN_INSTRUMENTS.equals(joinKey) || JOIN_ACCOUNTS.equals(joinKey)) {
                joins.add(JOIN_HOLDINGS);
            }
            joins.add(joinKey);
        }

        @Override
        public String idExpression() {
            return "it.id";
        }

        @Override
        protected String userScopePredicate(Map<String, Object> params, UUID userId) {
            params.put("userId", userId.toString());
            return "it.user_id = :userId";
        }

        @Override
        public String fromClause(Set<String> joins) {
            StringBuilder sb = new StringBuilder(" FROM investment_transactions it");
            if (joins.contains(JOIN_HOLDINGS) || joins.contains(JOIN_INSTRUMENTS) || joins.contains(JOIN_ACCOUNTS)) {
                sb.append(" JOIN holdings h ON h.id = it.holding_id");
            }
            if (joins.contains(JOIN_INSTRUMENTS)) {
                sb.append(" JOIN instruments ins ON ins.id = h.instrument_id");
            }
            if (joins.contains(JOIN_ACCOUNTS)) {
                sb.append(" JOIN accounts acc ON acc.id = h.broker_account_id");
            }
            return sb.toString();
        }
    }

    private final InvestmentTradesQueryBuilder queryBuilder;

    public InvestmentTradesDatasource(SqlPredicates sqlPredicates, DateRangeResolver dateRangeResolver) {
        Map<String, FieldDef> fieldsMap = FIELDS.stream().collect(Collectors.toMap(FieldDef::name, f -> f));
        this.queryBuilder = new InvestmentTradesQueryBuilder(fieldsMap, dateRangeResolver, sqlPredicates);
    }

    @Override
    public String name() {
        return "investment_trades";
    }

    @Override
    public String label() {
        return "Investment Trades";
    }

    @Override
    public List<FieldDef> fields() {
        return FIELDS;
    }

    @Override
    public ReportQueryBuilder queryBuilder() {
        return queryBuilder;
    }
}
