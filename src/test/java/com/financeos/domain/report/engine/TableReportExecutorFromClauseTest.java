package com.financeos.domain.report.engine;

import com.financeos.domain.report.datasource.DatasourceCatalog.FieldDef;
import com.financeos.domain.report.datasource.FieldRole;
import com.financeos.domain.report.datasource.FieldType;
import com.financeos.domain.report.datasource.ReportDatasource;
import com.financeos.domain.report.definition.RawTableDefinition;
import com.financeos.domain.report.definition.TableMode;
import jakarta.persistence.EntityManager;
import jakarta.persistence.Query;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anySet;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/** A raw table builds its FROM (which may bind the billing-cycle table) once, shared by count and page. */
class TableReportExecutorFromClauseTest {

    @Test
    void rawTableBuildsTheFromClauseOnceForTheCountAndThePage() {
        UUID userId = UUID.randomUUID();
        ReportQueryBuilder qb = mock(ReportQueryBuilder.class);
        when(qb.buildWhere(any(), eq(userId), anyMap(), anySet())).thenReturn(" WHERE 1 = 1");
        when(qb.fromClause(anySet(), anyMap(), eq(userId))).thenReturn(" FROM transactions t");
        when(qb.idExpression()).thenReturn("t.id");
        when(qb.expression(eq("description"), anySet())).thenReturn("t.description");

        ReportDatasource ds = mock(ReportDatasource.class);
        when(ds.name()).thenReturn("transactions");
        when(ds.queryBuilder()).thenReturn(qb);
        FieldDef description = new FieldDef("description", "Description", FieldType.STRING, FieldRole.DIMENSION, null, null, null, List.of());
        when(ds.field("description")).thenReturn(description);
        when(ds.fields()).thenReturn(List.of(description));

        EntityManager em = mock(EntityManager.class);
        List<String> sqls = new java.util.ArrayList<>();
        when(em.createNativeQuery(anyString())).thenAnswer(inv -> {
            sqls.add(inv.getArgument(0));
            Query q = mock(Query.class);
            when(q.getSingleResult()).thenReturn(0L);
            when(q.getResultList()).thenReturn(List.of());
            return q;
        });
        TableReportExecutor executor = new TableReportExecutor(new DateRangeResolver(4));
        ReflectionTestUtils.setField(executor, "em", em);

        executor.execute(new RawTableDefinition(TableMode.RAW, List.of("description"), List.of(), null), ds, userId, 0, 10);

        ArgumentCaptor<Set<String>> joins = ArgumentCaptor.forClass(Set.class);
        ArgumentCaptor<Map<String, Object>> params = ArgumentCaptor.forClass(Map.class);
        verify(qb, times(1)).fromClause(joins.capture(), params.capture(), eq(userId));
        verify(qb, never()).fromClause(anySet());
        assertEquals(2, sqls.size());
        assertEquals("SELECT COUNT(*) FROM transactions t WHERE 1 = 1", sqls.get(0));
        assertTrue(sqls.get(1).contains(" FROM transactions t WHERE 1 = 1"), sqls.get(1));
    }
}
