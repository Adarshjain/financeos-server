package com.financeos.domain.transaction;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.financeos.core.exception.ValidationException;
import com.financeos.core.time.AppTime;
import com.financeos.domain.report.datasource.DatasourceCatalog;
import com.financeos.domain.report.definition.FilterClause;
import com.financeos.domain.report.engine.DateRangeResolver;
import com.financeos.domain.report.engine.SqlPredicates;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.Pageable;

/** The transactions list accepts the next_x_days preset with { amount: N }. */
class TransactionSearchNextXDaysTest {

    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");
    private static final LocalDate TODAY = LocalDate.of(2026, 10, 8);

    private TransactionListQueryBuilder queryBuilder;

    @BeforeEach
    void setUp() {
        AppTime.useClock(Clock.fixed(TODAY.atTime(10, 0).atZone(IST).toInstant(), IST));
        DateRangeResolver resolver = new DateRangeResolver(4);
        queryBuilder = new TransactionListQueryBuilder(new SqlPredicates(resolver), new DatasourceCatalog());
    }

    @AfterEach
    void tearDown() {
        AppTime.reset();
    }

    @Test
    void nextXDaysFiltersTheListToTheComingWindow() {
        TransactionListQueryBuilder.QueryResult data = queryBuilder.buildDataQuery(UUID.randomUUID(), criteria(amount(7)), Pageable.unpaged());
        assertTrue(data.sql().contains("sub.transaction_date BETWEEN :f0a AND :f0b"), data.sql());
        assertEquals(TODAY, data.params().get("f0a"));
        assertEquals(LocalDate.of(2026, 10, 14), data.params().get("f0b"));
    }

    @Test
    void theCountQueryAppliesTheSameWindow() {
        TransactionListQueryBuilder.QueryResult count = queryBuilder.buildCountQuery(UUID.randomUUID(), criteria(amount(30)));
        assertTrue(count.sql().contains("sub.transaction_date BETWEEN :f0a AND :f0b"), count.sql());
        assertEquals(TODAY, count.params().get("f0a"));
        assertEquals(LocalDate.of(2026, 11, 6), count.params().get("f0b"));
    }

    @Test
    void nextXDaysWithoutAValueIsRejected() {
        assertRejected(null);
    }

    @Test
    void nextXDaysWithoutAmountIsRejected() {
        assertRejected(JsonNodeFactory.instance.objectNode());
    }

    @Test
    void nextXDaysWithZeroIsRejected() {
        assertRejected(amount(0));
    }

    @Test
    void nextXDaysWithNegativeIsRejected() {
        assertRejected(amount(-7));
    }

    @Test
    void nextXDaysWithFractionIsRejected() {
        assertRejected(JsonNodeFactory.instance.objectNode().put("amount", 3.5));
    }

    @Test
    void nextXDaysWithTextAmountIsRejected() {
        assertRejected(JsonNodeFactory.instance.objectNode().put("amount", "7"));
    }

    @Test
    void nextXDaysOnANonDateFieldIsRejected() {
        TransactionSearchCriteria c = new TransactionSearchCriteria(
                List.of(new FilterClause("description", "next_x_days", amount(7))), null);
        assertThrows(ValidationException.class, () -> queryBuilder.validate(c, Pageable.unpaged()));
    }

    private void assertRejected(JsonNode value) {
        ValidationException ex = assertThrows(ValidationException.class,
                () -> queryBuilder.validate(criteria(value), Pageable.unpaged()));
        assertTrue(ex.getMessage().contains("next_x_days"));
    }

    private static TransactionSearchCriteria criteria(JsonNode value) {
        return new TransactionSearchCriteria(List.of(new FilterClause("date", "next_x_days", value)), null);
    }

    private static JsonNode amount(int n) {
        return JsonNodeFactory.instance.objectNode().put("amount", n);
    }
}
