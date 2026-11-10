package com.financeos.statement.parser.layout;

import java.util.List;

/**
 * The card summary keys the application stores (a copy of {@code gmail.reconcile.StatementParser.CARD_FIELD_KEYS},
 * kept equal by a test). A CARD profile must declare each one or mark it not printed.
 */
public final class CardFieldKeys {

    public static final List<String> KEYS = List.of(
            "total_amount_due", "minimum_amount_due", "payment_due_date", "credit_limit",
            "available_credit_limit", "finance_charges", "fees_and_charges", "previous_balance",
            "payments_received", "total_purchases", "reward_points_balance", "reward_points_earned");

    private CardFieldKeys() {
    }
}
