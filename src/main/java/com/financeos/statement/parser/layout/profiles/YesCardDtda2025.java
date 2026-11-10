package com.financeos.statement.parser.layout.profiles;

import com.financeos.statement.parser.layout.LayoutProfile;
import com.financeos.statement.parser.layout.LayoutProfile.Direction;
import com.financeos.statement.parser.layout.LayoutProfile.EndScope;
import com.financeos.statement.parser.layout.LayoutProfile.MetaTarget;
import com.financeos.statement.parser.layout.LayoutProfile.Product;
import com.financeos.statement.parser.layout.LayoutProfile.Role;
import com.financeos.statement.parser.layout.LayoutProfile.ValueType;

/**
 * YES Bank credit card statement, 2025 onwards: an overview with labels stacked over their values in two columns (card
 * number, period, limits, dues on the left; previous balance, purchases, payments on the right), a reward-points row,
 * then "Date | Transaction Details | Merchant Category | Amount (Rs.)" with the amount followed by a Cr / Dr marker and an
 * "End of the Statement" line after the last row. The merchant category is part of the narration.
 */
public final class YesCardDtda2025 {

    public static final String ID = "yes-card-dtda-2025";

    /** Built on demand (never in a static initializer that parsing depends on): a bad definition must not break parsing. */
    public static LayoutProfile build() {
        return LayoutProfile.builder(ID)
            .issuer("yes")
            .product(Product.CARD)
            .bank("Yes Bank")
            // the overview box wording of this layout
            .requires("statement\\s+summary")
            .headerLabel("DATE", "\\bdate\\b")
            .headerLabel("TRANSACTION", "\\btransaction\\b")
            .headerLabel("DETAILS", "\\bdetails\\b")
            .headerLabel("AMOUNT", "\\bamount\\b")
            .fromLabel(Role.DATE, "DATE", -20, 40)
            .column(Role.NARRATION, LayoutProfile.at("TRANSACTION", -3), LayoutProfile.at("AMOUNT", -10))
            .fromLabel(Role.AMOUNT, "AMOUNT", -10, 48)
            .fromLabel(Role.MARKER, "AMOUNT", 48, 90)
            .dateFormats("dd/MM/yyyy")
            .suffix("Cr", "Dr", Direction.DEBIT)
            .continuation(12, 12)
            // a lone stray glyph on a line of its own (an artefact of the PDF) is no narration
            .skipLine("^[A-Za-z]$")
            .endMarker("end\\s+of\\s+the\\s+statement", EndScope.FOR_GOOD)
            .summary(MetaTarget.PERIOD, "statement\\s+period").type(ValueType.DATE_RANGE).done()
            .summary(MetaTarget.ACCOUNT_NUMBER, "card\\s+number").rightOnLine().type(ValueType.MASKED_NUMBER).done()
            .summary(MetaTarget.STATEMENT_DATE, "statement\\s+date").rightOnLine().type(ValueType.DATE).done()
            .summary("payment_due_date", "payment\\s+due\\s+date").rightOnLine().type(ValueType.DATE).done()
            .summary("total_amount_due", "total\\s+amount\\s+due").creditIsNegative().done()
            .summary("minimum_amount_due", "minimum\\s+amount\\s+due").creditIsZero().done()
            .summary("credit_limit", "(?<!available\\s)credit\\s+limit").done()
            .summary("available_credit_limit", "available\\s+credit\\s+limit").done()
            .summary("cash_limit", "(?<!available\\s)cash\\s+limit").done()
            .summary("available_cash_limit", "available\\s+cash\\s+limit").done()
            .summary("previous_balance", "previous\\s+balance").creditIsNegative().done()
            // one figure for purchases, cash advances and other charges together
            .summary("total_purchases", "current\\s+purchases").maxLines(4).done()
            .summary("payments_received", "payment\\s*&\\s*credits\\s+received").done()
            .summary("reward_points_earned", "points\\s+earned").rightOnLine().type(ValueType.INTEGER).done()
            .summary("reward_points_balance", "closing\\s+reward\\s+points").type(ValueType.INTEGER).xTolerance(2).done()
            // the summary prints one combined figure for purchases and charges and no finance-charge line
            .notPrinted("finance_charges")
            .notPrinted("fees_and_charges")
            .build();
    }

    private YesCardDtda2025() {
    }
}
