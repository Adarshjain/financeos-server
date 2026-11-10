package com.financeos.statement.parser.layout.profiles;

import com.financeos.statement.parser.layout.LayoutProfile;
import com.financeos.statement.parser.layout.LayoutProfile.Direction;
import com.financeos.statement.parser.layout.LayoutProfile.EndScope;
import com.financeos.statement.parser.layout.LayoutProfile.MetaTarget;
import com.financeos.statement.parser.layout.LayoutProfile.Product;
import com.financeos.statement.parser.layout.LayoutProfile.Role;
import com.financeos.statement.parser.layout.LayoutProfile.ValueType;

/**
 * HSBC Premier credit card statement, 2026 layout whose table header and summary labels are not extractable text: only
 * the values are printed (payment due date and minimal payment due on one line, statement period and total payment due
 * on the next), then "Available Credit Limit", "OPENING BALANCE" and the rows. The "OPENING BALANCE" line is the anchor
 * of the columns (no header line exists): yearless "ddMMM" dates glued to the day, the narration, and the amount
 * followed by Cr for a credit. The rows end at "TOTAL PURCHASE OUTSTANDING". Matched after
 * the layout that prints the header (HsbcCardDtd2026), which has the same opening-balance line.
 */
public final class HsbcCardNohdr2026 {

    public static final String ID = "hsbc-card-nohdr-2026";

    private static final String DAY_MON_YEAR = "(\\d{1,2}\\s+[A-Za-z]{3}\\s+\\d{4})";
    private static final String AMOUNT_AT_END = "([\\d,]+\\.\\d{2})$";
    /** Payment due date and minimal payment due: a date and one amount, no "to" between two dates. */
    private static final String DUE_LINE = "^(?!.*\\sto\\s)(?:\\S+\\s+)?\\d{1,2}\\s+[a-z]{3}\\s+\\d{4}\\s+[\\d,]+\\.\\d{2}$";
    /** Statement period and total payment due: two dates joined by "to" and one amount. */
    private static final String PERIOD_LINE = "\\d{1,2}\\s+[a-z]{3}\\s+\\d{4}\\s+to\\s+\\d{1,2}\\s+[a-z]{3}\\s+\\d{4}\\s+[\\d,]+\\.\\d{2}$";
    /** The account-summary row, four amounts and no labels. */
    private static final String ACCOUNT_SUMMARY_ROW = "^(?:[\\d,]+\\.\\d{2}\\s+){3}[\\d,]+\\.\\d{2}$";
    private static final String MASK = "[0-9xX*]{4}\\s+[xX*]{4}\\s+[xX*]{4}\\s+\\d{4}";

    /** Built on demand (never in a static initializer that parsing depends on): a bad definition must not break parsing. */
    public static LayoutProfile build() {
        return LayoutProfile.builder(ID)
            .issuer("hsbc")
            .product(Product.CARD)
            .bank("HSBC")
            .requires("hsbc\\s+premier")
            // no header line is printed: the "OPENING BALANCE" line over the rows anchors the columns
            .headerLabel("OPENING", "\\bopening\\b")
            .headerLabel("BALANCE", "\\bbalance\\b")
            .column(Role.DATE, LayoutProfile.at("OPENING", -60), LayoutProfile.at("OPENING", -5))
            .column(Role.NARRATION, LayoutProfile.at("OPENING", -5), LayoutProfile.at("OPENING", 295))
            .fromLabel(Role.AMOUNT, "OPENING", 295, 333)
            .fromLabel(Role.MARKER, "OPENING", 333, 373)
            .cardLast4FromAccountNumber()
            .dateFormats("ddMMM", "dd MMM yyyy")
            .suffix("Cr", "Dr", Direction.DEBIT)
            .continuation(12, 12)
            // "dd xxxx xxxx last-4" and the holder name above the rows of a card
            .sectionHeader("^[0-9xX*]{4}\\s+[xX*]{4}\\s+[xX*]{4}\\s+(\\d{4})\\b")
            // the section heading and the interest-rate note between the rows are no narration
            .skipLine("^purchases\\s*&\\s*installments$")
            .skipLine("^interest\\s+rate\\s+applicable")
            // the account-summary row is printed again under the rows of a later page: read once, never an unread row
            .summaryLineInTable("^(?:[\\d,]+\\.\\d{2}-?\\s+){3}[\\d,]+\\.\\d{2}-?$")
            .endMarker("total\\s+purchase\\s+outstanding", EndScope.FOR_GOOD)
            .summary(MetaTarget.PERIOD, PERIOD_LINE).type(ValueType.DATE_RANGE)
                .capture(DAY_MON_YEAR + "\\s+to\\s+" + DAY_MON_YEAR).done()
            .summary(MetaTarget.ACCOUNT_NUMBER, MASK).type(ValueType.MASKED_NUMBER).capture("(" + MASK + ")").done()
            .summary("payment_due_date", DUE_LINE).type(ValueType.DATE).capture(DAY_MON_YEAR).done()
            .summary("minimum_amount_due", DUE_LINE).creditIsZero().capture(AMOUNT_AT_END).done()
            .summary("total_amount_due", PERIOD_LINE).creditIsNegative().capture(AMOUNT_AT_END).done()
            .summary("available_credit_limit", "available\\s+credit\\s+limit").rightOnLine().done()
            // the account-summary row prints no labels: opening, purchases and other charges, payments, net outstanding
            .summary(MetaTarget.OPENING_BALANCE, ACCOUNT_SUMMARY_ROW).capture("^([\\d,]+\\.\\d{2})\\s").done()
            .summary(MetaTarget.CLOSING_BALANCE, "net\\s+outstanding\\s+balance").rightOnLine().done()
            .summary("previous_balance", ACCOUNT_SUMMARY_ROW).creditIsNegative().capture("^([\\d,]+\\.\\d{2})\\s").done()
            .summary("total_purchases", ACCOUNT_SUMMARY_ROW)
                .capture("^[\\d,]+\\.\\d{2}\\s+([\\d,]+\\.\\d{2})\\s").done()
            .notPrinted("credit_limit")
            .notPrinted("payments_received")
            .notPrinted("finance_charges")
            .notPrinted("fees_and_charges")
            .notPrinted("reward_points_earned")
            .notPrinted("reward_points_balance")
            .build();
    }

    private HsbcCardNohdr2026() {
    }
}
