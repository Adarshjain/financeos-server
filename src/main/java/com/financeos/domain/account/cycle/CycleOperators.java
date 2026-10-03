package com.financeos.domain.account.cycle;

import com.fasterxml.jackson.databind.JsonNode;
import com.financeos.domain.report.definition.FilterClause;

import java.util.List;
import java.util.Set;

/**
 * Billing-cycle date operators. They resolve per account (a credit card's statement cycles,
 * any other account's calendar months) on the transaction's effective date, and a report using
 * them must be limited to one account. {@code billing_cycles_ago} ({amount: n}) is internal —
 * KPI comparisons use it to reach the cycle before the filtered one.
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

    /**
     * The single account a billing-cycle report is limited to: the value of its one-value
     * filter on {@code accountField} (an account id, or a name for name-based fields); null if
     * there is none (the validator rejects such reports).
     */
    public static String singleAccountRef(String accountField, List<FilterClause> filters) {
        if (accountField == null || filters == null) {
            return null;
        }
        for (FilterClause f : filters) {
            if (!accountField.equals(f.field()) || f.value() == null) {
                continue;
            }
            if ("is".equals(f.operator()) && !f.value().isArray()) {
                return f.value().asText();
            }
            if ("in".equals(f.operator()) && f.value().isArray() && f.value().size() == 1) {
                return f.value().get(0).asText();
            }
        }
        return null;
    }
}
