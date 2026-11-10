package com.financeos.statement.parser.layout.profiles;

import com.financeos.statement.parser.layout.LayoutProfile;
import com.financeos.statement.parser.layout.LayoutProfile.Direction;
import com.financeos.statement.parser.layout.LayoutProfile.EndScope;
import com.financeos.statement.parser.layout.LayoutProfile.MetaTarget;
import com.financeos.statement.parser.layout.LayoutProfile.Product;
import com.financeos.statement.parser.layout.LayoutProfile.Role;
import com.financeos.statement.parser.layout.LayoutProfile.ValueType;

/**
 * Axis Bank credit card statement, 2024 onwards, the variant with a CASHBACK EARNED column: the same payment-summary box
 * and equation row as {@link AxisCardDtda2024}, then "DATE | TRANSACTION DETAILS | MERCHANT CATEGORY | AMOUNT (Rs.) |
 * CASHBACK EARNED" where each row ends with two figures, the amount with its Cr / Dr marker and the cashback with its own
 * marker (ignored). The table is printed in two x-arrangements, told apart by where the AMOUNT label sits: A (AMOUNT at
 * about x 418, amounts left-aligned under it) and B (AMOUNT at about x 451, amounts right-aligned).
 */
public final class AxisCardDtdace2024 {

    public static final String ID_A = "axis-card-dtdace-2024a";
    public static final String ID_B = "axis-card-dtdace-2024b";

    public static LayoutProfile buildA() {
        return common(ID_A)
            .requiresLabelAt("AMOUNT", 400, 435)
            .column(Role.NARRATION, LayoutProfile.at("DATE", 30), LayoutProfile.at("AMOUNT", -30))
            .fromLabel(Role.AMOUNT, "AMOUNT", -30, -4)
            .fromLabel(Role.MARKER, "AMOUNT", -4, 65)
            .fromLabel(Role.IGNORE, "CASHBACK", -20, 400)
            .build();
    }

    public static LayoutProfile buildB() {
        return common(ID_B)
            .requiresLabelAt("AMOUNT", 440, 470)
            .column(Role.NARRATION, LayoutProfile.at("DATE", 30), LayoutProfile.at("AMOUNT", 10))
            .fromLabel(Role.AMOUNT, "AMOUNT", 10, 52)
            .fromLabel(Role.MARKER, "AMOUNT", 52, 80)
            .fromLabel(Role.IGNORE, "CASHBACK", 10, 400)
            .build();
    }

    /** Built on demand (never in a static initializer that parsing depends on): a bad definition must not break parsing. */
    private static LayoutProfile.Builder common(String id) {
        return LayoutProfile.builder(id)
            .issuer("axis")
            .product(Product.CARD)
            .bank("Axis Bank")
            .requires("statement\\s+period")
            .headerLabel("DATE", "\\bdate\\b")
            .headerLabel("TRANSACTION", "\\btransaction\\b")
            .headerLabel("DETAILS", "\\bdetails\\b")
            .headerLabel("MERCHANT", "\\bmerchant\\b")
            .headerLabel("CATEGORY", "\\bcategory\\b")
            .headerLabel("AMOUNT", "\\bamount\\b")
            .headerLabel("CASHBACK", "\\bcashback\\b")
            .headerLabel("EARNED", "\\bearned\\b")
            .fromLabel(Role.DATE, "DATE", -20, 30)
            .dateFormats("dd/MM/yyyy")
            .suffix("Cr", "Dr", Direction.DEBIT)
            .sectionHeader("\\bcard\\s+no\\.?:?\\s+[\\d*x]*?(\\d{4})(?=\\s|$)")
            .endMarker("end\\s+of\\s+statement", EndScope.FOR_GOOD)
            // the cashback box under the table (heading, label row, its two figures): a full table can run on to the next
            // page after it, so it is skipped rather than ending the table
            .skipLine("cashback\\s+details")
            .skipLine("cashback\\s+earned.*cashback\\s+credited")
            .skipLine("^[\\d,]+\\.\\d{2}\\s+[\\d,]+\\.\\d{2}$")
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
            .notPrinted("reward_points_balance")
            .notPrinted("finance_charges")
            .notPrinted("reward_points_earned");
    }

    private AxisCardDtdace2024() {
    }
}
