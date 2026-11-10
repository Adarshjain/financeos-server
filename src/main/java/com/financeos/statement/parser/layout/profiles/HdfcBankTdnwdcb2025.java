package com.financeos.statement.parser.layout.profiles;

import com.financeos.statement.parser.Line;
import com.financeos.statement.parser.Word;
import com.financeos.statement.parser.layout.LayoutProfile;
import com.financeos.statement.parser.layout.LayoutProfile.DateContext;
import com.financeos.statement.parser.layout.LayoutProfile.EndScope;
import com.financeos.statement.parser.layout.LayoutProfile.MetaTarget;
import com.financeos.statement.parser.layout.LayoutProfile.Product;
import com.financeos.statement.parser.layout.LayoutProfile.Role;
import com.financeos.statement.parser.layout.LayoutProfile.Sign;
import com.financeos.statement.parser.layout.LayoutProfile.TableRow;
import com.financeos.statement.parser.layout.LayoutProfile.ValueType;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * HDFC Bank savings account statement, 2025 onwards: "Txn Date | Narration | Withdrawals | Deposits | Closing Balance".
 * Every row prints both the withdrawal and the deposit figure (the unused one as 0.00) and a closing balance, and its
 * narration continues on the lines below the row ("Value Dt ... Ref ..." style text) until the next dated row; so the
 * rows are read by a row strategy. The opening balance is in a summary block after the table.
 * <p>
 * Not covered, the profile DSL cannot express it: the account number (a plain 14-digit number under an "Account
 * Number" label; the number type needs mask characters), the IFSC code, and the row card last four (the generic
 * reader copies the account number's last four onto every row).
 */
public final class HdfcBankTdnwdcb2025 {

    public static final String ID = "hdfc-bank-tdnwdcb-2025";

    private static final Pattern AMOUNT = Pattern.compile("^(?:\\d{1,3}(?:,\\d{2,3})*|\\d+)\\.\\d{1,2}$");

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
            .rowStrategy(HdfcBankTdnwdcb2025::rows)
            .endMarker("statement\\s+summary", EndScope.FOR_GOOD)
            .summary(MetaTarget.PERIOD, "statement\\s+from").rightOnLine().type(ValueType.DATE_RANGE).done()
            .summary(MetaTarget.STATEMENT_DATE, "statement\\s+as\\s+on").rightOnLine().type(ValueType.DATE).done()
            .summary(MetaTarget.OPENING_BALANCE, "opening\\s+balance").below().done()
            .summary(MetaTarget.CLOSING_BALANCE, "closing\\s+balance").below().done()
            // the generic reader also reports the opening balance under the card-style previous_balance key
            .summary("previous_balance", "opening\\s+balance").below().done()
            .build();
    }

    /** Dated rows of one page; the lines under a row up to the next dated row are the rest of its narration. */
    private static List<TableRow> rows(List<Line> table, Map<String, Double> labelX0, DateContext dates) {
        double dateLo = labelX0.get("TXN") - 10;
        double narrLo = labelX0.get("TXN") + 50;
        double debitLo = labelX0.get("WITHDRAWALS") + 10;
        double creditLo = labelX0.get("DEPOSITS") + 10;
        double balanceLo = labelX0.get("CLOSING") + 25;
        List<TableRow> out = new ArrayList<>();
        Line rowLine = null;
        LocalDate date = null;
        BigDecimal debit = null;
        BigDecimal credit = null;
        BigDecimal balance = null;
        List<String> narration = new ArrayList<>();
        for (Line ln : table) {
            LocalDate d = dateIn(ln, dateLo, narrLo, dates);
            if (d != null) {
                if (rowLine != null) {
                    out.add(row(rowLine, date, narration, debit, credit, balance));
                }
                rowLine = ln;
                date = d;
                narration = new ArrayList<>();
                BigDecimal w = null;
                BigDecimal dep = null;
                BigDecimal bal = null;
                for (Word word : ln.words()) {
                    if (word.x0() < narrLo) {
                        continue;
                    }
                    if (word.x0() < debitLo) {
                        narration.add(word.text());
                    } else if (word.x0() < creditLo) {
                        w = amount(word.text());
                    } else if (word.x0() < balanceLo) {
                        dep = amount(word.text());
                    } else {
                        bal = amount(word.text());
                    }
                }
                debit = nonZero(w);
                credit = nonZero(dep);
                balance = bal;
            } else if (rowLine != null) {
                for (Word word : ln.words()) {
                    if (word.x0() >= narrLo && word.x0() < debitLo) {
                        narration.add(word.text());
                    }
                }
            }
        }
        if (rowLine != null) {
            out.add(row(rowLine, date, narration, debit, credit, balance));
        }
        return out;
    }

    private static TableRow row(Line line, LocalDate date, List<String> narration, BigDecimal debit, BigDecimal credit,
                                BigDecimal balance) {
        // a row with both figures non-zero, or both zero, has no readable direction: left without an amount (dropped)
        boolean ambiguous = (debit == null) == (credit == null);
        return new TableRow(line, date, String.join(" ", narration), ambiguous ? null : debit, ambiguous ? null : credit,
                null, false, null, balance, null);
    }

    private static LocalDate dateIn(Line ln, double lo, double hi, DateContext dates) {
        for (Word w : ln.words()) {
            if (w.x0() >= lo && w.x0() < hi) {
                LocalDate d = dates.parse(w.text().strip());
                if (d != null) {
                    return d;
                }
            }
        }
        return null;
    }

    private static BigDecimal amount(String text) {
        String t = text.strip();
        return AMOUNT.matcher(t).matches() ? new BigDecimal(t.replace(",", "")) : null;
    }

    private static BigDecimal nonZero(BigDecimal v) {
        return v == null || v.signum() == 0 ? null : v;
    }

    private HdfcBankTdnwdcb2025() {
    }
}
