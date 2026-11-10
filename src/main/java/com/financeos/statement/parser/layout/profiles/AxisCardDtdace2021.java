package com.financeos.statement.parser.layout.profiles;

import com.financeos.statement.parser.layout.LayoutProfile;
import com.financeos.statement.parser.layout.LayoutProfile.Direction;
import com.financeos.statement.parser.layout.LayoutProfile.EndScope;
import com.financeos.statement.parser.layout.LayoutProfile.MetaTarget;
import com.financeos.statement.parser.layout.LayoutProfile.Product;
import com.financeos.statement.parser.layout.LayoutProfile.Role;
import com.financeos.statement.parser.layout.LayoutProfile.ValueType;

/**
 * Axis Bank credit card statement, 2021-2023, the variant with a CASHBACK EARNED column: the letterspaced generation of
 * {@link AxisCardDtda2022} (label words arrive broken: "Statement Per iod", "Credit L imit") with "DATE | TRANSACTION
 * DETAILS | MERCHANT CATEGORY | AMOUNT (Rs.) | CASHBACK EARNED" where each row ends with two figures, the amount with its
 * Cr / Dr marker and the cashback with its own marker (ignored). Amounts are left-aligned under the AMOUNT label.
 */
public final class AxisCardDtdace2021 {

    public static final String ID = "axis-card-dtdace-2021";

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
            .headerLabel("CASHBACK", "\\bcashback\\b")
            .headerLabel("EARNED", "\\bearned\\b")
            .fromLabel(Role.DATE, "DATE", -20, 30)
            .column(Role.NARRATION, LayoutProfile.at("DATE", 30), LayoutProfile.at("AMOUNT", -30))
            .fromLabel(Role.AMOUNT, "AMOUNT", -30, -4)
            .fromLabel(Role.MARKER, "AMOUNT", -4, 65)
            .fromLabel(Role.IGNORE, "CASHBACK", -20, 400)
            .dateFormats("dd/MM/yyyy")
            .suffix("Cr", "Dr", Direction.DEBIT)
            .sectionHeader("\\bcard\\s+no\\.?:?\\s+[\\d*x]*?(\\d{4})(?=\\s|$)")
            .endMarker("end\\s+of\\s+statement", EndScope.FOR_GOOD)
            // the cashback box under the table (heading, label row, its two figures) is skipped, not an end: a full
            // table can run on to the next page after it
            .skipLine("cashback\\s+details")
            .skipLine("cashback\\s+earned.*cashback\\s+credited")
            .skipLine("^[\\d,]+\\.\\d{2}\\s+[\\d,]+\\.\\d{2}$")
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
            .notPrinted("reward_points_balance")
            .notPrinted("finance_charges")
            .notPrinted("reward_points_earned")
            .build();
    }

    private AxisCardDtdace2021() {
    }
}
