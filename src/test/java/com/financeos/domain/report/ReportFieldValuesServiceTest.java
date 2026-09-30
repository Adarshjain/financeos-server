package com.financeos.domain.report;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

import com.financeos.core.exception.ValidationException;
import com.financeos.core.security.UserContext;
import com.financeos.domain.report.datasource.Aggregation;
import com.financeos.domain.report.datasource.ComputedReportDatasource;
import com.financeos.domain.report.datasource.DatasourceCatalog;
import com.financeos.domain.report.datasource.DatasourceCatalog.FieldDef;
import com.financeos.domain.report.datasource.DatasourceRegistry;
import com.financeos.domain.report.datasource.FieldRole;
import com.financeos.domain.report.datasource.FieldType;
import com.financeos.domain.report.datasource.ReportDatasource;
import com.financeos.domain.report.engine.DateRangeResolver;
import com.financeos.domain.report.engine.ReportQueryBuilder;
import com.financeos.domain.report.engine.SqlPredicates;
import com.financeos.domain.report.engine.TransactionQueryBuilder;
import jakarta.persistence.EntityManager;
import jakarta.persistence.Query;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.UUID;

class ReportFieldValuesServiceTest {

    private static final FieldDef ACCOUNT = new FieldDef("account", "Account", FieldType.ENUM, FieldRole.DIMENSION,
            null, null, true, List.of());
    private static final FieldDef CATEGORY = new FieldDef("category", "Category", FieldType.ENUM, FieldRole.DIMENSION,
            null, null, true, List.of());
    private static final FieldDef TYPE = new FieldDef("type", "Type", FieldType.ENUM, FieldRole.DIMENSION,
            null, List.of("DEBIT", "CREDIT"), null, List.of());
    private static final FieldDef AMOUNT = new FieldDef("amount", "Amount", FieldType.NUMBER, FieldRole.MEASURE,
            List.of(Aggregation.SUM), null, null, List.of(), "currency");

    private EntityManager em;
    private Query query;
    private UUID userId;

    @BeforeEach
    void setUp() {
        userId = UUID.randomUUID();
        UserContext.setCurrentUserId(userId);
        em = mock(EntityManager.class);
        query = mock(Query.class);
        when(em.createNativeQuery(anyString())).thenReturn(query);
    }

    @AfterEach
    void tearDown() {
        UserContext.clear();
    }

    private ReportFieldValuesService service(ReportDatasource... datasources) {
        ReportFieldValuesService s = new ReportFieldValuesService(
                new DatasourceRegistry(List.of(datasources), new DatasourceCatalog()));
        ReflectionTestUtils.setField(s, "em", em);
        return s;
    }

    private static SqlDatasource sqlDatasource(FieldDef... fields) {
        DateRangeResolver resolver = new DateRangeResolver(4);
        Map<String, FieldDef> map = new java.util.LinkedHashMap<>();
        for (FieldDef f : fields) map.put(f.name(), f);
        return new SqlDatasource("transactions", List.of(fields), new TransactionQueryBuilder(map, resolver, new SqlPredicates(resolver)));
    }

    private static ComputedDatasource computed(List<Map<String, Object>> rows, FieldDef... fields) {
        return new ComputedDatasource("computed", List.of(fields), rows);
    }

    private static Map<String, Object> row(Object... kv) {
        Map<String, Object> m = new java.util.HashMap<>();
        for (int i = 0; i < kv.length; i += 2) m.put((String) kv[i], kv[i + 1]);
        return m;
    }

    // ---------- registry / no dynamic fields ----------

    @Test
    void unknownDatasourceIsAValidationError() {
        ValidationException ex = assertThrows(ValidationException.class, () -> service().values("nope"));
        assertTrue(ex.getMessage().contains("Unknown report datasource: nope"));
    }

    @Test
    void datasourceWithoutDynamicFieldsGivesEmptyMapAndNeverQueries() {
        assertEquals(Map.of(), service(sqlDatasource(TYPE, AMOUNT)).values("transactions"));
        assertEquals(Map.of(), service(computed(List.of(row("type", "DEBIT")), TYPE, AMOUNT)).values("computed"));
        verifyNoInteractions(em);
    }

    // ---------- SQL datasources ----------

    @Test
    void sqlDatasourceBuildsDistinctQueryPerDynamicFieldOnly() {
        when(query.getResultList()).thenReturn(List.of("HDFC", "Axis", "icici"));

        Map<String, List<String>> values = service(sqlDatasource(ACCOUNT, TYPE, AMOUNT)).values("transactions");

        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        verify(em, times(1)).createNativeQuery(sql.capture());
        assertEquals("SELECT DISTINCT a.name AS v FROM transactions t LEFT JOIN accounts a ON a.id = t.account_id"
                + " WHERE t.user_id = :userId ORDER BY 1 FETCH FIRST 500 ROWS ONLY", sql.getValue());
        assertEquals(List.of("account"), List.copyOf(values.keySet()));
    }

    @Test
    void sqlDatasourceBindsTheUserScopeParameter() {
        when(query.getResultList()).thenReturn(List.of());

        service(sqlDatasource(ACCOUNT)).values("transactions");

        verify(query).setParameter("userId", userId.toString());
        verify(query, times(1)).setParameter(anyString(), any());
    }

    @Test
    void sqlValuesAreSortedCaseInsensitivelyAndNullsDropped() {
        when(query.getResultList()).thenReturn(Arrays.asList("hdfc", null, "Axis", "Zeta", "icici"));

        Map<String, List<String>> values = service(sqlDatasource(ACCOUNT)).values("transactions");

        assertEquals(List.of("Axis", "hdfc", "icici", "Zeta"), values.get("account"));
    }

    @Test
    void sqlValuesAreStringified() {
        when(query.getResultList()).thenReturn(List.of(2, 10));
        assertEquals(List.of("10", "2"), service(sqlDatasource(ACCOUNT)).values("transactions").get("account"));
    }

    @Test
    void sqlDatasourceRunsOneQueryPerDynamicFieldWithItsOwnJoins() {
        when(query.getResultList()).thenReturn(List.of("x"));

        Map<String, List<String>> values = service(sqlDatasource(ACCOUNT, CATEGORY)).values("transactions");

        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        verify(em, times(2)).createNativeQuery(sql.capture());
        assertTrue(sql.getAllValues().get(0).startsWith("SELECT DISTINCT a.name AS v FROM transactions t LEFT JOIN accounts a"));
        assertTrue(sql.getAllValues().get(0).contains("LEFT JOIN accounts a") && !sql.getAllValues().get(0).contains("categories"));
        assertTrue(sql.getAllValues().get(1).startsWith("SELECT DISTINCT c.name AS v FROM transactions t"));
        assertTrue(sql.getAllValues().get(1).contains("LEFT JOIN categories c ON c.id = tc.category_id"));
        assertEquals(List.of("account", "category"), List.copyOf(values.keySet()));
    }

    // ---------- computed datasources ----------

    @Test
    void computedValuesAreDistinctNonNullAndCaseInsensitivelySorted() {
        var ds = computed(List.of(
                row("account", "hdfc"),
                row("account", "Axis"),
                row("account", null),
                row("account", "hdfc"),
                row("account", "Zeta"),
                row("other", "ignored")), ACCOUNT, AMOUNT);

        Map<String, List<String>> values = service(ds).values("computed");

        assertEquals(List.of("account"), List.copyOf(values.keySet()));
        assertEquals(List.of("Axis", "hdfc", "Zeta"), values.get("account"));
    }

    @Test
    void computedListValuesAreFlattenedAndNullElementsSkipped() {
        var ds = computed(List.of(
                row("category", Arrays.asList("Travel", "Dining", null)),
                row("category", List.of("dining", "Food")),
                row("category", List.of())), CATEGORY);

        assertEquals(List.of("Dining", "Food", "Travel"), service(ds).values("computed").get("category"));
    }

    @Test
    void computedValuesCoverEveryDynamicFieldAndSkipNonDynamicOnes() {
        var ds = computed(List.of(row("account", "HDFC", "category", List.of("Dining"), "type", "DEBIT")),
                ACCOUNT, CATEGORY, TYPE);

        Map<String, List<String>> values = service(ds).values("computed");

        assertEquals(List.of("account", "category"), List.copyOf(values.keySet()));
        assertEquals(List.of("HDFC"), values.get("account"));
        assertEquals(List.of("Dining"), values.get("category"));
    }

    @Test
    void computedValuesAreCappedAt500() {
        List<Map<String, Object>> rows = new ArrayList<>();
        for (int i = 0; i < 600; i++) {
            rows.add(row("account", String.format("acct-%04d", i)));
        }

        List<String> values = service(computed(rows, ACCOUNT)).values("computed").get("account");

        assertEquals(500, values.size());
        assertEquals("acct-0000", values.get(0));
        assertEquals("acct-0499", values.get(499));
    }

    @Test
    void computedNullRowsGiveEmptyLists() {
        var ds = new ComputedDatasource("computed", List.of(ACCOUNT), null);
        assertEquals(Map.of("account", List.of()), service(ds).values("computed"));
    }

    @Test
    void computedDatasourceNeverTouchesTheDatabase() {
        service(computed(List.of(row("account", "HDFC")), ACCOUNT)).values("computed");
        verifyNoInteractions(em);
    }

    // ---------- fakes ----------

    private record SqlDatasource(String name, List<FieldDef> fields, ReportQueryBuilder builder) implements ReportDatasource {
        @Override public String label() { return name; }
        @Override public ReportQueryBuilder queryBuilder() { return builder; }
    }

    private record ComputedDatasource(String name, List<FieldDef> fields, List<Map<String, Object>> data)
            implements ComputedReportDatasource {
        @Override public String label() { return name; }
        @Override public List<Map<String, Object>> rows() { return data; }
    }
}
