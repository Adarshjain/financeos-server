package com.financeos.statement.parser.layout;

import com.financeos.statement.parser.Line;
import com.financeos.statement.parser.Word;

import java.util.ArrayList;
import java.util.List;

/** Hand-built lines with invented values and realistic coordinates (5.5 units per character). */
final class LayoutFixtures {

    private LayoutFixtures() {
    }

    /** ln(page, top, "text", x0, "text", x0, ...): one line of words. */
    static Line ln(int page, double top, Object... textAndX0) {
        List<Word> words = new ArrayList<>();
        for (int i = 0; i < textAndX0.length; i += 2) {
            String t = (String) textAndX0[i];
            double x0 = ((Number) textAndX0[i + 1]).doubleValue();
            words.add(new Word(t, x0, x0 + 5.5 * t.length(), top, page));
        }
        return new Line(words);
    }

    /** A word that ends at x1 (right-aligned amounts) rather than at 5.5 per character. */
    static Word wx(String t, double x0, double x1, double top, int page) {
        return new Word(t, x0, x1, top, page);
    }

    static Line of(Word... words) {
        return new Line(List.of(words));
    }

    static final double DATE = 50;
    static final double NARR = 120;
    static final double DEBIT = 330;
    static final double CREDIT = 400;
    static final double BAL = 470;

    /** The table header of the generic bank layout, shifted right by dx. */
    static Line bankHeader(int page, double top, double dx) {
        return ln(page, top, "Date", DATE + dx, "Narration", NARR + dx, "Debit", DEBIT + dx, "Credit", CREDIT + dx,
                "Balance", BAL + dx);
    }

    /** A row of the generic bank layout; a null debit / credit / balance leaves its cell empty. */
    static Line bankRow(int page, double top, double dx, String date, String narration, String debit, String credit,
                        String balance) {
        List<Object> spec = new ArrayList<>();
        spec.add(date);
        spec.add(DATE + dx);
        spec.add(narration);
        spec.add(NARR + dx);
        if (debit != null) {
            spec.add(debit);
            spec.add(DEBIT + dx);
        }
        if (credit != null) {
            spec.add(credit);
            spec.add(CREDIT + dx);
        }
        if (balance != null) {
            spec.add(balance);
            spec.add(BAL + dx);
        }
        return ln(page, top, spec.toArray());
    }

    /** Builder for the generic bank layout: separate debit / credit columns and a balance column. */
    static LayoutProfile.Builder bank(String id) {
        return LayoutProfile.builder(id)
                .product(LayoutProfile.Product.BANK)
                .headerLabel("DATE", "\\bdate\\b")
                .headerLabel("NARR", "\\bnarration\\b")
                .headerLabel("DEBIT", "\\bdebit\\b")
                .headerLabel("CREDIT", "\\bcredit\\b")
                .headerLabel("BAL", "\\bbalance\\b")
                .fromLabel(LayoutProfile.Role.DATE, "DATE", -10, 60)
                .fromLabel(LayoutProfile.Role.NARRATION, "NARR", -10, 200)
                .fromLabel(LayoutProfile.Role.DEBIT, "DEBIT", -30, 40)
                .fromLabel(LayoutProfile.Role.CREDIT, "CREDIT", -30, 40)
                .fromLabel(LayoutProfile.Role.BALANCE, "BAL", -30, 80)
                .sign(LayoutProfile.Sign.SEPARATE_COLUMNS);
    }

    static LayoutProfile bankProfile() {
        return bank("test-bank").build();
    }

    /** A single-amount layout (Date | Narration | Amount) whose sign is given by the rule under test. */
    static LayoutProfile.Builder single(String id) {
        LayoutProfile.Builder b = LayoutProfile.builder(id);
        CardFieldKeys.KEYS.forEach(b::notPrinted); // a card profile accounts for every stored summary key
        return b
                .product(LayoutProfile.Product.CARD)
                .headerLabel("DATE", "\\bdate\\b")
                .headerLabel("NARR", "\\bnarration\\b")
                .headerLabel("AMT", "\\bamount\\b")
                .fromLabel(LayoutProfile.Role.DATE, "DATE", -10, 60)
                .fromLabel(LayoutProfile.Role.NARRATION, "NARR", -10, 160)
                .fromLabel(LayoutProfile.Role.AMOUNT, "AMT", -20, 50)
                .fromLabel(LayoutProfile.Role.MARKER, "AMT", 50, 90);
    }

    static Line singleHeader(int page, double top, double dx) {
        return ln(page, top, "Date", DATE + dx, "Narration", NARR + dx, "Amount", 400 + dx);
    }

    static Line singleRow(int page, double top, double dx, String date, String narration, String amount, String marker) {
        return marker == null
                ? ln(page, top, date, DATE + dx, narration, NARR + dx, amount, 405 + dx)
                : ln(page, top, date, DATE + dx, narration, NARR + dx, amount, 405 + dx, marker, 460 + dx);
    }
}
