package com.financeos.domain.report.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anySet;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.financeos.domain.report.ReportType;
import com.financeos.domain.report.datasource.Aggregation;
import com.financeos.domain.report.datasource.ComputedReportDatasource;
import com.financeos.domain.report.datasource.DatasourceCatalog;
import com.financeos.domain.report.datasource.DatasourceCatalog.FieldDef;
import com.financeos.domain.report.datasource.FieldRole;
import com.financeos.domain.report.datasource.FieldType;
import com.financeos.domain.report.datasource.ReportDatasource;
import com.financeos.domain.report.definition.ChartDefinition;
import com.financeos.domain.report.definition.ChartType;
import com.financeos.domain.report.definition.DimensionRef;
import com.financeos.domain.report.definition.MeasureRef;
import jakarta.persistence.EntityManager;
import jakarta.persistence.Query;
import java.math.BigDecimal;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

/** A breakdown's {@code subValueLabels} follow the sub-dimension's labels, like {@code valueLabels} do. */
class ChartSubValueLabelsTest {

    private static final List<ReportType> ALL = List.of(ReportType.KPI, ReportType.CHART, ReportType.TABLE);
    private static final Map<String, String> SIDES = DatasourceCatalog.valueLabels("asset", "Asset", "liability", "Liability");
    private static final List<FieldDef> FIELDS = List.of(
            new FieldDef("name", "Name", FieldType.STRING, FieldRole.DIMENSION, null, null, null, ALL),
            new FieldDef("side", "Side", FieldType.ENUM, FieldRole.DIMENSION, null, List.of("asset", "liability"), null, ALL)
                    .withValueLabels(SIDES),
            new FieldDef("value", "Value", FieldType.NUMBER, FieldRole.MEASURE, List.of(Aggregation.SUM), null, null, ALL, "currency"));
    private static final MeasureRef SUM = new MeasureRef("value", Aggregation.SUM);
    private final UUID userId = UUID.randomUUID();

    private static ChartDefinition breakdown(DimensionRef dim, DimensionRef sub, ChartType type) {
        return new ChartDefinition(type, dim, null, SUM, List.of(), null, null, null, null, sub, null);
    }

    private ReportDatasource sqlDatasource() {
        ReportQueryBuilder qb = mock(ReportQueryBuilder.class);
        when(qb.buildWhere(any(), eq(userId), anyMap(), anySet())).thenReturn(" WHERE 1 = 1");
        when(qb.fromClause(anySet(), anyMap(), eq(userId))).thenReturn(" FROM t");
        when(qb.idExpression()).thenReturn("t.id");
        when(qb.expression(anyString(), anySet())).thenAnswer(inv -> "t." + inv.getArgument(0));
        ReportDatasource ds = mock(ReportDatasource.class);
        when(ds.queryBuilder()).thenReturn(qb);
        when(ds.fields()).thenReturn(FIELDS);
        Map<String, FieldDef> byName = new HashMap<>();
        FIELDS.forEach(f -> byName.put(f.name(), f));
        when(ds.field(anyString())).thenAnswer(inv -> byName.get((String) inv.getArgument(0)));
        return ds;
    }

    private ChartReportExecutor sqlExecutor() {
        ChartReportExecutor executor = new ChartReportExecutor(new DateRangeResolver(4));
        EntityManager em = mock(EntityManager.class);
        when(em.createNativeQuery(anyString())).thenAnswer(inv -> {
            String sql = inv.getArgument(0);
            Query q = mock(Query.class);
            when(q.getSingleResult()).thenReturn(2L);
            if (sql.contains("sub_key")) {
                when(q.getResultList()).thenReturn(Arrays.<Object[]>asList(new Object[]{"Alpha", "asset", new BigDecimal("5"), 1L}));
            } else {
                when(q.getResultList()).thenReturn(Arrays.<Object[]>asList(
                        new Object[]{"Alpha", new BigDecimal("5"), 1L, 1L, new BigDecimal("5")}));
            }
            return q;
        });
        ReflectionTestUtils.setField(executor, "em", em);
        return executor;
    }

    @Test
    void sqlBreakdownLabelsAnEnumSubDimension() {
        ChartData data = sqlExecutor().execute(breakdown(new DimensionRef("name", null), new DimensionRef("side", null),
                ChartType.BREAKDOWN), sqlDatasource(), userId);
        assertEquals(SIDES, data.subValueLabels());
        assertEquals("asset", data.breakdown().rows().get(0).subRows().get(0).label());
        assertNull(data.valueLabels());
    }

    @Test
    void sqlSubValueLabelsStayNullForAPlainSubDimensionOrNoSubDimension() {
        ChartData plainSub = sqlExecutor().execute(breakdown(new DimensionRef("side", null), new DimensionRef("name", null),
                ChartType.BREAKDOWN), sqlDatasource(), userId);
        assertNull(plainSub.subValueLabels());
        ChartData none = sqlExecutor().execute(breakdown(new DimensionRef("name", null), null, ChartType.BREAKDOWN),
                sqlDatasource(), userId);
        assertNull(none.subValueLabels());
        ChartData bar = sqlExecutor().execute(breakdown(new DimensionRef("name", null), null, ChartType.BAR),
                sqlDatasource(), userId);
        assertNull(bar.subValueLabels());
    }

    @Test
    void inMemoryBreakdownLabelsAnEnumSubDimension() {
        ComputedReportDatasource ds = new ComputedReportDatasource() {
            @Override public String name() { return "labelled"; }
            @Override public String label() { return "Labelled"; }
            @Override public List<FieldDef> fields() { return FIELDS; }
            @Override public List<Map<String, Object>> rows() {
                Map<String, Object> row = new HashMap<>();
                row.put("name", "Alpha");
                row.put("side", "liability");
                row.put("value", new BigDecimal("7"));
                return List.of(row);
            }
        };
        InMemoryReportExecutor executor = new InMemoryReportExecutor(new DateRangeResolver(4));

        ChartData data = executor.execute(breakdown(new DimensionRef("name", null), new DimensionRef("side", null),
                ChartType.BREAKDOWN), ds, null);
        assertEquals(SIDES, data.subValueLabels());
        assertEquals("liability", data.breakdown().rows().get(0).subRows().get(0).label());

        ChartData none = executor.execute(breakdown(new DimensionRef("name", null), null, ChartType.BREAKDOWN), ds, null);
        assertNull(none.subValueLabels());
    }
}
