package com.financeos.domain.lending;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

/**
 * Derived lending cycles of one person's entries. Nothing is stored: a cycle is the run of entries, in
 * ledger order ({@link #CHRONOLOGICAL}), from a zero balance until the running balance (+lent, -borrowed)
 * next lands exactly on zero; that entry closes the cycle and the next entry starts the next one.
 * Numbers are 1-based, oldest first. A balance that crosses zero without landing on it does not close a
 * cycle. The latest cycle is open while the final balance is non-zero.
 */
public final class LendingCycles {

    /** Ledger order, oldest first: entry date, then creation time, then id (stable). */
    public static final Comparator<Lending> CHRONOLOGICAL = Comparator
            .comparing(Lending::getEntryDate, Comparator.nullsFirst(Comparator.<LocalDate>naturalOrder()))
            .thenComparing(Lending::getCreatedAt, Comparator.nullsFirst(Comparator.<Instant>naturalOrder()))
            .thenComparing(Lending::getId, Comparator.nullsFirst(Comparator.<UUID>naturalOrder()));

    /** An entry with the balance right after it and the cycle it belongs to. */
    public record Placed(Lending entry, BigDecimal runningBalance, int cycle) {
    }

    private LendingCycles() {
    }

    /** The entries in ledger order, each with its running balance and cycle number. */
    public static List<Placed> assign(Collection<Lending> entries) {
        List<Lending> sorted = new ArrayList<>(entries);
        sorted.sort(CHRONOLOGICAL);
        List<Placed> out = new ArrayList<>(sorted.size());
        BigDecimal running = BigDecimal.ZERO;
        int cycle = 1;
        for (Lending l : sorted) {
            running = l.getDirection() == LendingDirection.lent ? running.add(l.getAmount()) : running.subtract(l.getAmount());
            out.add(new Placed(l, running, cycle));
            if (running.signum() == 0) {
                cycle++;
            }
        }
        return out;
    }

    /** Entries of the open (unsettled) latest cycle in ledger order; empty when square or without entries. */
    public static List<Lending> openCycleEntries(Collection<Lending> entries) {
        List<Placed> placed = assign(entries);
        if (placed.isEmpty() || placed.get(placed.size() - 1).runningBalance().signum() == 0) {
            return List.of();
        }
        int open = placed.get(placed.size() - 1).cycle();
        return placed.stream().filter(p -> p.cycle() == open).map(Placed::entry).toList();
    }
}
