package com.financeos.domain.account.cycle;

import com.financeos.domain.statement.Statement;
import com.financeos.domain.statement.StatementVerdict;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * A credit card's billing cycles, derived from its imported statements. Pure: no I/O.
 *
 * <p>The cycle containing a date is, in order of preference:
 * <ol>
 *   <li>the period of an imported statement that covers it;</li>
 *   <li>a projection from the nearest statement: cycles repeat monthly on that statement's
 *       closing day (clamped to short months), forward from the latest statement before the
 *       date and backward from the earliest one after it, never overlapping a real statement;</li>
 *   <li>the calendar month, when the card has no usable statement at all.</li>
 * </ol>
 * The current cycle normally has no statement yet (it is issued after the cycle closes), so
 * it is almost always a projection from the latest statement.
 */
public final class BillingCycles {

    /** One cycle, both ends inclusive. */
    public record Cycle(LocalDate start, LocalDate end, Source source) {
        public boolean contains(LocalDate date) {
            return !date.isBefore(start) && !date.isAfter(end);
        }

        /** True only when the card had no statements and the calendar month stood in. */
        public boolean calendarFallback() {
            return source == Source.CALENDAR_MONTH;
        }
    }

    public enum Source { STATEMENT, PROJECTED, CALENDAR_MONTH }

    private record Period(LocalDate start, LocalDate end) {
    }

    /** Guard against runaway projection loops (≈ 400 years of monthly cycles). */
    private static final int MAX_STEPS = 5000;

    private final List<Period> periods;

    private BillingCycles(List<Period> periods) {
        this.periods = periods;
    }

    /** Cycles from a card's statements; rejected and date-less statements are ignored. */
    public static BillingCycles fromStatements(List<Statement> statements) {
        List<Period> periods = new ArrayList<>();
        if (statements != null) {
            for (Statement s : statements) {
                if (s.getVerdict() == StatementVerdict.REJECTED || s.getPeriodStart() == null || s.getPeriodEnd() == null
                        || s.getPeriodEnd().isBefore(s.getPeriodStart())) {
                    continue;
                }
                periods.add(new Period(s.getPeriodStart(), s.getPeriodEnd()));
            }
        }
        periods.sort(Comparator.comparing(Period::start).thenComparing(Period::end));
        return new BillingCycles(List.copyOf(periods));
    }

    public boolean hasStatements() {
        return !periods.isEmpty();
    }

    /** The cycle containing {@code date}. */
    public Cycle containing(LocalDate date) {
        if (periods.isEmpty()) {
            return new Cycle(date.withDayOfMonth(1), date.withDayOfMonth(date.lengthOfMonth()), Source.CALENDAR_MONTH);
        }
        Period before = null;
        Period after = null;
        for (Period p : periods) {
            if (!date.isBefore(p.start()) && !date.isAfter(p.end())) {
                return new Cycle(p.start(), p.end(), Source.STATEMENT);
            }
            if (p.end().isBefore(date) && (before == null || p.end().isAfter(before.end()))) {
                before = p;
            }
            if (p.start().isAfter(date) && (after == null || p.start().isBefore(after.start()))) {
                after = p;
            }
        }
        if (before != null) {
            return projectForward(before, date, after);
        }
        return projectBackward(after, date);
    }

    /** The cycle {@code n} cycles before the one containing {@code date} (n = 0 is that cycle). */
    public Cycle cyclesBefore(LocalDate date, int n) {
        Cycle cycle = containing(date);
        for (int i = 0; i < n; i++) {
            cycle = containing(cycle.start().minusDays(1));
        }
        return cycle;
    }

    /** Every cycle overlapping [from, to], oldest first. */
    public List<Cycle> between(LocalDate from, LocalDate to) {
        List<Cycle> out = new ArrayList<>();
        if (to.isBefore(from)) {
            return out;
        }
        Cycle cycle = containing(from);
        int guard = 0;
        while (!cycle.start().isAfter(to) && guard++ < MAX_STEPS) {
            out.add(cycle);
            cycle = containing(cycle.end().plusDays(1));
        }
        return out;
    }

    /** Monthly cycles closing on {@code from}'s closing day, after {@code from}, never into {@code next}. */
    private static Cycle projectForward(Period from, LocalDate date, Period next) {
        int closingDay = from.end().getDayOfMonth();
        LocalDate start = from.end().plusDays(1);
        LocalDate end = onDay(from.end().plusMonths(1), closingDay);
        int k = 1;
        while (end.isBefore(date) && k < MAX_STEPS) {
            start = end.plusDays(1);
            k++;
            end = onDay(from.end().plusMonths(k), closingDay);
        }
        if (next != null && !end.isBefore(next.start())) {
            end = next.start().minusDays(1);
        }
        return new Cycle(start, end, Source.PROJECTED);
    }

    /** Monthly cycles opening on {@code to}'s opening day, before {@code to}. */
    private static Cycle projectBackward(Period to, LocalDate date) {
        int openingDay = to.start().getDayOfMonth();
        LocalDate end = to.start().minusDays(1);
        LocalDate start = onDay(to.start().minusMonths(1), openingDay);
        int k = 1;
        while (start.isAfter(date) && k < MAX_STEPS) {
            end = start.minusDays(1);
            k++;
            start = onDay(to.start().minusMonths(k), openingDay);
        }
        return new Cycle(start, end, Source.PROJECTED);
    }

    private static LocalDate onDay(LocalDate monthOf, int day) {
        return monthOf.withDayOfMonth(Math.min(day, monthOf.lengthOfMonth()));
    }
}
