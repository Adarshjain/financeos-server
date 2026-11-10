package com.financeos.statement.parser.layout.profiles;

import com.financeos.statement.parser.layout.LayoutProfile;
import com.financeos.statement.parser.layout.LayoutProfile.Direction;
import com.financeos.statement.parser.layout.LayoutProfile.EndScope;
import com.financeos.statement.parser.layout.LayoutProfile.MetaTarget;
import com.financeos.statement.parser.layout.LayoutProfile.Product;
import com.financeos.statement.parser.layout.LayoutProfile.Role;
import com.financeos.statement.parser.layout.LayoutProfile.ValueType;

/**
 * ICICI Bank credit card statement, 2021-22: the table sits at the right of the page (offers and a spends chart with
 * percentage labels in the left margin), a header "SPENDS OVERVIEW | Date | SerNo. | Transaction Details | Intl.# |
 * Amount", the masked card number on the line below the header, then rows "date | serial number | narration | amount"
 * and a "# International Spends" footnote after the last row. The summary is a statement-summary box (label row over
 * value row) and an equation row. The 2023 generation adds a Reward points column and is another layout.
 */
public final class IciciCardSdtdaa2021 {

    public static final String ID = "icici-card-sdtdaa-2021";

    /** Built on demand (never in a static initializer that parsing depends on): a bad definition must not break parsing. */
    public static LayoutProfile build() {
        return LayoutProfile.builder(ID)
            .issuer("icici")
            .product(Product.CARD)
            .bank("ICICI Bank")
            // a continued page repeats the header without its SPENDS OVERVIEW caption, so it starts at Date
            .headerLabel("DATE", "\\bdate\\b")
            .headerLabel("TRANSACTION", "\\btransaction\\b")
            .headerLabel("DETAILS", "\\bdetails\\b")
            // "Amount (in rupee)", not the "amount" of the second header line that a continued page prints beside it
            .headerLabel("AMOUNT", "\\bamount\\s+\\(")
            .headerRejects("\\breward\\b")
            .fromLabel(Role.DATE, "DATE", -15, 30)
            // the serial number is not an amount
            .column(Role.IGNORE, LayoutProfile.at("DATE", 30), LayoutProfile.at("TRANSACTION", -5))
            .column(Role.NARRATION, LayoutProfile.at("TRANSACTION", -5), LayoutProfile.at("AMOUNT", -15))
            .fromLabel(Role.AMOUNT, "AMOUNT", -15, 26)
            .fromLabel(Role.MARKER, "AMOUNT", 26, 60)
            .dateFormats("dd/MM/yyyy", "MMM d yyyy", "MMMM d yyyy")
            .suffix("CR", "DR", Direction.DEBIT)
            .continuation(11, 11)
            // the masked card number alone on the line under the header names the card of the rows (and is the account number)
            .sectionHeader("\\b(\\d{4}[x*]{4,}\\d{4})\\b")
            .accountNumberFromSectionHeader()
            .endMarker("#\\s*international\\s+spends", EndScope.FOR_GOOD)
            .summary(MetaTarget.PERIOD, "statement\\s+period").rightOnLine().type(ValueType.DATE_RANGE).done()
            .summary(MetaTarget.STATEMENT_DATE, "statement\\s+date").type(ValueType.DATE).done()
            .summary("payment_due_date", "payment\\s+due\\s+date").type(ValueType.DATE).done()
            // the equation row: total due = previous balance + purchases + cash advances - payments
            .summary("total_amount_due", "total\\s+amount\\s+due").nthAmountBelow(1).creditIsNegative().done()
            .summary("previous_balance", "previous\\s+balance").nthAmountBelow(2).creditIsNegative().done()
            .summary("total_purchases", "purchases\\s*/\\s*charges").nthAmountBelow(3).done()
            .summary("cash_advance", "cash\\s+advances?").nthAmountBelow(4).done()
            .summary("payments_received", "payments\\s*/\\s*credits").nthAmountBelow(5).done()
            .summary("minimum_amount_due", "minimum\\s+amount\\s+due").done()
            .summary("credit_limit", "credit\\s+limit").maxLines(4).done()
            .summary("available_credit_limit", "available\\s+credit").maxLines(4).done()
            .summary("cash_limit", "cash\\s+limit").maxLines(4).done()
            .summary("available_cash_limit", "available\\s+cash").maxLines(4).done()
            // no reward-points, finance charge or fees figure in this layout's summary
            .notPrinted("finance_charges")
            .notPrinted("fees_and_charges")
            .notPrinted("reward_points_balance")
            .notPrinted("reward_points_earned")
            .build();
    }

    private IciciCardSdtdaa2021() {
    }
}
