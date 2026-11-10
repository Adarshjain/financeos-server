package com.financeos.statement.parser.layout.profiles;

import com.financeos.statement.parser.Line;
import com.financeos.statement.parser.Word;
import com.financeos.statement.parser.layout.LayoutProfile;
import com.financeos.statement.parser.layout.LayoutRegistry;
import com.financeos.statement.parser.ReadOutcome;
import com.financeos.statement.parser.RowResult;
import com.financeos.statement.parser.layout.ProfiledReader;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The Federal Bank savings layout of 2022 onwards on synthetic text, coordinates as printed: the layout's identity and its
 * block rows (a date line of its own, narration, the amount line, a little narration under it, same-day rows without a date).
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

    private static List<Line> fullStatement() {
        List<Line> l = statement("FDRL0001234", "Date");
        l.add(ln(1, 195, "Opening", 56, "Balance", 95, "Closing", 492, "Balance", 528));
        l.add(ln(1, 225, "\u20b91,000.00", 56, "\u20b9995.00", 492));
        l.add(ln(1, 294, "Day/Night", 56, "Comment", 108));
        // 25 Jun: narration above the amount line, a tail under it
        l.add(ln(1, 322, "25", 56, "Jun", 69));
        l.add(ln(1, 344, "UPI/SHOP", 108));
        l.add(ln(1, 351, "10.00", 423, "1,010.00", 513));
        l.add(ln(1, 356, "REF1", 108));
        // same day, no date line: narration then amounts on the next line, a tail under it
        l.add(ln(1, 388, "TO", 108, "SHOP2", 130));
        l.add(ln(1, 395, "20.00", 423, "990.00", 513));
        l.add(ln(1, 400, "REF2", 108));
        // 3 Jul, single digit day
        l.add(ln(1, 430, "3", 56, "Jul", 69));
        l.add(ln(1, 452, "INTEREST", 108, "5.00", 423, "995.00", 513));
        // the page footer is printed in the table's columns
        l.add(ln(1, 795, "9AM", 67, "In", 330, "Spent", 427, "Saved", 539));
        l.add(ln(1, 828, "CONTACT", 342, "US", 379, "PAGE", 478));
        return l;
    }

    @Test
    void blockRowsAreReadWithTheirDatesNarrationAndBalances() {
        ReadOutcome o = ProfiledReader.read(fullStatement(), PROFILE).outcome();
        List<RowResult> r = o.results();
        assertEquals(3, r.size());
        assertEquals(LocalDate.of(2022, 6, 25), r.get(0).date);
        assertEquals("UPI/SHOP REF1", r.get(0).description);
        assertEquals(10.0, r.get(0).amount, 0.001);
        assertEquals(1010.0, r.get(0).balance, 0.001);
        assertEquals(LocalDate.of(2022, 6, 25), r.get(1).date, "a row without its own date line takes the date of the row before");
        assertEquals("TO SHOP2 REF2", r.get(1).description);
        assertEquals(-20.0, r.get(1).amount, 0.001);
        assertEquals(LocalDate.of(2022, 7, 3), r.get(2).date);
        assertEquals(5.0, r.get(2).amount, 0.001);
        assertEquals("balance-chain", o.mode());
        assertEquals(1000.0, o.meta().openingBalance, 0.001);
        assertEquals(995.0, o.meta().closingBalance, 0.001);
        assertEquals("FDRL0001234", o.meta().ifsc);
    }

    @Test
    void theFooterIsNeverNarration() {
        for (RowResult r : ProfiledReader.read(fullStatement(), PROFILE).outcome().results()) {
            assertFalse(r.description.contains("CONTACT"), r.description);
            assertFalse(r.description.contains("Spent"), r.description);
        }
    }
}
