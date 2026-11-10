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
 * running balance on a later line of the same block; opening and closing balances are "Balance Brought/Carried Forward"
 * rows. Date and amount are not on one line in most rows, which the single-line row reader cannot join (see the report).
 */
public final class HsbcBankDtddwb2026 {

    public static final String ID = "hsbc-bank-dtddwb-2026";

    /** Built on demand (never in a static initializer that parsing depends on): a bad definition must not break parsing. */
    public static LayoutProfile build() {
        return LayoutProfile.builder(ID)
            .requires("\\bHSBC\\b")
            .requires("balance\\s+carried\\s+forward")
            .product(Product.BANK)
            .headerLabel("DATE", "\\bdate\\b")
            .headerLabel("TRANSACTION", "\\btransaction\\b")
            .headerLabel("DETAILS", "\\bdetails\\b")
            .headerLabel("DEPOSITS", "\\bdeposits\\b")
            .headerLabel("WITHDRAWALS", "\\bwithdrawals\\b")
            .headerLabel("BALANCE", "\\bbalance\\b")
            .column(Role.DATE, LayoutProfile.at("DATE", -10), LayoutProfile.at("TRANSACTION", -5))
            .column(Role.NARRATION, LayoutProfile.at("TRANSACTION", -5), LayoutProfile.at("DEPOSITS", -10))
            .column(Role.CREDIT, LayoutProfile.at("DEPOSITS", -10), LayoutProfile.at("WITHDRAWALS", -10))
            .column(Role.DEBIT, LayoutProfile.at("WITHDRAWALS", -10), LayoutProfile.at("BALANCE", -10))
            .column(Role.BALANCE, LayoutProfile.at("BALANCE", -10), LayoutProfile.at("BALANCE", 100))
            .sign(Sign.SEPARATE_COLUMNS)
            .dateFormats("ddMMMyyyy", "ddMMMyy")
            .continuation(45, 45)
            .skipLine("balance\\s+brought\\s+forward")
            .endMarker("balance\\s+carried\\s+forward", EndScope.FOR_GOOD)
            .cardLast4FromAccountNumber()
            .summary(MetaTarget.STATEMENT_DATE, "statement\\s+date").rightOnLine().type(ValueType.DATE).done()
            .summary(MetaTarget.OPENING_BALANCE, "balance\\s+brought\\s+forward").rightOnLine().done()
            .summary(MetaTarget.CLOSING_BALANCE, "balance\\s+carried\\s+forward").rightOnLine().done()
            .build();
    }

    private HsbcBankDtddwb2026() {
    }
}
