package com.financeos.statement.parser.layout.profiles;

import com.financeos.statement.parser.layout.LayoutProfile;
import com.financeos.statement.parser.layout.LayoutProfile.EndScope;
import com.financeos.statement.parser.layout.LayoutProfile.MetaTarget;
import com.financeos.statement.parser.layout.LayoutProfile.Product;
import com.financeos.statement.parser.layout.LayoutProfile.Role;
import com.financeos.statement.parser.layout.LayoutProfile.Sign;
import com.financeos.statement.parser.layout.LayoutProfile.ValueType;


/**
 * HSBC composite statement (account section), 2026: "Date | Transaction Details | Deposits | Withdrawals | Balance".
 * A row prints its date and the first narration line on one line, more narration lines below, and the amount with the
 * running balance on the last line; the date is printed once for the rows of a day, so the next rows start at a narration
 * line (block rows, closing at the amount line; a block may continue on the next page). Amounts are right-aligned under the
 * header, so the columns start left of the header labels. Opening and closing balances are "Balance Brought/Carried
 * Forward" rows. The spreadsheet export of the same statement (newest first, single-line rows) is the same layout.
 */
public final class HsbcBankDtddwb2026 {

    public static final String ID = "hsbc-bank-dtddwb-2026";

    /** Built on demand (never in a static initializer that parsing depends on): a bad definition must not break parsing. */
    public static LayoutProfile build() {
        return LayoutProfile.builder(ID)
            .requires("HSBC")
            .product(Product.BANK)
            .headerLabel("DATE", "\\bdate\\b")
            .headerLabel("TRANSACTION", "\\btransaction\\b")
            .headerLabel("DETAILS", "\\bdetails\\b")
            .headerLabel("DEPOSITS", "\\bdeposits\\b")
            .headerLabel("WITHDRAWALS", "\\bwithdrawals\\b")
            .headerLabel("BALANCE", "\\bbalance\\b")
            .column(Role.DATE, LayoutProfile.at("DATE", -10), LayoutProfile.at("TRANSACTION", -5))
            .column(Role.NARRATION, LayoutProfile.at("TRANSACTION", -5), LayoutProfile.at("DEPOSITS", -15))
            .column(Role.CREDIT, LayoutProfile.at("DEPOSITS", -15), LayoutProfile.at("WITHDRAWALS", -30))
            .column(Role.DEBIT, LayoutProfile.at("WITHDRAWALS", -30), LayoutProfile.at("BALANCE", -40))
            .column(Role.BALANCE, LayoutProfile.at("BALANCE", -40), LayoutProfile.at("BALANCE", 100))
            .sign(Sign.SEPARATE_COLUMNS)
            .dateFormats("ddMMMyyyy", "ddMMMyy", "yyyy/MM/dd")
            .blockRows()
            .blockEndsAtAmountLine(0)
            .narrationDropsDates()
            // the PDF prints oldest first, the spreadsheet export newest first
            .order(LayoutProfile.Order.AUTO)
            .skipLine("balance\\s+brought\\s+forward")
            .skipLine("^opening\\s+balance")
            .endMarker("balance\\s+carried\\s+forward", EndScope.PAGE)
            .endMarker("closing\\s+balance", EndScope.FOR_GOOD)
            .cardLast4FromAccountNumber()
            .summary(MetaTarget.IFSC, "\\bHSBC0").rightOnLine().type(ValueType.TEXT)
                .capture("(?-i:\\b(HSBC0[A-Z0-9]{6})\\b)").done()
            .summary(MetaTarget.PERIOD_END, "statement\\s+date").rightOnLine().type(ValueType.DATE).done()
            .summary("total_deposits", "total\\s+deposits").rightOnLine().done()
            .summary(MetaTarget.ACCOUNT_NUMBER, "\\baccount-").rightOnLine().type(ValueType.TEXT)
                .capture("\\b(\\d{3}-\\d{6}-\\d{3})\\b").done()
            .summary(MetaTarget.STATEMENT_DATE, "statement\\s+date").rightOnLine().type(ValueType.DATE).done()
            .summary(MetaTarget.OPENING_BALANCE, "balance\\s+brought\\s+forward").rightOnLine().done()
            .summary(MetaTarget.CLOSING_BALANCE, "balance\\s+carried\\s+forward").rightOnLine().done()
            .build();
    }

    private HsbcBankDtddwb2026() {
    }
}
