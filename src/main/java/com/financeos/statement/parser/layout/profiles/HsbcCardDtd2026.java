package com.financeos.statement.parser.layout.profiles;

import com.financeos.statement.parser.layout.LayoutProfile;
import com.financeos.statement.parser.layout.LayoutProfile.Direction;
import com.financeos.statement.parser.layout.LayoutProfile.EndScope;
import com.financeos.statement.parser.layout.LayoutProfile.MetaTarget;
import com.financeos.statement.parser.layout.LayoutProfile.Product;
import com.financeos.statement.parser.layout.LayoutProfile.Role;
import com.financeos.statement.parser.layout.LayoutProfile.ValueType;

/**
 * HSBC credit card statement, 2026 layout with a printed table header: a payment-summary box (payment due date and
 * minimal payment due over their values, statement period and total payment due), the card number, a limits row, then
 * "DATE | TRANSACTION DETAILS | AMOUNTS" with yearless "ddMMM" dates glued to the day and the amount followed by Cr for a
 * credit. The rows end at "TOTAL PURCHASE OUTSTANDING"; an account-summary row (opening balance, purchases and other
 * charges, payments and other credits, net outstanding balance) sits under them.
 */
public final class HsbcCardDtd2026 {

    public static final String ID = "hsbc-card-dtd-2026";

    private static final String DAY_MON_YEAR = "(\\d{1,2}\\s+[A-Za-z]{3}\\s+\\d{4})";

    /** Built on demand (never in a static initializer that parsing depends on): a bad definition must not break parsing. */
    public static LayoutProfile build() {
        return LayoutProfile.builder(ID)
            .issuer("hsbc")
            .product(Product.CARD)
            .bank("HSBC")
            // the payment-summary wording of a card statement (the bank statements print none)
            .requires("minimal\\s+payment\\s+due")
            .headerLabel("DATE", "\\bdate\\b")
            .headerLabel("TRANSACTION", "\\btransaction\\b")
            .headerLabel("DETAILS", "\\bdetails\\b")
            .headerLabel("AMOUNTS", "\\bamounts\\b")
            .fromLabel(Role.DATE, "DATE", -10, 42)
            .column(Role.NARRATION, LayoutProfile.at("DATE", 42), LayoutProfile.at("AMOUNTS", -30))
            .fromLabel(Role.AMOUNT, "AMOUNTS", -30, 48)
            .fromLabel(Role.MARKER, "AMOUNTS", 48, 90)
            .cardLast4FromAccountNumber()
            .dateFormats("ddMMM", "dd MMM yyyy")
            .suffix("Cr", "Dr", Direction.DEBIT)
            .continuation(12, 12)
            // "dd" "xxxx" "xxxx" last-4 and the holder name above the rows of a card
            .sectionHeader("^[0-9xX*]{4}\\s+[xX*]{4}\\s+[xX*]{4}\\s+(\\d{4})\\b")
            // the account-summary row of four amounts, when it sits under the rows of a page the table goes on from
            .skipLine("^(?:[\\d,]+\\.\\d{2}-?\\s+){3}[\\d,]+\\.\\d{2}-?$")
            .endMarker("total\\s+purchase\\s+outstanding", EndScope.FOR_GOOD)
            .summary(MetaTarget.PERIOD, "statement\\s+period").captureLines(1).type(ValueType.DATE_RANGE)
                .capture(DAY_MON_YEAR + "\\s+to\\s+" + DAY_MON_YEAR).done()
            .summary(MetaTarget.ACCOUNT_NUMBER, "primary\\s+card\\s+number").captureLines(2).type(ValueType.MASKED_NUMBER)
                .capture("([0-9xX*]{4}(?:\\s+[0-9xX*]{4}){3})").done()
            .summary("payment_due_date", "payment\\s+due\\s+date").captureLines(1).type(ValueType.DATE)
                .capture(DAY_MON_YEAR).done()
            .summary("total_amount_due", "total\\s+payment\\s+due").creditIsNegative().done()
            .summary("minimum_amount_due", "minimal\\s+payment\\s+due").creditIsZero().done()
            .summary("credit_limit", "^credit\\s+limit").done()
            .summary("cash_limit", "cash\\s+limit").done()
            .summary("available_credit_limit", "available\\s+credit\\s+limit").rightOnLine().done()
            .summary(MetaTarget.OPENING_BALANCE, "opening\\s+balance").rightOnLine().done()
            .summary(MetaTarget.CLOSING_BALANCE, "net\\s+outstanding\\s+balance").rightOnLine().done()
            .summary("previous_balance", "opening\\s+balance").rightOnLine().creditIsNegative().done()
            .summary("total_purchases", "purchase\\s*&\\s*other\\s+charges").done()
            .notPrinted("payments_received")
            .notPrinted("finance_charges")
            .notPrinted("fees_and_charges")
            .notPrinted("reward_points_earned")
            .notPrinted("reward_points_balance")
            .build();
    }

    private HsbcCardDtd2026() {
    }
}
