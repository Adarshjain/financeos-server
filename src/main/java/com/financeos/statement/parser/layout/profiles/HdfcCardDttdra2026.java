package com.financeos.statement.parser.layout.profiles;

import com.financeos.statement.parser.layout.LayoutProfile;
import com.financeos.statement.parser.layout.LayoutProfile.Direction;
import com.financeos.statement.parser.layout.LayoutProfile.EndScope;
import com.financeos.statement.parser.layout.LayoutProfile.MetaTarget;
import com.financeos.statement.parser.layout.LayoutProfile.Product;
import com.financeos.statement.parser.layout.LayoutProfile.Role;
import com.financeos.statement.parser.layout.LayoutProfile.ValueType;

/**
 * HDFC Bank credit card statement, 2026 generation: the layout of {@link HdfcCardDttdra2025} (REWARDS column, "+" before
 * a credit amount, header-less continuation pages with their own geometry, "TRANSACTIONS TOTAL AMOUNT" ending the table)
 * whose points block is headed "Cash Points Opening Balance" and carries no reward-points figures.
 */
public final class HdfcCardDttdra2026 {

    public static final String ID = "hdfc-card-dttdra-2026";

    /** Built on demand (never in a static initializer that parsing depends on): a bad definition must not break parsing. */
    public static LayoutProfile build() {
        return LayoutProfile.builder(ID)
            .issuer("hdfc")
            .product(Product.CARD)
            .bank("HDFC Bank")
            // the points block wording of this generation (the 2025 one prints Reward Points)
            .requires("cash\\s+points\\s+opening\\s+balance")
            .headerLabel("DATE", "\\bdate\\b")
            .headerLabel("TIME", "\\btime\\b")
            .headerLabel("TRANSACTION", "\\btransaction\\b")
            .headerLabel("DESCRIPTION", "\\bdescription\\b")
            .headerLabel("REWARDS", "\\brewards\\b")
            .headerLabel("AMOUNT", "\\bamount\\b")
            .column(Role.DATE, LayoutProfile.at("DATE", -10), LayoutProfile.at("TRANSACTION", -22))
            .column(Role.NARRATION, LayoutProfile.at("TRANSACTION", -22), LayoutProfile.at("REWARDS", -5))
            .column(Role.IGNORE, LayoutProfile.at("REWARDS", -5), LayoutProfile.at("AMOUNT", -12))
            .column(Role.AMOUNT, LayoutProfile.at("AMOUNT", -12), LayoutProfile.at("AMOUNT", 60))
            .continuationFromX(Role.DATE, 0, 105)
            .continuationFromX(Role.NARRATION, 105, 430)
            .continuationFromX(Role.IGNORE, 430, 512)
            .continuationFromX(Role.AMOUNT, 512, 600)
            .dateFormats("dd/MM/yyyy HH:mm", "dd MMM yyyy")
            .prefix("\\+", null, Direction.DEBIT)
            .continuation(8, 8)
            .cardLast4FromAccountNumber()
            .endMarker("transactions\\s+total\\s+amount", EndScope.FOR_GOOD)
            .summary(MetaTarget.ACCOUNT_NUMBER, "credit\\s+card\\s+no").rightOnLine().type(ValueType.MASKED_NUMBER).done()
            .summary(MetaTarget.STATEMENT_DATE, "statement\\s+date").rightOnLine().type(ValueType.DATE).done()
            .summary(MetaTarget.PERIOD, "billing\\s+period").rightOnLine().type(ValueType.DATE_RANGE).done()
            .summary("previous_balance", "previous\\s+statement\\s+dues").nthAmountBelow(1).creditIsNegative().done()
            .summary("payments_received", "payments/credits").nthAmountBelow(2).maxLines(5).done()
            .summary("total_purchases", "purchases/debit").nthAmountBelow(3).maxLines(5).done()
            .summary("finance_charges", "finance\\s+charges").nthAmountBelow(4).done()
            .summary("total_amount_due", "total\\s+amount\\s+due").nthAmountBelow(5).creditIsNegative().done()
            // the limits print as whole-rupee figures, three on one line under their three labels
            .summary("credit_limit", "total\\s+credit\\s+limit").capture("^C\\s*([\\d,]+)\\s+C\\s*[\\d,]+\\s+C\\s*[\\d,]+$")
                .captureLines(5).type(ValueType.INTEGER).done()
            .summary("available_credit_limit", "available\\s+credit\\s+limit").capture("^C\\s*[\\d,]+\\s+C\\s*([\\d,]+)\\s+C\\s*[\\d,]+$")
                .captureLines(5).type(ValueType.INTEGER).done()
            .summary("available_cash_limit", "available\\s+cash\\s+limit").capture("^C\\s*[\\d,]+\\s+C\\s*[\\d,]+\\s+C\\s*([\\d,]+)$")
                .captureLines(5).type(ValueType.INTEGER).done()
            .summary("minimum_amount_due", "minimum\\s+due").maxLines(5).creditIsZero().done()
            .summary("payment_due_date", "due\\s+date").type(ValueType.DATE).maxLines(5).done()
            .summary("overlimit_amount", "over\\s+limit").maxLines(2).done()
            // no separate fees line, and the block prints cash points, not reward points
            .notPrinted("fees_and_charges")
            .notPrinted("reward_points_balance")
            .notPrinted("reward_points_earned")
            .build();
    }

    private HdfcCardDttdra2026() {
    }
}
