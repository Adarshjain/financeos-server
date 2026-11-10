package com.financeos.statement.parser.layout.profiles;

import com.financeos.statement.parser.layout.LayoutProfile;
import com.financeos.statement.parser.layout.LayoutProfile.Direction;
import com.financeos.statement.parser.layout.LayoutProfile.EndScope;
import com.financeos.statement.parser.layout.LayoutProfile.MetaTarget;
import com.financeos.statement.parser.layout.LayoutProfile.Product;
import com.financeos.statement.parser.layout.LayoutProfile.Role;
import com.financeos.statement.parser.layout.LayoutProfile.ValueType;

/**
 * HDFC Bank credit card statement, 2018 to 2020: the same top block as the later layouts ("Statement Date:dd/mm/yyyy" with
 * no period range, "Card No: dddd ddXX XXX", dues, limits, account-summary and past-dues rows), then
 * "Date | Transaction Description | Amount (in Rs.)" with the date only and the amount followed by Cr for a credit, and a
 * "Reward Points Summary" (opening, earned, disbursed, adjusted, closing) under the table that ends it. The amount label
 * sits a few points further left than in the 2022 and 2024 layouts, which tells the generations apart.
 */
public final class HdfcCardDtda2018 {

    public static final String ID = "hdfc-card-dtda-2018";

    /** Built on demand (never in a static initializer that parsing depends on): a bad definition must not break parsing. */
    public static LayoutProfile build() {
        return LayoutProfile.builder(ID)
            .issuer("hdfc")
            .product(Product.CARD)
            .bank("HDFC Bank")
            // the reward summary under the table (the 2024 layout prints none) and the amount label x of this generation
            .requires("reward\\s+points\\s+summary")
            .headerLabel("DATE", "\\bdate\\b")
            .headerLabel("TRANSACTION", "\\btransaction\\b")
            .headerLabel("DESCRIPTION", "\\bdescription\\b")
            .headerLabel("AMOUNT", "\\bamount\\b")
            .headerRejects("\\btime\\b")
            .requiresLabelAt("AMOUNT", 490, 502)
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
            // reward-points summary under the table: label row over a row of whole-number figures
            .summary("reward_points_earned", "\\bearned\\b").type(ValueType.INTEGER).done()
            .summary("reward_points_balance", "closing\\s+balance").type(ValueType.INTEGER).done()
            // payments and credits are one printed figure (kept as credits_received); no separate fees figure
            .notPrinted("payments_received")
            .notPrinted("fees_and_charges")
            .build();
    }

    private HdfcCardDtda2018() {
    }
}
