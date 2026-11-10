package com.financeos.statement.parser.layout.profiles;

import com.financeos.statement.parser.layout.LayoutProfile;
import com.financeos.statement.parser.layout.LayoutProfile.EndScope;
import com.financeos.statement.parser.layout.LayoutProfile.MetaTarget;
import com.financeos.statement.parser.layout.LayoutProfile.Product;
import com.financeos.statement.parser.layout.LayoutProfile.Role;
import com.financeos.statement.parser.layout.LayoutProfile.Sign;
import com.financeos.statement.parser.layout.LayoutProfile.ValueType;


/**
 * HDFC Bank savings account statement, 2025 onwards: "Txn Date | Narration | Withdrawals | Deposits | Closing Balance".
 * Every row prints both the withdrawal and the deposit figure (the unused one as 0.00) and a closing balance, and its
 * narration continues on the lines below the row ("Value Dt ... Ref ..." style text) until the next dated row. The
 * opening balance is in a summary block after the table.
 */
public final class HdfcBankTdnwdcb2025 {

    public static final String ID = "hdfc-bank-tdnwdcb-2025";

    /** Built on demand (never in a static initializer that parsing depends on): a bad definition must not break parsing. */
    public static LayoutProfile build() {
        return LayoutProfile.builder(ID)
            // no issuer gate: the issuer detection finds nothing in about half of this layout's statements, so the
            // bank's name in the text is what tells the layout apart from a look-alike table of another bank
            .requires("hdfc\\s+bank")
            .product(Product.BANK)
            .bank("HDFC Bank")
            .headerLabel("TXN", "\\btxn\\b")
            .headerLabel("DATE", "\\bdate\\b")
            .headerLabel("NARRATION", "\\bnarration\\b")
            .headerLabel("WITHDRAWALS", "\\bwithdrawals\\b")
            .headerLabel("DEPOSITS", "\\bdeposits\\b")
            .headerLabel("CLOSING", "\\bclosing\\b")
            .headerLabel("BALANCE", "\\bbalance\\b")
            .column(Role.DATE, LayoutProfile.at("TXN", -10), LayoutProfile.at("TXN", 50))
            .column(Role.NARRATION, LayoutProfile.at("TXN", 50), LayoutProfile.at("WITHDRAWALS", 10))
            .column(Role.DEBIT, LayoutProfile.at("WITHDRAWALS", 10), LayoutProfile.at("DEPOSITS", 10))
            .column(Role.CREDIT, LayoutProfile.at("DEPOSITS", 10), LayoutProfile.at("CLOSING", 25))
            .column(Role.BALANCE, LayoutProfile.at("CLOSING", 25), LayoutProfile.at("CLOSING", 200))
            .sign(Sign.SEPARATE_COLUMNS)
            .dateFormats("dd/MM/yyyy")
            // the narration continues on the lines below the row, up to the next dated row
            .continuation(45, 0)
            .cardLast4FromAccountNumber()
            .endMarker("statement\\s+summary", EndScope.FOR_GOOD)
            .summary(MetaTarget.PERIOD, "statement\\s+from").rightOnLine().type(ValueType.DATE_RANGE).done()
            .summary(MetaTarget.STATEMENT_DATE, "statement\\s+as\\s+on").rightOnLine().type(ValueType.DATE).done()
            .summary(MetaTarget.ACCOUNT_NUMBER, "account\\s+number").rightOnLine().type(ValueType.TEXT).done()
            .summary(MetaTarget.IFSC, "rtgs/neft\\s+ifsc").rightOnLine().type(ValueType.TEXT)
                .capture("(?-i:\\b([A-Z]{4}0[A-Z0-9]{6})\\b)").done()
            .summary(MetaTarget.OPENING_BALANCE, "opening\\s+balance").below().done()
            .summary(MetaTarget.CLOSING_BALANCE, "closing\\s+balance").below().done()
            // the generic reader also reports the opening balance under the card-style previous_balance key
            .summary("previous_balance", "opening\\s+balance").below().done()
            .build();
    }

    private HdfcBankTdnwdcb2025() {
    }
}
