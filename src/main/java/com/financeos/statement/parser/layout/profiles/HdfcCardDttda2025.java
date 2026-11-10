package com.financeos.statement.parser.layout.profiles;

import com.financeos.statement.parser.layout.LayoutProfile;
import com.financeos.statement.parser.layout.LayoutProfile.Direction;
import com.financeos.statement.parser.layout.LayoutProfile.EndScope;
import com.financeos.statement.parser.layout.LayoutProfile.MetaTarget;
import com.financeos.statement.parser.layout.LayoutProfile.Product;
import com.financeos.statement.parser.layout.LayoutProfile.Role;
import com.financeos.statement.parser.layout.LayoutProfile.ValueType;

/**
 * HDFC Bank credit card statement, 2025 generation without a REWARDS column and without a reward-points block: the same
 * address block, equation row and limits row as {@link HdfcCardDttdra2025}, then
 * "DATE & TIME | TRANSACTION DESCRIPTION | AMOUNT | PI" where a credit prints a "+" before the amount. Pages after the
 * first print the table without its header and with their own (wider) geometry. A "Cash Back Summary" heading ends the
 * table.
 */
public final class HdfcCardDttda2025 {

    public static final String ID = "hdfc-card-dttda-2025";

    /** Built on demand (never in a static initializer that parsing depends on): a bad definition must not break parsing. */
    public static LayoutProfile build() {
        return LayoutProfile.builder(ID)
            .issuer("hdfc")
            .product(Product.CARD)
            .bank("HDFC Bank")
            .headerLabel("DATE", "\\bdate\\b")
            .headerLabel("TIME", "\\btime\\b")
            .headerLabel("TRANSACTION", "\\btransaction\\b")
            .headerLabel("DESCRIPTION", "\\bdescription\\b")
            .headerLabel("AMOUNT", "\\bamount\\b")
            // the REWARDS column belongs to the other 2025 generation
            .headerRejects("rewards")
            .column(Role.DATE, LayoutProfile.at("DATE", -10), LayoutProfile.at("TRANSACTION", -22))
            .column(Role.NARRATION, LayoutProfile.at("TRANSACTION", -22), LayoutProfile.at("AMOUNT", -12))
            .column(Role.AMOUNT, LayoutProfile.at("AMOUNT", -12), LayoutProfile.at("AMOUNT", 60))
            .continuationFromX(Role.DATE, 0, 105)
            .continuationFromX(Role.NARRATION, 105, 500)
            .continuationFromX(Role.AMOUNT, 500, 600)
            .dateFormats("dd/MM/yyyy HH:mm", "dd MMM yyyy")
            .prefix("\\+", null, Direction.DEBIT)
            .continuation(8, 8)
            .cardLast4FromAccountNumber()
            .endMarker("cash\\s+back\\s+summary", EndScope.FOR_GOOD)
            .summary(MetaTarget.ACCOUNT_NUMBER, "credit\\s+card\\s+no").rightOnLine().type(ValueType.MASKED_NUMBER).done()
            .summary(MetaTarget.STATEMENT_DATE, "statement\\s+date").rightOnLine().type(ValueType.DATE).done()
            .summary(MetaTarget.PERIOD, "billing\\s+period").rightOnLine().type(ValueType.DATE_RANGE).done()
            // the equation row, read by position on the line (previous dues - payments + purchases + finance charges = total)
            // so that a previous-dues figure printed in a form no amount grammar reads cannot shift the others
            .summary("previous_balance", "previous\\s+statement\\s+dues")
                .capture("^C(\\S+) C\\S+ \\+ C\\S+ \\+ C\\S+ = C\\S+$").captureLines(4).creditIsNegative().done()
            .summary("payments_received", "payments/credits")
                .capture("^C\\S+ C(\\S+) \\+ C\\S+ \\+ C\\S+ = C\\S+$").captureLines(4).done()
            .summary("total_purchases", "purchases/debit")
                .capture("^C\\S+ C\\S+ \\+ C(\\S+) \\+ C\\S+ = C\\S+$").captureLines(4).done()
            .summary("finance_charges", "finance\\s+charges")
                .capture("^C\\S+ C\\S+ \\+ C\\S+ \\+ C(\\S+) = C\\S+$").captureLines(4).done()
            .summary("total_amount_due", "total\\s+amount\\s+due")
                .capture("^C\\S+ C\\S+ \\+ C\\S+ \\+ C\\S+ = C(\\S+)$").captureLines(4).creditIsNegative().done()
            .summary("credit_limit", "total\\s+credit\\s+limit").capture("^C\\s*([\\d,]+)\\s+C\\s*[\\d,]+\\s+C\\s*[\\d,]+$")
                .captureLines(5).type(ValueType.INTEGER).done()
            .summary("available_credit_limit", "available\\s+credit\\s+limit").capture("^C\\s*[\\d,]+\\s+C\\s*([\\d,]+)\\s+C\\s*[\\d,]+$")
                .captureLines(5).type(ValueType.INTEGER).done()
            .summary("available_cash_limit", "available\\s+cash\\s+limit").capture("^C\\s*[\\d,]+\\s+C\\s*[\\d,]+\\s+C\\s*([\\d,]+)$")
                .captureLines(5).type(ValueType.INTEGER).done()
            .summary("minimum_amount_due", "minimum\\s+due").maxLines(5).creditIsZero().done()
            .summary("payment_due_date", "due\\s+date").type(ValueType.DATE).maxLines(5).done()
            .summary("overlimit_amount", "over\\s+limit").maxLines(2).done()
            // this generation prints neither a fees line nor a reward-points block
            .notPrinted("fees_and_charges")
            .notPrinted("reward_points_balance")
            .notPrinted("reward_points_earned")
            .build();
    }

    private HdfcCardDttda2025() {
    }
}
