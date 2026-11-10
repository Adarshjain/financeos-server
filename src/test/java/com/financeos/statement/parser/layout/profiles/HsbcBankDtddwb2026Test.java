package com.financeos.statement.parser.layout.profiles;

import com.financeos.statement.parser.Line;
import com.financeos.statement.parser.Word;
import com.financeos.statement.parser.layout.LayoutProfile;
import com.financeos.statement.parser.layout.LayoutRegistry;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The HSBC composite statement's account table of 2026 on synthetic text, coordinates as printed. Only the layout's identity
 * is tested: a row's date and amount sit on different lines of its block, which the single-line row reader of the DSL
 * cannot join, so no row is read yet.
 */
class HsbcBankDtddwb2026Test {

    private static final LayoutProfile PROFILE = HsbcBankDtddwb2026.build();

    private static Line ln(int page, double top, Object... textAndX0) {
        List<Word> words = new ArrayList<>();
        for (int i = 0; i < textAndX0.length; i += 2) {
            String t = (String) textAndX0[i];
            double x0 = ((Number) textAndX0[i + 1]).doubleValue();
            words.add(new Word(t, x0, x0 + 5.2 * t.length(), top, page));
        }
        return new Line(words);
    }

    private static List<Line> statement(boolean carriedForward, String bank) {
        List<Line> l = new ArrayList<>();
        l.add(ln(1, 103, bank, 311, "Statement", 430));
        l.add(ln(2, 624, "Date", 36, "Transaction", 102, "Details", 147, "Deposits", 335, "Withdrawals", 408, "Balance", 522));
        l.add(ln(2, 657, "01Jul2026", 36, "BALANCE", 102, "BROUGHT", 142, "FORWARD", 184, "1,000.00", 502));
        if (carriedForward) {
            l.add(ln(3, 735, "Balance", 103, "Carried", 134, "Forward", 162, "1,000.00", 507));
        }
        return l;
    }

    @Test
    void theProfileMatchesTheHsbcAccountTable() {
        assertEquals("hsbc-bank-dtddwb-2026", PROFILE.id());
        assertEquals(LayoutProfile.Product.BANK, PROFILE.product());
        assertEquals(PROFILE.id(), LayoutRegistry.defaults().match(statement(true, "HSBC"), "hsbc").id());
    }

    @Test
    void aTableOfAnotherBankIsNotThisLayout() {
        assertNull(LayoutRegistry.defaults().match(statement(true, "OTHER"), "hsbc"));
    }

    @Test
    void aStatementWithoutTheCarriedForwardRowIsNotThisLayout() {
        assertNull(LayoutRegistry.defaults().match(statement(false, "HSBC"), "hsbc"));
    }
}
