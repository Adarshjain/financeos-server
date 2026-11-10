package com.financeos.statement.parser.layout.profiles;

import com.financeos.statement.parser.layout.LayoutProfile;
import com.financeos.statement.parser.layout.LayoutProfile.Direction;
import com.financeos.statement.parser.layout.LayoutProfile.EndScope;
import com.financeos.statement.parser.layout.LayoutProfile.MetaTarget;
import com.financeos.statement.parser.layout.LayoutProfile.Product;
import com.financeos.statement.parser.layout.LayoutProfile.Role;
import com.financeos.statement.parser.layout.LayoutProfile.ValueType;

/**
 * Axis Bank credit card statement, 2024 onwards: a payment-summary box (label row over value row), an equation row
 * (previous balance - payments - credits + purchase + cash advance + other = total due), then
 * "DATE | TRANSACTION DETAILS | MERCHANT CATEGORY | AMOUNT (Rs.)" with the amount followed by a Cr / Dr marker and an
 * "End of Statement" line after the last row. The variant with a CASHBACK EARNED column is another layout.
 */
public final class AxisCardDtda2024 {

    public static final String ID = "axis-card-dtda-2024";

    /** Built on demand (never in a static initializer that parsing depends on): a bad definition must not break parsing. */
    public static LayoutProfile build() {
        return LayoutProfile.builder(ID)
            .issuer("axis")
            .product(Product.CARD)
            .bank("Axis Bank")
            // the box wording of this layout; the earlier generation prints the same table with split words
            .requires("statement\\s+period")
            .headerLabel("DATE", "\\bdate\\b")
            .headerLabel("TRANSACTION", "\\btransaction\\b")
            .headerLabel("DETAILS", "\\bdetails\\b")
            .headerLabel("MERCHANT", "\\bmerchant\\b")
            .headerLabel("CATEGORY", "\\bcategory\\b")
            .headerLabel("AMOUNT", "\\bamount\\b")
            .headerRejects("cashback")
            .fromLabel(Role.DATE, "DATE", -20, 30)
            .column(Role.NARRATION, LayoutProfile.at("DATE", 30), LayoutProfile.at("AMOUNT", -10))
            .fromLabel(Role.AMOUNT, "AMOUNT", -10, 50)
            .fromLabel(Role.MARKER, "AMOUNT", 50, 90)
            .dateFormats("dd/MM/yyyy")
            .suffix("Cr", "Dr", Direction.DEBIT)
            .sectionHeader("\\bcard\\s+no\\.?:?\\s+[\\d*x]*?(\\d{4})(?=\\s|$)")
            .endMarker("end\\s+of\\s+statement", EndScope.FOR_GOOD)
            .summary(MetaTarget.PERIOD, "statement\\s+period").type(ValueType.DATE_RANGE).done()
            .summary(MetaTarget.ACCOUNT_NUMBER, "credit\\s+card\\s+number").type(ValueType.MASKED_NUMBER).done()
            .summary("total_amount_due", "total\\s+payment\\s+due").creditIsNegative().done()
            .summary("minimum_amount_due", "minimum\\s+payment\\s+due").creditIsZero().done()
            .summary("payment_due_date", "payment\\s+due\\s+date").type(ValueType.DATE).done()
            .summary("credit_limit", "credit\\s+limit").done()
            .summary("available_credit_limit", "available\\s+credit\\s+limit").done()
            .summary("available_cash_limit", "available\\s+cash\\s+limit").done()
            .summary("previous_balance", "previous\\s+balance").nthAmountBelow(1).creditIsNegative().done()
            .summary("payments_received", "-\\s*payments\\s*-").nthAmountBelow(2).done()
            .summary("credits_received", "-\\s*credits\\s*\\+").nthAmountBelow(3).done()
            .summary("total_purchases", "\\+\\s*purchase\\s*\\+").nthAmountBelow(4).done()
            .summary("cash_advance", "\\+\\s*cash\\s+advance\\s*\\+").nthAmountBelow(5).done()
            .summary("fees_and_charges", "\\+\\s*other\\s+debit").nthAmountBelow(6).done()
            // the eDGE reward block under the table: label row "POINTS ON DATE", figures two lines below
            .summary("reward_points_balance", "points\\s+on\\s+date").type(ValueType.INTEGER).done()
            // this layout prints neither a finance-charge nor a points-earned figure in its summary
            .notPrinted("finance_charges")
            .notPrinted("reward_points_earned")
            .build();
    }

    private AxisCardDtda2024() {
    }
}
