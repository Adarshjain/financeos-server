package com.financeos.statement.parser.layout;

import com.financeos.statement.parser.Line;
import com.financeos.statement.parser.RowResult;
import com.financeos.statement.parser.StatementParseEngine;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static com.financeos.statement.parser.layout.LayoutFixtures.*;
import static org.junit.jupiter.api.Assertions.*;

/** The profiled reader builds a row's description from the same words exactly as the generic reader does. */
class NarrationRulesTest {

    @AfterEach
    void reset() {
        LayoutRouting.reset();
    }

    /** Rows of 10.00 debits with chaining balances; the narration words are given as text/x0 pairs. */
    private static List<Line> statement(Object[]... narrations) {
        List<Line> lines = new ArrayList<>(List.of(
                ln(0, 40, "Statement", 40, "Period:", 100, "01/01/2026", 150, "to", 220, "31/01/2026", 240),
                ln(0, 50, "Opening", 40, "Balance:", 100, "1,000.00", 160),
                bankHeader(0, 100, 0)));
        double balance = 1000;
        for (int i = 0; i < narrations.length; i++) {
            balance -= 10;
            List<Object> spec = new ArrayList<>();
            spec.add(String.format("%02d/01/2026", i + 2));
            spec.add(DATE);
            spec.addAll(List.of(narrations[i]));
            spec.add("10.00");
            spec.add(DEBIT);
            spec.add(String.format("%,.2f", balance));
            spec.add(BAL);
            lines.add(ln(0, 110 + i * 10, spec.toArray()));
        }
        return lines;
    }

    private static List<String> descriptions(List<Line> lines, boolean layout) {
        LayoutRouting.setRegistry(new LayoutRegistry(List.of(bankProfile())));
        LayoutRouting.setForcedReader(layout ? "layout" : "v1");
        List<String> out = new ArrayList<>();
        for (RowResult r : StatementParseEngine.parseLines(lines, false, null).transactions()) {
            out.add(r.description);
        }
        return out;
    }

    private static void sameAsV1(List<Line> lines, String... expected) {
        List<String> v1 = descriptions(lines, false);
        assertEquals(v1, descriptions(lines, true));
        assertEquals(List.of(expected), v1);
    }

    @Test
    void aDashRightBeforeTheAmountIsASignMarkerNotNarration() {
        sameAsV1(statement(new Object[]{"PAYMENT", 120, "RECEIVED", 170, "-", 300}), "PAYMENT RECEIVED");
    }

    @Test
    void aForeignCurrencyAmountInsideTheNarrationIsNotNarration() {
        sameAsV1(statement(new Object[]{"SHOP", 120, "(", 160, "USD", 170, "9.99", 200, ")", 235, "STORE", 245}),
                "SHOP ( USD ) STORE");
    }

    @Test
    void aCurrencyGlyphInFrontOfAnAmountIsDropped() {
        sameAsV1(statement(new Object[]{"SHOP", 120, "Rs", 200, "100.00", 215}), "SHOP");
    }

    @Test
    void aDashInsideATextRunIsKept() {
        sameAsV1(statement(new Object[]{"AMAZON", 120, "-", 155, "GROCERY", 160}), "AMAZON - GROCERY");
    }

    @Test
    void aDanglingDashAwayFromTheWordsAndTheAmountIsDropped() {
        sameAsV1(statement(new Object[]{"SHOP", 120, "-", 220}), "SHOP");
    }

    @Test
    void aLoneLetterOutsideATextRunIsDroppedAndOneInsideIsKept() {
        sameAsV1(statement(new Object[]{"SHOP", 120, "X", 220}, new Object[]{"K", 120, "SHARMA", 127}),
                "SHOP", "K SHARMA");
    }

    @Test
    void aBareCrOrDrWordInsideTheNarrationIsKeptAsPrintedUnlikeTheGenericReader() {
        assertEquals(List.of("SHOP Dr"), descriptions(statement(new Object[]{"SHOP", 120, "Dr", 200}), true));
    }

    @Test
    void ordinaryNarrationIsUntouched() {
        sameAsV1(statement(new Object[]{"UPI", 120, "SHOP,CITY", 150, "REF123", 215}), "UPI SHOP,CITY REF123");
    }

    @Test
    void continuationLinesDropAmountsCurrencyGlyphsAndLoneLetters() {
        LayoutProfile p = bank("cont").continuation(10, 0).build();
        List<Line> lines = List.of(bankHeader(0, 100, 0),
                bankRow(0, 110, 0, "05/01/2026", "SHOP", "10.00", null, "90.00"),
                ln(0, 118, "REF", 120, "Rs", 160, "5.00", 175, "X", 250));
        assertEquals("SHOP REF", ProfiledReader.read(lines, p).outcome().results().get(0).description);
    }
}
