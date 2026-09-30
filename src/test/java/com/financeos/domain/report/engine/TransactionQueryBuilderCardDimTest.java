package com.financeos.domain.report.engine;

import static org.junit.jupiter.api.Assertions.*;

import com.financeos.domain.report.datasource.impl.TransactionsDatasource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;

class TransactionQueryBuilderCardDimTest {

    private TransactionQueryBuilder queryBuilder;

    @BeforeEach
    void setUp() {
        DateRangeResolver resolver = new DateRangeResolver(4);
        TransactionsDatasource ds = new TransactionsDatasource(new SqlPredicates(resolver), resolver);
        queryBuilder = (TransactionQueryBuilder) ds.queryBuilder();
    }

    @Test
    void cardDimensionTestsTheCardRowNotTheConcatenation() {
        // Oracle treats NULL as '' in ||, so a card-less transaction must be detected on c.last4.
        String expected = "CASE WHEN c.last4 IS NULL THEN 'Unattributed' ELSE a.name || ' •••• ' || c.last4 END";
        assertEquals(expected, TransactionQueryBuilder.CARD_DIM);

        Set<String> joins = new HashSet<>();
        assertEquals(expected, queryBuilder.expression("card", joins));
        assertTrue(joins.contains(TransactionQueryBuilder.JOIN_CARDS));
    }
}
