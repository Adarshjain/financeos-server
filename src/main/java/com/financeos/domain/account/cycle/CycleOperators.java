package com.financeos.domain.account.cycle;

import com.fasterxml.jackson.databind.JsonNode;
import com.financeos.domain.report.definition.FilterClause;

import java.util.List;
import java.util.Set;

/**
 * Billing-cycle date operators. Unlike other relative operators they resolve PER CARD: each
 * credit card's rows are kept to that card's own cycle, and other accounts are excluded.
 * {@code billing_cycles_ago} ({amount: n}) is internal — KPI comparisons use it to reach the
 * cycle before the filtered one.
 */
public final class CycleOperators {

    public static final String THIS_CYCLE = "this_billing_cycle";
    public static final String PREVIOUS_CYCLE = "previous_billing_cycle";
    public static final String CYCLES_AGO = "billing_cycles_ago";

    /** The operators offered in the catalog (on fields that support billing cycles). */
    public static final List<String> PUBLIC = List.of(THIS_CYCLE, PREVIOUS_CYCLE);

    private static final Set<String> ALL = Set.of(THIS_CYCLE, PREVIOUS_CYCLE, CYCLES_AGO);

    private CycleOperators() {
    }

    public static boolean isCycle(String operator) {
        return operator != null && ALL.contains(operator);
    }

    public static boolean isCycle(FilterClause filter) {
        return filter != null && isCycle(filter.operator());
    }

    /** How many cycles back the operator points: this = 0, previous = 1, ago = amount. */
    public static int cyclesAgo(String operator, JsonNode value) {
        return switch (operator) {
            case THIS_CYCLE -> 0;
            case PREVIOUS_CYCLE -> 1;
            case CYCLES_AGO -> {
                JsonNode amount = value == null ? null : value.get("amount");
                if (amount == null || !amount.isIntegralNumber() || amount.asInt() < 0) {
                    throw new IllegalArgumentException("Operator '" + CYCLES_AGO + "' requires { amount: integer >= 0 }");
                }
                yield amount.asInt();
            }
            default -> throw new IllegalArgumentException("Not a billing-cycle operator: " + operator);
        };
    }

    public static int cyclesAgo(FilterClause filter) {
        return cyclesAgo(filter.operator(), filter.value());
    }
}
