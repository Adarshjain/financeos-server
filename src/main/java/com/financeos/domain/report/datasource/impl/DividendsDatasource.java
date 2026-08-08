package com.financeos.domain.report.datasource.impl;

import com.financeos.domain.instrument.InstrumentType;
import com.financeos.domain.investment.dividend.DividendType;
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
public class DividendsDatasource implements ReportDatasource {

    private static final List<Aggregation> NUMERIC_AGGS = List.of(
            Aggregation.SUM, Aggregation.AVG, Aggregation.COUNT, Aggregation.MIN, Aggregation.MAX);
    private static final List<Aggregation> NON_SUM_AGGS = List.of(
            Aggregation.AVG, Aggregation.MIN, Aggregation.MAX);

    private static final List<ReportType> ALL = List.of(ReportType.KPI, ReportType.CHART, ReportType.TABLE);
    private static final List<ReportType> CHART_TABLE = List.of(ReportType.CHART, ReportType.TABLE);
    private static final List<ReportType> TABLE_ONLY = List.of(ReportType.TABLE);

    private static final List<String> TYPE_VALUES = Arrays.stream(DividendType.values())
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
            new FieldDef("amount", "Amount", FieldType.NUMBER, FieldRole.MEASURE, NUMERIC_AGGS, null, null, ALL, "currency"),
            new FieldDef("tds", "TDS", FieldType.NUMBER, FieldRole.MEASURE, NUMERIC_AGGS, null, null, ALL, "currency"),
            new FieldDef("perUnit", "Per unit", FieldType.NUMBER, FieldRole.MEASURE, NON_SUM_AGGS, null, null, TABLE_ONLY, "currency"),
            new FieldDef("payDate", "Pay date", FieldType.DATE, FieldRole.DIMENSION, null, null, null, CHART_TABLE),
            new FieldDef("exDate", "Ex date", FieldType.DATE, FieldRole.DIMENSION, null, null, null, CHART_TABLE),
            new FieldDef("type", "Type", FieldType.ENUM, FieldRole.DIMENSION, null, TYPE_VALUES, null, CHART_TABLE),
            new FieldDef("broker", "Broker", FieldType.ENUM, FieldRole.DIMENSION, null, null, true, CHART_TABLE),
            new FieldDef("instrument", "Instrument", FieldType.ENUM, FieldRole.DIMENSION, null, null, true, CHART_TABLE),
            new FieldDef("instrumentType", "Instrument type", FieldType.ENUM, FieldRole.DIMENSION, null, INSTRUMENT_TYPE_VALUES, null, CHART_TABLE),
            new FieldDef("source", "Source", FieldType.ENUM, FieldRole.DIMENSION, null, SOURCE_VALUES, null, CHART_TABLE)
    );

    public static class DividendsQueryBuilder extends AbstractReportQueryBuilder {

        public static final String JOIN_HOLDINGS = "HOLDINGS";
        public static final String JOIN_INSTRUMENTS = "INSTRUMENTS";
        public static final String JOIN_ACCOUNTS = "ACCOUNTS";

        private static final Map<String, Mapping> MAPPINGS = Map.ofEntries(
                Map.entry("amount", new Mapping("d.amount", null)),
                Map.entry("tds", new Mapping("NVL(d.tds, 0)", null)),
                Map.entry("perUnit", new Mapping("d.per_unit", null)),
                Map.entry("payDate", new Mapping("d.pay_date", null)),
                Map.entry("exDate", new Mapping("d.ex_date", null)),
                Map.entry("type", new Mapping("d.type", null)),
                Map.entry("broker", new Mapping("acc.name", JOIN_ACCOUNTS)),
                Map.entry("instrument", new Mapping("ins.name", JOIN_INSTRUMENTS)),
                Map.entry("instrumentType", new Mapping("ins.type", JOIN_INSTRUMENTS)),
                Map.entry("source", new Mapping("d.source", null))
        );

        public DividendsQueryBuilder(Map<String, FieldDef> fieldsMap, DateRangeResolver dateRangeResolver, SqlPredicates sqlPredicates) {
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
            return "d.id";
        }

        @Override
        protected String userScopePredicate(Map<String, Object> params, UUID userId) {
            params.put("userId", userId.toString());
            return "d.user_id = :userId";
        }

        @Override
        public String fromClause(Set<String> joins) {
            StringBuilder sb = new StringBuilder(" FROM dividends d");
            if (joins.contains(JOIN_HOLDINGS) || joins.contains(JOIN_INSTRUMENTS) || joins.contains(JOIN_ACCOUNTS)) {
                sb.append(" JOIN holdings h ON h.id = d.holding_id");
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

    private final DividendsQueryBuilder queryBuilder;

    public DividendsDatasource(SqlPredicates sqlPredicates, DateRangeResolver dateRangeResolver) {
        Map<String, FieldDef> fieldsMap = FIELDS.stream().collect(Collectors.toMap(FieldDef::name, f -> f));
        this.queryBuilder = new DividendsQueryBuilder(fieldsMap, dateRangeResolver, sqlPredicates);
    }

    @Override
    public String name() {
        return "dividends";
    }

    @Override
    public String label() {
        return "Dividends";
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
