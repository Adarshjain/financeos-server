package com.financeos.domain.transaction;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.financeos.domain.report.datasource.DatasourceCatalog;
import com.financeos.domain.report.definition.FilterClause;
import com.financeos.domain.report.engine.DateRangeResolver;
import com.financeos.domain.report.engine.SqlPredicates;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.Pageable;

import java.util.List;
import java.util.UUID;

class TransactionSearchAmountAndReasonTest {

    private TransactionListQueryBuilder queryBuilder;
    private final ObjectMapper mapper = new ObjectMapper();

    @BeforeEach
    void setUp() {
        queryBuilder = new TransactionListQueryBuilder(
                new SqlPredicates(new DateRangeResolver(4)), new DatasourceCatalog());
    }

    private TransactionListQueryBuilder.QueryResult search(String text) {
        return queryBuilder.buildDataQuery(UUID.randomUUID(),
                new TransactionSearchCriteria(List.of(), text), Pageable.unpaged());
    }

    @Test
    void reviewReasonFilter_acceptsEveryEnumValue() {
        for (ReviewReason reason : ReviewReason.values()) {
            FilterClause filter = new FilterClause("reviewReason", "is", mapper.valueToTree(reason.name()));
            assertDoesNotThrow(() -> queryBuilder.buildDataQuery(UUID.randomUUID(),
                    new TransactionSearchCriteria(List.of(filter), null), Pageable.unpaged()), reason.name());
        }
    }

    @Test
    void reviewReasonFilter_rejectsUnknownValue() {
        FilterClause filter = new FilterClause("reviewReason", "is", mapper.valueToTree("OTHER"));
        assertThrows(com.financeos.core.exception.ValidationException.class, () -> queryBuilder.buildDataQuery(
                UUID.randomUUID(), new TransactionSearchCriteria(List.of(filter), null), Pageable.unpaged()));
    }

    @Test
    void numericSearchText_parsesNumbers() {
        assertEquals("3600", TransactionListQueryBuilder.numericSearchText("3600"));
        assertEquals("1249.5", TransactionListQueryBuilder.numericSearchText(" 1249.5 "));
        assertEquals("1249600.5", TransactionListQueryBuilder.numericSearchText("₹1,249,600.50"));
        assertEquals("3600.", TransactionListQueryBuilder.numericSearchText("3,600.00"));
        assertEquals("250", TransactionListQueryBuilder.numericSearchText("-250"));
        assertEquals(".5", TransactionListQueryBuilder.numericSearchText(".5"));
    }

    @Test
    void numericSearchText_rejectsNonNumbers() {
        for (String s : new String[] {"swiggy", "12abc", "1.2.3", ".", "-", "", "  ", "12 34", "a1"}) {
            assertNull(TransactionListQueryBuilder.numericSearchText(s), s);
        }
        assertNull(TransactionListQueryBuilder.numericSearchText(null));
    }

    @Test
    void numericSearch_addsAmountPredicateWithBindParam() {
        var result = search("3,600");
        assertTrue(result.sql().contains("TO_CHAR(ABS(sub.signed_amount)"));
        assertEquals("%3600%", result.params().get("qAmt"));
        assertFalse(result.sql().contains("3600"));
    }

    @Test
    void textSearch_leavesAmountPredicateOut() {
        var result = search("swiggy");
        assertFalse(result.sql().contains("TO_CHAR(ABS(sub.signed_amount)"));
        assertFalse(result.params().containsKey("qAmt"));
        assertTrue(result.params().containsKey("q"));
    }

    @Test
    void numericSearch_stillSearchesText() {
        var result = search("7eleven 24");
        assertFalse(result.params().containsKey("qAmt"));
        var numeric = search("24");
        assertTrue(numeric.sql().contains("LOWER(sub.description)"));
        assertTrue(numeric.params().containsKey("q"));
    }

    @Test
    void countQuery_alsoCarriesAmountPredicate() {
        var result = queryBuilder.buildCountQuery(UUID.randomUUID(), new TransactionSearchCriteria(List.of(), "99.5"));
        assertTrue(result.sql().contains("TO_CHAR(ABS(sub.signed_amount)"));
        assertEquals("%99.5%", result.params().get("qAmt"));
    }
}
