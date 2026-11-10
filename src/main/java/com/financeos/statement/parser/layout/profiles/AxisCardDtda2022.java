package com.financeos.statement.parser.layout.profiles;

import com.financeos.statement.parser.layout.LayoutProfile;
import com.financeos.statement.parser.layout.LayoutProfile.Direction;
import com.financeos.statement.parser.layout.LayoutProfile.EndScope;
import com.financeos.statement.parser.layout.LayoutProfile.MetaTarget;
import com.financeos.statement.parser.layout.LayoutProfile.Product;
import com.financeos.statement.parser.layout.LayoutProfile.Role;
import com.financeos.statement.parser.layout.LayoutProfile.ValueType;

/**
 * Axis Bank credit card statement, the 2022-2023 generation: the same payment-summary box, equation row and
 * "DATE | TRANSACTION DETAILS | MERCHANT CATEGORY | AMOUNT (Rs.)" table as {@link AxisCardDtda2024}, but the PDF text is
 * letterspaced, so label words arrive broken ("Statement Per iod", "Credit L imit", "Avai lable"); the labels below
 * tolerate the breaks. No eDGE reward block and no cashback column (the "Cashback Earned" box under the table is
 * not part of it).
 */
public final class AxisCardDtda2022 {

    public static final String ID = "axis-card-dtda-2022";

    /** Built on demand (never in a static initializer that parsing depends on): a bad definition must not break parsing. */
    public static LayoutProfile build() {
        return LayoutProfile.builder(ID)
            .issuer("axis")
            .product(Product.CARD)
            .bank("Axis Bank")
            // the split-word box wording of this generation; the 2024 generation prints "Statement Period" whole
            .requires("statement\\s+per\\s+iod")
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
            // a merchant category that wraps prints one line above and/or below its row (about 4 units away, rows are 10)
            .continuation(6, 6)
            .sectionHeader("\\bcard\\s+no\\.?:?\\s+[\\d*x]*?(\\d{4})(?=\\s|$)")
            .endMarker("end\\s+of\\s+statement", EndScope.FOR_GOOD)
            // a statement without the "End of Statement" line goes straight to the cashback box, whose figures are not rows
            .endMarker("cashback\\s+details", EndScope.FOR_GOOD)
            .summary(MetaTarget.PERIOD, "statement\\s+per\\s*iod").type(ValueType.DATE_RANGE).done()
            .summary(MetaTarget.ACCOUNT_NUMBER, "credit\\s+card\\s+number").type(ValueType.MASKED_NUMBER).done()
            .summary("total_amount_due", "total\\s+payment\\s+due").creditIsNegative().done()
            .summary("minimum_amount_due", "minimum\\s+payment\\s+due").creditIsZero().done()
            .summary("payment_due_date", "payment\\s+due\\s+date").type(ValueType.DATE).done()
            .summary("credit_limit", "credit\\s+l\\s*imit").done()
            .summary("available_credit_limit", "avai\\s*lable\\s+credit\\s+l\\s*imit").done()
            .summary("available_cash_limit", "avai\\s*lable\\s+cash\\s+l\\s*imit").done()
            .summary("previous_balance", "previous\\s+balance").nthAmountBelow(1).creditIsNegative().done()
            .summary("payments_received", "-\\s*payments\\s*-").nthAmountBelow(2).done()
            .summary("credits_received", "-\\s*credits\\s*\\+").nthAmountBelow(3).done()
            .summary("total_purchases", "\\+\\s*purchase\\s*\\+").nthAmountBelow(4).done()
            .summary("cash_advance", "\\+\\s*cash\\s+advance\\s*\\+").nthAmountBelow(5).done()
            .summary("fees_and_charges", "\\+\\s*other\\s+debit").nthAmountBelow(6).done()
            // this generation prints no reward block, finance-charge figure or points-earned figure in its summary
            .notPrinted("reward_points_balance")
            .notPrinted("finance_charges")
            .notPrinted("reward_points_earned")
            .build();
    }

    private AxisCardDtda2022() {
    }
}
