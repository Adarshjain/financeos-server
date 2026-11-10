package com.financeos.statement.parser.layout.profiles;

import com.financeos.statement.parser.layout.LayoutProfile;
import com.financeos.statement.parser.layout.LayoutProfile.MetaTarget;
import com.financeos.statement.parser.layout.LayoutProfile.Product;
import com.financeos.statement.parser.layout.LayoutProfile.Role;
import com.financeos.statement.parser.layout.LayoutProfile.Sign;
import com.financeos.statement.parser.layout.LayoutProfile.ValueType;


/**
 * Federal Bank savings account statement, 2022 onwards: "Date | Transaction Details | Amount | Balance". One signed-by-balance
 * Amount column and a running balance; a row prints its date ("25 Jun", no year) on a line of its own, the narration and
 * the amount/balance on the lines below, and a little more narration right under the amount line. The date is printed once
 * for the rows of a day (block rows, closing at the amount line). Amounts are right-aligned under the header, so the
 * columns start left of the header labels. The opening and closing balance are in a banner above the table.
 */
public final class FederalBankDtdab2021 {

    public static final String ID = "federal-bank-dtdab-2021";

    /** Built on demand (never in a static initializer that parsing depends on): a bad definition must not break parsing. */
    public static LayoutProfile build() {
        return LayoutProfile.builder(ID)
            .requires("\\bFDRL0[A-Z0-9]{6}\\b")
            .requires("account\\s+statement")
            .product(Product.BANK)
            .headerLabel("DATE", "\\bdate\\b")
            .headerLabel("TRANSACTION", "\\btransaction\\b")
            .headerLabel("DETAILS", "\\bdetails\\b")
            .headerLabel("AMOUNT", "\\bamount\\b")
            .headerLabel("BALANCE", "\\bbalance\\b")
            .column(Role.DATE, LayoutProfile.at("DATE", -10), LayoutProfile.at("TRANSACTION", -5))
            .column(Role.NARRATION, LayoutProfile.at("TRANSACTION", -5), LayoutProfile.at("AMOUNT", -40))
            .column(Role.AMOUNT, LayoutProfile.at("AMOUNT", -40), LayoutProfile.at("BALANCE", -25))
            .column(Role.BALANCE, LayoutProfile.at("BALANCE", -25), LayoutProfile.at("BALANCE", 100))
            .sign(Sign.BALANCE_DELTA)
            .dateFormats("d MMM", "d MMMM yyyy")
            .blockRows()
            .blockEndsAtAmountLine(8)
            .cardLast4FromAccountNumber()
            // the page footer (support hours, contact line) is printed in the table's columns
            .skipLine("\\bin\\s+spent\\s+saved\\b")
            .skipLine("\\bcontact\\s+us\\b")
            .summary(MetaTarget.PERIOD, "account\\s+statement").rightOnLine().type(ValueType.DATE_RANGE)
                .capture("(\\d{1,2}\\s+[A-Za-z]+\\s+\\d{4})\\s+to\\s+(\\d{1,2}\\s+[A-Za-z]+\\s+\\d{4})").done()
            .summary(MetaTarget.IFSC, "\\bFDRL0").rightOnLine().type(ValueType.TEXT)
                .capture("(?-i:\\b(FDRL0[A-Z0-9]{6})\\b)").done()
            .summary(MetaTarget.ACCOUNT_NUMBER, "a/c\\s+no").below().type(ValueType.TEXT).done()
            .summary(MetaTarget.OPENING_BALANCE, "opening\\s+balance").below().done()
            .summary(MetaTarget.CLOSING_BALANCE, "closing\\s+balance").below().done()
            .summary("previous_balance", "opening\\s+balance").below().done()
            .build();
    }

    private FederalBankDtdab2021() {
    }
}
