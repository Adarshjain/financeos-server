package com.financeos.domain.report.engine;

import static org.junit.jupiter.api.Assertions.*;

import com.financeos.domain.report.datasource.impl.TransactionsDatasource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;

class TransactionQueryBuilderJoinsTest {

    private static final String LINKS_JOIN = " LEFT JOIN (SELECT m.transaction_id, MIN(l.type) AS link_type"
            + " FROM transaction_link_members m JOIN transaction_links l ON l.id = m.link_id"
            + " GROUP BY m.transaction_id) lk ON lk.transaction_id = t.id";

    private TransactionQueryBuilder queryBuilder;

    @BeforeEach
    void setUp() {
        DateRangeResolver resolver = new DateRangeResolver(4);
        TransactionsDatasource ds = new TransactionsDatasource(new SqlPredicates(resolver), resolver);
        queryBuilder = (TransactionQueryBuilder) ds.queryBuilder();
    }

    // ---------- link type ----------

    @Test
    void linkTypeIsAPlainJoinedColumnNotASubquery() {
        assertEquals("lk.link_type", TransactionQueryBuilder.LINK_TYPE);
        Set<String> joins = new HashSet<>();
        assertEquals("lk.link_type", queryBuilder.expression("linkType", joins));
        assertEquals(Set.of(TransactionQueryBuilder.JOIN_LINKS), joins);
        assertEquals("LINKS", TransactionQueryBuilder.JOIN_LINKS);
    }

    @Test
    void linkJoinIsEmittedWhenLinkTypeIsUsed() {
        String from = queryBuilder.fromClause(Set.of(TransactionQueryBuilder.JOIN_LINKS));
        assertEquals(" FROM transactions t" + LINKS_JOIN, from);
    }

    @Test
    void linkJoinIsAbsentWhenLinkTypeIsNotUsed() {
        Set<String> joins = new HashSet<>();
        queryBuilder.expression("amount", joins);
        String from = queryBuilder.fromClause(joins);
        assertFalse(from.contains("transaction_link_members"));
        assertFalse(from.contains("lk"));
        assertEquals(" FROM transactions t", from);
    }

    @Test
    void otherLinkDerivedFlagsDoNotRequireTheLinkJoin() {
        Set<String> joins = new HashSet<>();
        queryBuilder.expression("isRefundLeg", joins);
        assertFalse(joins.contains(TransactionQueryBuilder.JOIN_LINKS));
    }

    // ---------- cards vs categories aliases ----------

    @Test
    void cardsJoinUsesAliasCdAndCardholdersHangOffIt() {
        String from = queryBuilder.fromClause(Set.of(TransactionQueryBuilder.JOIN_CARDS));
        assertEquals(" FROM transactions t LEFT JOIN accounts a ON a.id = t.account_id"
                + " LEFT JOIN cards cd ON cd.id = t.card_id LEFT JOIN cardholders ch ON ch.id = cd.cardholder_id", from);
    }

    @Test
    void cardAndCategoryInOneQueryUseDistinctAliases() {
        String from = queryBuilder.fromClause(Set.of(TransactionQueryBuilder.JOIN_CARDS,
                TransactionQueryBuilder.JOIN_CATEGORIES));
        assertTrue(from.contains("LEFT JOIN cards cd ON cd.id = t.card_id"));
        assertTrue(from.contains("LEFT JOIN categories c ON c.id = tc.category_id"));
        assertFalse(from.contains("cards c ON"), "cards must not take the categories alias");
        assertEquals(1, from.split(" c ON ", -1).length - 1, "alias c is bound exactly once");
    }

    @Test
    void cardCategoryAndLinkJoinsCombine() {
        Set<String> joins = new HashSet<>();
        queryBuilder.expression("card", joins);
        queryBuilder.expression("category", joins);
        queryBuilder.expression("linkType", joins);
        String from = queryBuilder.fromClause(joins);
        assertTrue(from.contains("cards cd"));
        assertTrue(from.contains("categories c "));
        assertTrue(from.endsWith(LINKS_JOIN));
    }

    @Test
    void cardholderAndRelationshipDimensionsUseTheCardholdersJoin() {
        Set<String> joins = new HashSet<>();
        assertEquals("NVL(ch.person_name, 'Unattributed')", queryBuilder.expression("cardholder", joins));
        assertEquals("NVL(ch.relationship, 'Unattributed')", queryBuilder.expression("cardRelationship", joins));
        assertEquals(Set.of(TransactionQueryBuilder.JOIN_CARDS), joins);
    }
}
