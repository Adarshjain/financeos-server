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
 * The Federal Bank savings layout of 2022 onwards on synthetic text, coordinates as printed. Only the layout's identity is
 * tested: a row prints its date on a line of its own and its amount on a later line, which the single-line row reader of
 * the DSL cannot join, so no row is read yet.
 */
class FederalBankDtdab2021Test {

    private static final LayoutProfile PROFILE = FederalBankDtdab2021.build();

    private static Line ln(int page, double top, Object... textAndX0) {
        List<Word> words = new ArrayList<>();
        for (int i = 0; i < textAndX0.length; i += 2) {
            String t = (String) textAndX0[i];
            double x0 = ((Number) textAndX0[i + 1]).doubleValue();
            words.add(new Word(t, x0, x0 + 5.2 * t.length(), top, page));
        }
        return new Line(words);
    }

    private static List<Line> statement(String ifsc, String header) {
        List<Line> l = new ArrayList<>();
        l.add(ln(1, 32, "Account", 56, "Statement", 99, "1", 162, "April", 169, "2022", 192, "to", 219, "31", 234, "March", 246, "2023", 276));
        l.add(ln(1, 103, "SAVINGS", 407, "A/C", 440, "NO", 456, "IFSC", 500));
        l.add(ln(1, 115, "10000000000001", 407, ifsc, 500));
        l.add(ln(1, 284, header.split(" ")[0], 56, "Transaction", 108, "Details", 169, "Amount", 427, "Balance", 520));
        return l;
    }

    @Test
    void theProfileMatchesTheFederalSavingsLayout() {
        assertEquals("federal-bank-dtdab-2021", PROFILE.id());
        assertEquals(LayoutProfile.Product.BANK, PROFILE.product());
        assertEquals(PROFILE.id(), LayoutRegistry.defaults().match(statement("FDRL0001234", "Date"), "federal").id());
    }

    @Test
    void aStatementOfAnotherBanksIfscIsNotThisLayout() {
        assertNull(LayoutRegistry.defaults().match(statement("HDFC0001234", "Date"), "federal"));
    }

    @Test
    void theSameTextWithoutTheDateColumnHeaderIsNotThisLayout() {
        assertNull(LayoutRegistry.defaults().match(statement("FDRL0001234", "Value"), "federal"));
    }
}
