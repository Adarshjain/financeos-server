package com.financeos.statement.parser.layout.profiles;

import com.financeos.statement.parser.layout.LayoutProfile;
import com.financeos.statement.parser.layout.LayoutProfile.Direction;
import com.financeos.statement.parser.layout.LayoutProfile.MetaTarget;
import com.financeos.statement.parser.layout.LayoutProfile.Product;
import com.financeos.statement.parser.layout.LayoutProfile.Role;
import com.financeos.statement.parser.layout.LayoutProfile.ValueType;

/**
 * Bank of Baroda (BOBCARD) credit card statement, 2026 generation, printed in Hindi and English: "Statement Date" and
 * "Statement Period" on top, the card number and sanctioned limit under the address, a dues row (payment due date,
 * minimum amount due, total amount due with a glued DR), a limits row, an account-summary row (opening balance,
 * payments and credits, new purchases and debits, closing balance), a bonus-reward-points block, then
 * "Date | Ref. No. | Particulars | Reward Points | Currency | Amt. | Amount" with a foreign-currency amount, the rupee
 * amount and a DR / CR marker. Reward points and currency are ignored columns.
 */
public final class BobCardDrnpaar2026 {

    public static final String ID = "bank_of_baroda-card-drnpaar-2026";

    /** Built on demand (never in a static initializer that parsing depends on): a bad definition must not break parsing. */
    public static LayoutProfile build() {
        return LayoutProfile.builder(ID)
            .issuer("bank_of_baroda")
            .product(Product.CARD)
            // the bonus / reward points block of this generation
            .requires("bonus/reward\\s+points\\s+summary")
            .headerLabel("DATE", "\\bdate\\b")
            .headerLabel("REF", "\\bref\\b")
            .headerLabel("PARTICULARS", "\\bparticulars\\b")
            .headerLabel("AMT", "\\bamt\\b")
            .headerLabel("AMOUNT", "\\bamount\\b")
            .fromLabel(Role.DATE, "DATE", -30, 45)
            .column(Role.IGNORE, LayoutProfile.at("DATE", 45), LayoutProfile.at("PARTICULARS", -90))
            .column(Role.NARRATION, LayoutProfile.at("PARTICULARS", -90), LayoutProfile.at("AMT", -125))
            // reward points; the currency code that follows stays in the narration
            .column(Role.IGNORE, LayoutProfile.at("AMT", -125), LayoutProfile.at("AMT", -86))
            .column(Role.NARRATION, LayoutProfile.at("AMT", -86), LayoutProfile.at("AMT", -60))
            .column(Role.IGNORE, LayoutProfile.at("AMT", -60), LayoutProfile.at("AMOUNT", -8))
            .fromLabel(Role.AMOUNT, "AMOUNT", -8, 40)
            .fromLabel(Role.MARKER, "AMOUNT", 40, 90)
            .cardLast4FromAccountNumber()
            .dateFormats("dd/MM/yyyy", "d MMM, yyyy")
            .suffix("Cr", "Dr", Direction.DEBIT)
            .continuation(14, 14)
            // the primary cardholder line under the table header is no narration (an add-on holder line above a row is)
            .skipLine("\\(primary\\s+card")
            // the dated "spend based reward" line is reward points only, with no amount
            .skipLine("^\\d{2}/\\d{2}/\\d{4}\\s+spend\\s+based\\s+reward")
            .summary(MetaTarget.PERIOD, "statement\\s+period").type(ValueType.DATE_RANGE)
                .capture("statement\\s+period\\s*:?\\s*(\\d{1,2}\\s+\\w{3},?\\s+\\d{4})\\s+to\\s+(\\d{1,2}\\s+\\w{3},?\\s+\\d{4})").done()
            .summary(MetaTarget.STATEMENT_DATE, "statement\\s+date").type(ValueType.DATE)
                .capture("statement\\s+date\\s*:?\\s*(\\d{2}/\\d{2}/\\d{4})").done()
            .summary(MetaTarget.ACCOUNT_NUMBER, "card\\s+no").type(ValueType.MASKED_NUMBER)
                .capture("card\\s+no\\s*:?\\s*([0-9xX*]{6,}[0-9xX*]*)").done()
            .summary("payment_due_date", "payment\\s+due\\s+date").type(ValueType.DATE).done()
            .summary("total_amount_due", "total\\s+amount\\s+due").creditIsNegative().done()
            .summary("minimum_amount_due", "minimum\\s+amount\\s+due").creditIsZero().done()
            // printed as whole rupees with a currency glyph glued on (no decimals)
            .summary("credit_limit", "sanctioned\\s+credit\\s+limit").type(ValueType.INTEGER)
                .capture("sanctioned\\s+credit\\s+limit\\s*:?\\s*[₹`]?\\s*([0-9,]+)").done()
            .summary("available_credit_limit", "available\\s+credit\\s+limit").done()
            .summary("cash_limit", "(?<!available\\s)cash\\s+limit").done()
            .summary("available_cash_limit", "available\\s+cash\\s+limit").done()
            .summary("previous_balance", "opening\\s+balance").nthAmountBelow(1).maxLines(4).creditIsNegative().done()
            .summary(MetaTarget.OPENING_BALANCE, "opening\\s+balance").nthAmountBelow(1).maxLines(4).done()
            .summary(MetaTarget.CLOSING_BALANCE, "closing\\s+balance").nthAmountBelow(4).maxLines(4).done()
            .summary("payments_received", "payment/credits").nthAmountBelow(2).maxLines(4).done()
            .summary("total_purchases", "purchases/debits").nthAmountBelow(3).maxLines(4).done()
            .notPrinted("finance_charges")
            .notPrinted("fees_and_charges")
            .notPrinted("reward_points_earned")
            .notPrinted("reward_points_balance")
            .build();
    }

    private BobCardDrnpaar2026() {
    }
}
