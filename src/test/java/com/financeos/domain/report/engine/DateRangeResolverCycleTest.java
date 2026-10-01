package com.financeos.domain.report.engine;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.financeos.domain.report.definition.FilterClause;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;

class DateRangeResolverCycleTest {

    private final DateRangeResolver resolver = new DateRangeResolver(4);

    @Test
    void cycleOperatorsHaveNoSingleWindow() {
        assertFalse(resolver.effectiveRange(new FilterClause("date", "this_billing_cycle", null)).bounded());
        assertFalse(resolver.effectiveRange(new FilterClause("date", "previous_billing_cycle", null)).bounded());
        assertFalse(resolver.effectiveRange(new FilterClause("date", "billing_cycles_ago",
                JsonNodeFactory.instance.objectNode().put("amount", 2))).bounded());
    }
}
