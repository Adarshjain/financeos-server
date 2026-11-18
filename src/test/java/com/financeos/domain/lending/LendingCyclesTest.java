package com.financeos.domain.lending;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class LendingCyclesTest {

    private int seq;

    private Lending e(LendingDirection dir, String amount, String date) {
        Lending l = new Lending();
        l.setId(new UUID(0, ++seq));
        l.setDirection(dir);
        l.setKind(LendingKind.principal);
        l.setAmount(new BigDecimal(amount));
        l.setEntryDate(LocalDate.parse(date));
        l.setCreatedAt(Instant.parse("2026-01-01T00:00:00Z").plusSeconds(seq));
        return l;
    }

    private Lending lent(String amount, String date) {
        return e(LendingDirection.lent, amount, date);
    }

    private Lending borrowed(String amount, String date) {
        return e(LendingDirection.borrowed, amount, date);
    }

    private static List<Integer> cycles(List<LendingCycles.Placed> p) {
        return p.stream().map(LendingCycles.Placed::cycle).toList();
    }

    @Test
    void noEntriesHaveNoCyclesAndNoOpenCycle() {
        assertTrue(LendingCycles.assign(List.of()).isEmpty());
        assertTrue(LendingCycles.openCycleEntries(List.of()).isEmpty());
    }

    @Test
    void unsettledEntriesFormOneOpenCycle() {
        Lending a = lent("500", "2026-03-01");
        Lending b = borrowed("200", "2026-03-02");

        var placed = LendingCycles.assign(List.of(b, a));

        assertEquals(List.of(a, b), placed.stream().map(LendingCycles.Placed::entry).toList());
        assertEquals(List.of(1, 1), cycles(placed));
        assertEquals(new BigDecimal("300"), placed.get(1).runningBalance());
        assertEquals(List.of(a, b), LendingCycles.openCycleEntries(List.of(a, b)));
    }

    @Test
    void landingOnZeroClosesTheCycleAndTheNextEntryStartsAnother() {
        Lending a = lent("500", "2026-03-01");
        Lending b = borrowed("500", "2026-03-02");
        Lending c = lent("100", "2026-03-03");

        var placed = LendingCycles.assign(List.of(a, b, c));

        assertEquals(List.of(1, 1, 2), cycles(placed));
        assertEquals(0, placed.get(1).runningBalance().signum());
        assertEquals(List.of(c), LendingCycles.openCycleEntries(List.of(a, b, c)));
    }

    @Test
    void severalCyclesAreNumberedOldestFirst() {
        Lending a = lent("100", "2026-01-01");
        Lending b = borrowed("100", "2026-01-02");
        Lending c = lent("50", "2026-02-01");
        Lending d = borrowed("50", "2026-02-02");
        Lending f = lent("70", "2026-03-01");
        Lending g = lent("30", "2026-03-02");

        assertEquals(List.of(1, 1, 2, 2, 3, 3), cycles(LendingCycles.assign(List.of(g, d, a, f, c, b))));
        assertEquals(List.of(f, g), LendingCycles.openCycleEntries(List.of(g, d, a, f, c, b)));
    }

    @Test
    void crossingZeroWithoutLandingOnItDoesNotCloseTheCycle() {
        Lending a = lent("500", "2026-03-01");
        Lending b = borrowed("700", "2026-03-02");   // -200
        Lending c = lent("200", "2026-03-03");       // 0 -> closes here

        var placed = LendingCycles.assign(List.of(a, b, c));

        assertEquals(List.of(1, 1, 1), cycles(placed));
        assertEquals(new BigDecimal("-200"), placed.get(1).runningBalance());
        assertEquals(List.of(), LendingCycles.openCycleEntries(List.of(a, b, c)));
    }

    @Test
    void sameDateEntriesOrderByCreationTimeAndDecideTheBoundary() {
        Lending lend = lent("100", "2026-03-01");       // created first
        Lending back = borrowed("100", "2026-03-01");   // created second: closes
        Lending next = lent("10", "2026-03-01");        // created third: new cycle

        var placed = LendingCycles.assign(List.of(next, back, lend));

        assertEquals(List.of(lend, back, next), placed.stream().map(LendingCycles.Placed::entry).toList());
        assertEquals(List.of(1, 1, 2), cycles(placed));
    }

    @Test
    void borrowedSideCycleClosesWhenTheNegativeBalanceReturnsToZero() {
        Lending a = borrowed("300", "2026-03-01");
        Lending b = lent("300", "2026-03-05");

        var placed = LendingCycles.assign(List.of(a, b));

        assertEquals(new BigDecimal("-300"), placed.get(0).runningBalance());
        assertEquals(List.of(1, 1), cycles(placed));
        assertTrue(LendingCycles.openCycleEntries(List.of(a, b)).isEmpty());
    }

    @Test
    void squarePersonHasNoOpenCycle() {
        assertTrue(LendingCycles.openCycleEntries(List.of(lent("10", "2026-03-01"), borrowed("10", "2026-03-02"))).isEmpty());
    }
}
