package com.financeos.domain.report.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.financeos.core.time.AppTime;
import com.financeos.domain.report.datasource.impl.TransactionsDatasource;
import com.financeos.domain.report.definition.FilterClause;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** A report filtered on next_x_days binds the window [today, today + N - 1]. */
class TransactionQueryBuilderNextXDaysTest {

    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");
    private static final LocalDate TODAY = LocalDate.of(2026, 10, 8);

    private TransactionQueryBuilder queryBuilder;

    @BeforeEach
    void setUp() {
        AppTime.useClock(Clock.fixed(TODAY.atTime(10, 0).atZone(IST).toInstant(), IST));
        DateRangeResolver dateRangeResolver = new DateRangeResolver(4);
        SqlPredicates sqlPredicates = new SqlPredicates(dateRangeResolver);
        queryBuilder = (TransactionQueryBuilder) new TransactionsDatasource(sqlPredicates, dateRangeResolver).queryBuilder();
    }

    @AfterEach
    void tearDown() {
        AppTime.reset();
    }

    @Test
    void nextXDaysBindsTodayThroughTodayPlusNMinusOne() {
        Map<String, Object> params = new HashMap<>();
        String where = queryBuilder.buildWhere(
                List.of(new FilterClause("date", "next_x_days", JsonNodeFactory.instance.objectNode().put("amount", 10))),
                UUID.randomUUID(), params, new HashSet<>());
        assertTrue(where.contains("BETWEEN :f0a AND :f0b"), where);
        assertEquals(TODAY, params.get("f0a"));
        assertEquals(LocalDate.of(2026, 10, 17), params.get("f0b"));
    }
}
