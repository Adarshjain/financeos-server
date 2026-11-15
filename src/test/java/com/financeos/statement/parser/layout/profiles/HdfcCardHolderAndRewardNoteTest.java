package com.financeos.statement.parser.layout.profiles;

import com.financeos.statement.parser.Line;
import com.financeos.statement.parser.ReadOutcome;
import com.financeos.statement.parser.Word;
import com.financeos.statement.parser.layout.LayoutProfile;
import com.financeos.statement.parser.layout.ProfiledReader;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The holder name next to the "Statement for ..." heading (2022 and 2024 layouts) and the reward-points-earned label,
 * which must not pick up a terms sentence that starts with the same words. Synthetic, invented values.
 */
class HdfcCardHolderAndRewardNoteTest {

    private static Line ln(int page, double top, Object... textAndX0) {
        List<Word> words = new ArrayList<>();
        for (int i = 0; i < textAndX0.length; i += 2) {
            String t = (String) textAndX0[i];
            double x0 = ((Number) textAndX0[i + 1]).doubleValue();
            words.add(new Word(t, x0, x0 + 5.2 * t.length(), top, page));
        }
        return new Line(words);
    }

    private static Line holderLine(Object... nameWords) {
        List<Object> w = new ArrayList<>(List.of("Name", 20, ":", 52));
        double x = 57;
        for (Object n : nameWords) {
            w.add(n);
            w.add(x);
            x += 5.2 * ((String) n).length() + 5;
        }
        w.addAll(List.of("Statement", 336, "for", 383, "HDFC", 398, "Bank", 427, "Credit", 452, "Card", 481));
        return ln(0, 58, w.toArray());
    }

    /** The lines given, then a header and one dated row (a read needs a table); the given lines come first. */
    private static ReadOutcome read(LayoutProfile p, List<Line> lines) {
        List<Line> all = new ArrayList<>(lines.stream().filter(l -> l.top() < 400).toList());
        all.add(ln(0, 444, "Date", 48, "Transaction", 110, "Description", 164, "Amount", 504, "(in", 540, "Rs.)", 554));
        all.add(ln(0, 483, "21/01/2026", 18, "UPI", 110, "TESTSHOP", 140, "50.00", 515));
        all.addAll(lines.stream().filter(l -> l.top() >= 400).toList());
        return ProfiledReader.read(all, p).outcome();
    }

    @Test
    void theHolderNameStopsBeforeTheStatementHeadingOnItsLine() {
        for (LayoutProfile p : List.of(HdfcCardDtda2024.build(), HdfcCardDtdap2022.build())) {
            assertEquals("TEST USER", read(p, List.of(holderLine("TEST", "USER"))).meta().holderName);
            assertEquals("TEST USER B", read(p, List.of(holderLine("TEST", "USER", "B"))).meta().holderName);
        }
    }

    @Test
    void aLineWithoutTheStatementHeadingGivesNoHolderName() {
        LayoutProfile p = HdfcCardDtda2024.build();
        assertNull(read(p, List.of(ln(0, 58, "Name", 20, ":", 52, "TEST", 57, "USER", 80))).meta().holderName);
    }

    @Test
    void aTermsSentenceStartingWithRewardPointsEarnedIsNotTheSummaryLabel() {
        LayoutProfile p = HdfcCardDtdap2022.build();
        List<Line> lines = new ArrayList<>();
        lines.add(ln(0, 300, "W.E.F", 20, "01/01/20", 60, "REWARD", 110, "POINTS", 150, "EARNED", 190, "ON", 230, "RENT",
                250, "WILL", 280, "BE", 310, "CAPPED", 330, "AT", 370, "1000", 390, "RP/MONTH.", 420));
        lines.add(ln(0, 785, "Reward", 246, "Points", 282, "Summary", 313));
        lines.add(ln(0, 799, "Feature", 106, "+", 138, "Bonus", 146));
        lines.add(ln(0, 804, "Opening", 17, "Balance", 53, "Disbursed", 207, "Adjusted/Lapsed", 276, "Closing", 355, "Balance", 387));
        lines.add(ln(0, 809, "Reward", 95, "Points", 127, "Earned", 154));
        lines.add(ln(0, 826, "2,397", 42, "468", 132, "0", 224, "0", 307, "2,865", 378));
        ReadOutcome o = read(p, lines);
        assertEquals(468.0, ((Number) o.summaryFields().get("reward_points_earned")).doubleValue(), 0.001);
        assertEquals(2865.0, ((Number) o.summaryFields().get("reward_points_balance")).doubleValue(), 0.001);
    }
}
