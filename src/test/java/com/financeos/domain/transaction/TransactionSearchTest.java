package com.financeos.domain.transaction;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.financeos.core.exception.ValidationException;
import com.financeos.domain.report.datasource.DatasourceCatalog;
import com.financeos.domain.report.definition.FilterClause;
import com.financeos.domain.report.engine.DateRangeResolver;
import com.financeos.domain.report.engine.SqlPredicates;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;

import java.util.List;
import java.util.UUID;

class TransactionSearchTest {

    private TransactionListQueryBuilder queryBuilder;
    private final ObjectMapper mapper = new ObjectMapper();

    @BeforeEach
    void setUp() {
        DatasourceCatalog catalog = new DatasourceCatalog();
        DateRangeResolver dateRangeResolver = new DateRangeResolver(4);
        SqlPredicates sqlPredicates = new SqlPredicates(dateRangeResolver);
        queryBuilder = new TransactionListQueryBuilder(sqlPredicates, catalog);
    }

    @Test
    void validateSort_validFields_success() {
        Pageable pageable = PageRequest.of(0, 10, Sort.by(Sort.Direction.ASC, "date", "amount", "createdAt", "id"));
        TransactionSearchCriteria criteria = new TransactionSearchCriteria(List.of(), null);
        
        assertDoesNotThrow(() -> queryBuilder.buildDataQuery(UUID.randomUUID(), criteria, pageable));
    }

    @Test
    void validateSort_invalidField_throwsValidationException() {
        Pageable pageable = PageRequest.of(0, 10, Sort.by(Sort.Direction.ASC, "description"));
        TransactionSearchCriteria criteria = new TransactionSearchCriteria(List.of(), null);
        
        assertThrows(ValidationException.class, () -> 
                queryBuilder.buildDataQuery(UUID.randomUUID(), criteria, pageable));
    }

    @Test
    void validateFilter_unknownField_throwsValidationException() {
        FilterClause filter = new FilterClause("unknownField", "is", mapper.valueToTree("val"));
        TransactionSearchCriteria criteria = new TransactionSearchCriteria(List.of(filter), null);
        
        assertThrows(ValidationException.class, () -> 
                queryBuilder.buildDataQuery(UUID.randomUUID(), criteria, Pageable.unpaged()));
    }

    @Test
    void validateFilter_invalidOperator_throwsValidationException() {
        FilterClause filter = new FilterClause("type", "contains", mapper.valueToTree("val"));
        TransactionSearchCriteria criteria = new TransactionSearchCriteria(List.of(filter), null);
        
        assertThrows(ValidationException.class, () -> 
                queryBuilder.buildDataQuery(UUID.randomUUID(), criteria, Pageable.unpaged()));
    }

    @Test
    void validateFilter_betweenOperatorWithoutFromTo_throwsValidationException() {
        FilterClause filter = new FilterClause("amount", "between", mapper.valueToTree("50"));
        TransactionSearchCriteria criteria = new TransactionSearchCriteria(List.of(filter), null);
        
        assertThrows(ValidationException.class, () -> 
                queryBuilder.buildDataQuery(UUID.randomUUID(), criteria, Pageable.unpaged()));
    }

    @Test
    void validateFilter_betweenOperatorWithFromTo_success() {
        ObjectNode betweenValue = mapper.createObjectNode();
        betweenValue.put("from", 10);
        betweenValue.put("to", 100);
        FilterClause filter = new FilterClause("amount", "between", betweenValue);
        TransactionSearchCriteria criteria = new TransactionSearchCriteria(List.of(filter), null);
        
        assertDoesNotThrow(() -> 
                queryBuilder.buildDataQuery(UUID.randomUUID(), criteria, Pageable.unpaged()));
    }

    @Test
    void validateFilter_inOperatorWithoutArray_throwsValidationException() {
        FilterClause filter = new FilterClause("type", "in", mapper.valueToTree("DEBIT"));
        TransactionSearchCriteria criteria = new TransactionSearchCriteria(List.of(filter), null);
        
        assertThrows(ValidationException.class, () -> 
                queryBuilder.buildDataQuery(UUID.randomUUID(), criteria, Pageable.unpaged()));
    }

    @Test
    void validateFilter_inOperatorWithEmptyArray_throwsValidationException() {
        ArrayNode array = mapper.createArrayNode();
        FilterClause filter = new FilterClause("type", "in", array);
        TransactionSearchCriteria criteria = new TransactionSearchCriteria(List.of(filter), null);
        
        assertThrows(ValidationException.class, () -> 
                queryBuilder.buildDataQuery(UUID.randomUUID(), criteria, Pageable.unpaged()));
    }

    @Test
    void validateFilter_staticEnumInvalidValue_throwsValidationException() {
        FilterClause filter = new FilterClause("type", "is", mapper.valueToTree("INVALID_TYPE"));
        TransactionSearchCriteria criteria = new TransactionSearchCriteria(List.of(filter), null);
        
        assertThrows(ValidationException.class, () -> 
                queryBuilder.buildDataQuery(UUID.randomUUID(), criteria, Pageable.unpaged()));
    }

    @Test
    void validateFilter_staticEnumValidValue_success() {
        FilterClause filter = new FilterClause("type", "is", mapper.valueToTree("DEBIT"));
        TransactionSearchCriteria criteria = new TransactionSearchCriteria(List.of(filter), null);
        
        assertDoesNotThrow(() -> 
                queryBuilder.buildDataQuery(UUID.randomUUID(), criteria, Pageable.unpaged()));
    }

    @Test
    void validateFilter_sourceRealEnumValues_success() {
        FilterClause filter = new FilterClause("source", "is", mapper.valueToTree("gmail_transaction_alert"));
        TransactionSearchCriteria criteria = new TransactionSearchCriteria(List.of(filter), null);
        
        assertDoesNotThrow(() -> 
                queryBuilder.buildDataQuery(UUID.randomUUID(), criteria, Pageable.unpaged()));
        
        FilterClause collapsedFilter = new FilterClause("source", "is", mapper.valueToTree("gmail"));
        TransactionSearchCriteria collapsedCriteria = new TransactionSearchCriteria(List.of(collapsedFilter), null);
        assertThrows(ValidationException.class, () -> 
                queryBuilder.buildDataQuery(UUID.randomUUID(), collapsedCriteria, Pageable.unpaged()));
    }

    @Test
    void buildQuery_tenancyPinning_enforced() {
        UUID userId = UUID.randomUUID();
        TransactionSearchCriteria criteria = new TransactionSearchCriteria(List.of(), null);
        
        TransactionListQueryBuilder.QueryResult dataQuery = queryBuilder.buildDataQuery(userId, criteria, Pageable.unpaged());
        TransactionListQueryBuilder.QueryResult countQuery = queryBuilder.buildCountQuery(userId, criteria);
        
        assertTrue(dataQuery.sql().contains("t.user_id = :userId"));
        assertTrue(countQuery.sql().contains("t.user_id = :userId"));
        assertEquals(userId.toString(), dataQuery.params().get("userId"));
        assertEquals(userId.toString(), countQuery.params().get("userId"));
    }

    @Test
    void buildQuery_broadSearch_enforced() {
        TransactionSearchCriteria criteria = new TransactionSearchCriteria(List.of(), "coffee");
        TransactionListQueryBuilder.QueryResult dataQuery = queryBuilder.buildDataQuery(UUID.randomUUID(), criteria, Pageable.unpaged());
        
        assertTrue(dataQuery.sql().contains("LOWER(sub.description)        LIKE :q"));
        assertTrue(dataQuery.sql().contains("LOWER(sub.sourced_description) LIKE :q"));
        assertTrue(dataQuery.sql().contains("LOWER(a.name)                 LIKE :q"));
        assertTrue(dataQuery.sql().contains("EXISTS (SELECT 1 FROM transaction_categories tcx"));
        assertEquals("%coffee%", dataQuery.params().get("q"));
    }

    @Test
    void buildQuery_tieBreakers_appended() {
        Pageable pageable = PageRequest.of(0, 10, Sort.by(Sort.Direction.ASC, "amount"));
        TransactionSearchCriteria criteria = new TransactionSearchCriteria(List.of(), null);
        
        TransactionListQueryBuilder.QueryResult dataQuery = queryBuilder.buildDataQuery(UUID.randomUUID(), criteria, pageable);
        
        assertTrue(dataQuery.sql().contains("ORDER BY sub.signed_amount ASC, sub.transaction_date DESC, sub.created_at DESC, sub.id DESC"));
    }

    @Test
    void findFiltered_validationHoisted_throwsValidationException() throws Exception {
        jakarta.persistence.EntityManager entityManager = mock(jakarta.persistence.EntityManager.class);
        TransactionRepositoryCustomImpl repository = new TransactionRepositoryCustomImpl(queryBuilder);
        
        java.lang.reflect.Field field = TransactionRepositoryCustomImpl.class.getDeclaredField("entityManager");
        field.setAccessible(true);
        field.set(repository, entityManager);

        FilterClause invalidFilter = new FilterClause("unknownField", "is", mapper.valueToTree("val"));
        TransactionSearchCriteria criteria = new TransactionSearchCriteria(List.of(invalidFilter), null);

        assertThrows(ValidationException.class, () -> 
                repository.findFiltered(UUID.randomUUID(), criteria, Pageable.unpaged()));
        
        verifyNoInteractions(entityManager);
    }

    @Test
    void validateFilter_coveredByStatement_success() {
        FilterClause filter = new FilterClause("coveredByStatement", "is", mapper.valueToTree(true));
        TransactionSearchCriteria criteria = new TransactionSearchCriteria(List.of(filter), null);
        
        TransactionListQueryBuilder.QueryResult dataQuery = queryBuilder.buildDataQuery(UUID.randomUUID(), criteria, Pageable.unpaged());
        assertTrue(dataQuery.sql().contains("(a.last_statement_date IS NULL OR sub.transaction_date <= a.last_statement_date)"));
        
        FilterClause filterFalse = new FilterClause("coveredByStatement", "is", mapper.valueToTree(false));
        TransactionSearchCriteria criteriaFalse = new TransactionSearchCriteria(List.of(filterFalse), null);
        TransactionListQueryBuilder.QueryResult dataQueryFalse = queryBuilder.buildDataQuery(UUID.randomUUID(), criteriaFalse, Pageable.unpaged());
        assertTrue(dataQueryFalse.sql().contains("(a.last_statement_date IS NOT NULL AND sub.transaction_date > a.last_statement_date)"));
    }

    @Test
    void validateFilter_coveredByStatement_invalidOperator_throwsValidationException() {
        FilterClause filter = new FilterClause("coveredByStatement", "contains", mapper.valueToTree(true));
        TransactionSearchCriteria criteria = new TransactionSearchCriteria(List.of(filter), null);
        
        assertThrows(ValidationException.class, () -> 
                queryBuilder.buildDataQuery(UUID.randomUUID(), criteria, Pageable.unpaged()));
    }
}
