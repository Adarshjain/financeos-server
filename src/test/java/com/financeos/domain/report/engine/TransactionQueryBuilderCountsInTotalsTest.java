package com.financeos.domain.report.engine;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.financeos.domain.report.datasource.impl.TransactionsDatasource;
import com.financeos.domain.report.definition.FilterClause;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The countsInTotals rule's SQL has one branch per way a row stops counting, and no joins of its own. */
class TransactionQueryBuilderCountsInTotalsTest {

    private TransactionQueryBuilder queryBuilder;

    @BeforeEach
    void setUp() {
        DateRangeResolver resolver = new DateRangeResolver(4);
        queryBuilder = (TransactionQueryBuilder) new TransactionsDatasource(new SqlPredicates(resolver), resolver).queryBuilder();
    }

    @Test
    void theRuleHasABranchForExcludedTransferLegAndDefaultExcludedCategoryNotSetByTheUser() {
        String sql = TransactionQueryBuilder.COUNTS_IN_TOTALS;

        assertTrue(sql.contains("CAST(t.is_excluded AS NUMBER(1)) = 1"));
        assertTrue(sql.contains(TransactionQueryBuilder.IS_TRANSFER_LEG + " = 1"));
        assertTrue(sql.contains("t.category_key IN (SELECT dc.category_key FROM txn_categories dc"
                + " WHERE CAST(dc.excluded_by_default AS NUMBER(1)) = 1)"));
        assertTrue(sql.contains("NVL(CAST(t.exclude_set_by_user AS NUMBER(1)), 0) <> 1"));
        assertTrue(sql.endsWith("THEN 0 ELSE 1 END)"));
    }

    @Test
    void theFieldMapsToTheRuleWithoutJoinsAndIsAFilterOnly() {
        Set<String> joins = new HashSet<>();

        assertEquals(TransactionQueryBuilder.COUNTS_IN_TOTALS, queryBuilder.expression("countsInTotals", joins));
        assertTrue(joins.isEmpty());
    }

    @Test
    void isTrueAndIsFalseFilterOnTheRule() {
        for (boolean value : new boolean[] {true, false}) {
            Map<String, Object> params = new HashMap<>();
            String where = queryBuilder.buildWhere(List.of(new FilterClause("countsInTotals", "is",
                    JsonNodeFactory.instance.booleanNode(value))), UUID.randomUUID(), params, new HashSet<>());

            assertTrue(where.contains(TransactionQueryBuilder.COUNTS_IN_TOTALS), where);
            assertTrue(params.containsValue(value ? 1 : 0) || params.containsValue(value), params.toString());
        }
    }

    @Test
    void subCategoryGroupingLabelsUseCategoryDotSubAndCategoryDotOthers() {
        String dim = TransactionQueryBuilder.SUBCATEGORY_DIM;

        assertTrue(dim.contains("COALESCE(xs.label, t.subcategory_key)"));
        assertTrue(dim.contains("'Uncategorized'"));
        assertTrue(dim.contains("COALESCE(xsc.name, t.category_key) || ' · Others'"));
        assertEquals(" · ", TransactionQueryBuilder.SEPARATOR);
    }
}
