package com.financeos.domain.report.datasource.impl;

import com.financeos.domain.instrument.OptionType;
import com.financeos.domain.investment.fno.FnoContractType;
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
public class FnoTradesDatasource implements ReportDatasource {

    private static final List<Aggregation> NUMERIC_AGGS = List.of(
            Aggregation.SUM, Aggregation.AVG, Aggregation.COUNT, Aggregation.MIN, Aggregation.MAX);
    private static final List<Aggregation> NON_SUM_AGGS = List.of(
            Aggregation.AVG, Aggregation.MIN, Aggregation.MAX);

    private static final List<ReportType> ALL = List.of(ReportType.KPI, ReportType.CHART, ReportType.TABLE);
    private static final List<ReportType> CHART_TABLE = List.of(ReportType.CHART, ReportType.TABLE);
    private static final List<ReportType> TABLE_ONLY = List.of(ReportType.TABLE);

    private static final List<String> CONTRACT_TYPE_VALUES = Arrays.stream(FnoContractType.values())
            .map(Enum::name)
            .toList();
    private static final List<String> OPTION_TYPE_VALUES = Arrays.stream(OptionType.values())
            .map(Enum::name)
            .toList();
    private static final List<String> SOURCE_VALUES = Stream.concat(
                    Stream.of("manual"),
                    Arrays.stream(ImportSource.values()).map(Enum::name))
            .distinct()
            .toList();

    private static final List<FieldDef> FIELDS = List.of(
            new FieldDef("realizedPnl", "Realized P&L", FieldType.NUMBER, FieldRole.MEASURE, NUMERIC_AGGS, null, null, ALL, "currency"),
            new FieldDef("buyValue", "Buy value", FieldType.NUMBER, FieldRole.MEASURE, NUMERIC_AGGS, null, null, ALL, "currency"),
            new FieldDef("sellValue", "Sell value", FieldType.NUMBER, FieldRole.MEASURE, NUMERIC_AGGS, null, null, ALL, "currency"),
            new FieldDef("totalCharges", "Charges", FieldType.NUMBER, FieldRole.MEASURE, NUMERIC_AGGS, null, null, ALL, "currency"),
            new FieldDef("quantity", "Quantity", FieldType.NUMBER, FieldRole.MEASURE, NUMERIC_AGGS, null, null, ALL, "number"),
            new FieldDef("strikePrice", "Strike", FieldType.NUMBER, FieldRole.MEASURE, NON_SUM_AGGS, null, null, TABLE_ONLY, "currency"),
            new FieldDef("exitDate", "Exit date", FieldType.DATE, FieldRole.DIMENSION, null, null, null, CHART_TABLE),
            new FieldDef("entryDate", "Entry date", FieldType.DATE, FieldRole.DIMENSION, null, null, null, CHART_TABLE),
            new FieldDef("expiryDate", "Expiry", FieldType.DATE, FieldRole.DIMENSION, null, null, null, CHART_TABLE),
            new FieldDef("contractType", "Contract", FieldType.ENUM, FieldRole.DIMENSION, null, CONTRACT_TYPE_VALUES, null, CHART_TABLE),
            new FieldDef("optionType", "Option type", FieldType.ENUM, FieldRole.DIMENSION, null, OPTION_TYPE_VALUES, null, CHART_TABLE),
            new FieldDef("underlyingSymbol", "Underlying", FieldType.STRING, FieldRole.DIMENSION, null, null, null, CHART_TABLE),
            new FieldDef("tradingSymbol", "Trading symbol", FieldType.STRING, FieldRole.DIMENSION, null, null, null, TABLE_ONLY),
            new FieldDef("broker", "Broker", FieldType.ENUM, FieldRole.DIMENSION, null, null, true, CHART_TABLE),
            new FieldDef("source", "Source", FieldType.ENUM, FieldRole.DIMENSION, null, SOURCE_VALUES, null, CHART_TABLE)
    );

    public static class FnoTradesQueryBuilder extends AbstractReportQueryBuilder {

        public static final String JOIN_ACCOUNTS = "ACCOUNTS";

        private static final Map<String, Mapping> MAPPINGS = Map.ofEntries(
                Map.entry("realizedPnl", new Mapping("f.realized_pnl", null)),
                Map.entry("buyValue", new Mapping("f.buy_value", null)),
                Map.entry("sellValue", new Mapping("f.sell_value", null)),
                Map.entry("totalCharges", new Mapping("NVL(f.total_charges, 0)", null)),
                Map.entry("quantity", new Mapping("f.quantity", null)),
                Map.entry("strikePrice", new Mapping("f.strike_price", null)),
                Map.entry("exitDate", new Mapping("f.exit_date", null)),
                Map.entry("entryDate", new Mapping("f.entry_date", null)),
                Map.entry("expiryDate", new Mapping("f.expiry_date", null)),
                Map.entry("contractType", new Mapping("f.contract_type", null)),
                Map.entry("optionType", new Mapping("f.option_type", null)),
                Map.entry("underlyingSymbol", new Mapping("f.underlying_symbol", null)),
                Map.entry("tradingSymbol", new Mapping("f.trading_symbol", null)),
                Map.entry("broker", new Mapping("acc.name", JOIN_ACCOUNTS)),
                Map.entry("source", new Mapping("f.source", null))
        );

        public FnoTradesQueryBuilder(Map<String, FieldDef> fieldsMap, DateRangeResolver dateRangeResolver, SqlPredicates sqlPredicates) {
            super(MAPPINGS, fieldsMap, sqlPredicates, dateRangeResolver);
        }

        @Override
        public String idExpression() {
            return "f.id";
        }

        @Override
        protected String userScopePredicate(Map<String, Object> params, UUID userId) {
            params.put("userId", userId.toString());
            return "f.user_id = :userId";
        }

        @Override
        public String fromClause(Set<String> joins) {
            StringBuilder sb = new StringBuilder(" FROM fno_trades f");
            if (joins.contains(JOIN_ACCOUNTS)) {
                sb.append(" JOIN accounts acc ON acc.id = f.broker_account_id");
            }
            return sb.toString();
        }
    }

    private final FnoTradesQueryBuilder queryBuilder;

    public FnoTradesDatasource(SqlPredicates sqlPredicates, DateRangeResolver dateRangeResolver) {
        Map<String, FieldDef> fieldsMap = FIELDS.stream().collect(Collectors.toMap(FieldDef::name, f -> f));
        this.queryBuilder = new FnoTradesQueryBuilder(fieldsMap, dateRangeResolver, sqlPredicates);
    }

    @Override
    public String name() {
        return "fno_trades";
    }

    @Override
    public String label() {
        return "F&O Trades";
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
