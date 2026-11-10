package com.financeos.statement.parser.layout.profiles;

import com.financeos.statement.parser.layout.LayoutProfile;
import com.financeos.statement.parser.layout.LayoutProfile.Direction;
import com.financeos.statement.parser.layout.LayoutProfile.EndScope;
import com.financeos.statement.parser.layout.LayoutProfile.MetaTarget;
import com.financeos.statement.parser.layout.LayoutProfile.Product;
import com.financeos.statement.parser.layout.LayoutProfile.Role;
import com.financeos.statement.parser.layout.LayoutProfile.ValueType;

/**
 * HDFC Bank credit card statement, 2022 onwards, with a time next to each date and a "Feature Reward Points" column:
 * "Statement Date:dd/mm/yyyy" (no period range) and "Card No: dddd ddXX XXXX dddd" in the top block, a dues row (payment
 * due date, total dues, minimum amount due) over its values, a limits row, an account-summary row (opening balance,
 * payments and credits, purchases and debits, finance charges, total dues), a past-dues row, then
 * "Date | Transaction Description | Amount (in Rs.)" with a time, a reward-points cell and the amount followed by Cr for
 * a credit. The table ends at the "Reward Points Summary" block under it.
 */
public final class HdfcCardDtdap2022 {

    public static final String ID = "hdfc-card-dtdap-2022";

    /** Built on demand (never in a static initializer that parsing depends on): a bad definition must not break parsing. */
    public static LayoutProfile build() {
        return LayoutProfile.builder(ID)
            .issuer("hdfc")
            .product(Product.CARD)
            .bank("HDFC Bank")
            // the reward-points column header of this layout (the 2018 and 2024 layouts print none)
            .requires("feature\\s+reward")
            .headerLabel("DATE", "\\bdate\\b")
            .headerLabel("TRANSACTION", "\\btransaction\\b")
            .headerLabel("DESCRIPTION", "\\bdescription\\b")
            .headerLabel("AMOUNT", "\\bamount\\b")
            .headerRejects("\\btime\\b")
            .fromLabel(Role.DATE, "DATE", -40, 5)
            .column(Role.IGNORE, LayoutProfile.at("DATE", 5), LayoutProfile.at("TRANSACTION", -6))
            .column(Role.NARRATION, LayoutProfile.at("TRANSACTION", -6), LayoutProfile.at("AMOUNT", -72))
            .column(Role.IGNORE, LayoutProfile.at("AMOUNT", -72), LayoutProfile.at("AMOUNT", -30))
            .fromLabel(Role.AMOUNT, "AMOUNT", -30, 50)
            .fromLabel(Role.MARKER, "AMOUNT", 50, 90)
            .dateFormats("dd/MM/yyyy")
            .suffix("Cr", "Dr", Direction.DEBIT)
            .cardLast4FromAccountNumber()
            .endMarker("reward\\s+points\\s+summary", EndScope.FOR_GOOD)
            .summary(MetaTarget.PERIOD_END, "statement\\s+date").type(ValueType.DATE)
                .capture("statement\\s+date\\s*:?\\s*(\\d{2}/\\d{2}/\\d{4})").done()
            .summary(MetaTarget.STATEMENT_DATE, "statement\\s+date").type(ValueType.DATE)
                .capture("statement\\s+date\\s*:?\\s*(\\d{2}/\\d{2}/\\d{4})").done()
            .summary(MetaTarget.ACCOUNT_NUMBER, "card\\s+no").type(ValueType.MASKED_NUMBER)
                .capture("card\\s+no\\s*:?\\s*((?:[0-9xX*]{3,4}\\s*){3,4})").done()
            .summary("payment_due_date", "payment\\s+due\\s+date").type(ValueType.DATE).done()
            .summary("total_amount_due", "total\\s+dues").creditIsNegative().done()
            .summary("minimum_amount_due", "minimum\\s+amount\\s+due").creditIsZero().done()
            // the limits are printed as whole rupees in lakh grouping (no decimals)
            .summary("credit_limit", "(?<!available\\s)credit\\s+limit").type(ValueType.INTEGER).done()
            .summary("available_credit_limit", "available\\s+credit\\s+limit").type(ValueType.INTEGER).done()
            .summary("available_cash_limit", "available\\s+cash\\s+limit").type(ValueType.INTEGER).done()
            // account-summary row: opening / payments and credits / purchases and debits / finance charges
            .summary("previous_balance", "opening").nthAmountBelow(1).maxLines(4).creditIsNegative().done()
            .summary("credits_received", "payment/").nthAmountBelow(2).maxLines(4).done()
            .summary("total_purchases", "purchase/").nthAmountBelow(3).maxLines(4).done()
            .summary("finance_charges", "finance").nthAmountBelow(4).maxLines(4).done()
            .summary("overlimit_amount", "overlimit").nthAmountBelow(1).done()
            // reward-points summary under the table: label rows over a row of whole-number figures
            .summary("reward_points_earned", "reward\\s+points\\s+earned").type(ValueType.INTEGER).done()
            .summary("reward_points_balance", "closing\\s+balance").type(ValueType.INTEGER).done()
            // payments and credits are one printed figure (kept as credits_received); no separate fees figure
            .notPrinted("payments_received")
            .notPrinted("fees_and_charges")
            .build();
    }

    private HdfcCardDtdap2022() {
    }
}
