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
 *   <li>the period of an imported statement that covers it (overlapping statements are
 *       trimmed so the later one wins, so no date belongs to two periods);</li>
 *   <li>a projected cycle: cycles close monthly on the card's closing day (taken from the
 *       nearest statement, see {@link #closingDay}), clamped to short months; a projected
 *       cycle never overlaps a real statement, so a partial statement (a card opened mid-cycle)
 *       or a missing one leaves a short stub cycle beside it;</li>
 *   <li>the calendar month, when the card has no usable statement at all.</li>
 * </ol>
 * The current cycle normally has no statement yet (it is issued after the cycle closes), so
 * it is almost always projected from the latest statement.
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

    /** Guard against runaway loops (≈ 400 years of monthly cycles). */
    private static final int MAX_STEPS = 5000;

    /** How many statements (up to the reference one) inform the closing day. */
    private static final int CLOSING_DAY_LOOKBACK = 3;

    /** Non-overlapping statement periods, ordered by start. */
    private final List<Period> periods;

    private BillingCycles(List<Period> periods) {
        this.periods = periods;
    }

    /** Cycles from a card's statements; rejected and date-less statements are ignored. */
    public static BillingCycles fromStatements(List<Statement> statements) {
        List<Period> raw = new ArrayList<>();
        if (statements != null) {
            for (Statement s : statements) {
                if (s.getVerdict() == StatementVerdict.REJECTED || s.getPeriodStart() == null || s.getPeriodEnd() == null
                        || s.getPeriodEnd().isBefore(s.getPeriodStart())) {
                    continue;
                }
                raw.add(new Period(s.getPeriodStart(), s.getPeriodEnd()));
            }
        }
        return new BillingCycles(withoutOverlaps(raw));
    }

    /** Calendar months only — for accounts that have no billing cycle (not credit cards). */
    public static BillingCycles calendarMonths() {
        return new BillingCycles(List.of());
    }

    /**
     * Trims overlapping periods so the later statement wins (a revised or re-issued statement
     * replaces the overlapping days of the earlier one); a period trimmed to nothing is dropped.
     */
    private static List<Period> withoutOverlaps(List<Period> raw) {
        List<Period> sorted = new ArrayList<>(raw);
        sorted.sort(Comparator.comparing(Period::start).thenComparing(Period::end));
        List<Period> out = new ArrayList<>();
        for (Period p : sorted) {
            while (!out.isEmpty()) {
                Period last = out.get(out.size() - 1);
                if (last.end().isBefore(p.start())) {
                    break;
                }
                out.remove(out.size() - 1);
                LocalDate trimmedEnd = p.start().minusDays(1);
                if (!trimmedEnd.isBefore(last.start())) {
                    out.add(new Period(last.start(), trimmedEnd));
                    break;
                }
            }
            out.add(p);
        }
        return List.copyOf(out);
    }

    public boolean hasStatements() {
        return !periods.isEmpty();
    }

    /** The cycle containing {@code date}. */
    public Cycle containing(LocalDate date) {
        if (periods.isEmpty()) {
            return new Cycle(date.withDayOfMonth(1), date.withDayOfMonth(date.lengthOfMonth()), Source.CALENDAR_MONTH);
        }
        int before = -1;
        int after = -1;
        for (int i = 0; i < periods.size(); i++) {
            Period p = periods.get(i);
            if (!date.isBefore(p.start()) && !date.isAfter(p.end())) {
                return new Cycle(p.start(), p.end(), Source.STATEMENT);
            }
            if (p.end().isBefore(date)) {
                before = i;
            } else if (after < 0) {
                after = i;
            }
        }
        int closingDay = closingDay(before >= 0 ? before : after);
        LocalDate end = onDay(date, closingDay);
        if (end.isBefore(date)) {
            end = onDay(date.plusMonths(1), closingDay);
        }
        LocalDate start = onDay(end.minusMonths(1), closingDay).plusDays(1);
        // A projected cycle never overlaps a real statement.
        if (before >= 0 && !start.isAfter(periods.get(before).end())) {
            start = periods.get(before).end().plusDays(1);
        }
        if (after >= 0 && !end.isBefore(periods.get(after).start())) {
            end = periods.get(after).start().minusDays(1);
        }
        return new Cycle(start, end, Source.PROJECTED);
    }

    /** The cycle {@code n} cycles before the one containing {@code date} (n = 0 is that cycle). */
    public Cycle cyclesBefore(LocalDate date, int n) {
        Cycle cycle = containing(date);
        for (int i = 0; i < n; i++) {
            cycle = containing(cycle.start().minusDays(1));
        }
        return cycle;
    }

    /** Every cycle overlapping [from, to], oldest first; contiguous and non-overlapping. */
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

    /**
     * The closing day near statement {@code ref}: its end day, except that an end on the last
     * day of a short month is ambiguous (28 Feb closes a 28th, 30th or 31st card alike), so the
     * largest end day among the last few statements up to {@code ref} wins. With only short
     * month-end evidence, a month-end card is assumed.
     */
    private int closingDay(int ref) {
        LocalDate end = periods.get(ref).end();
        if (end.getDayOfMonth() < end.lengthOfMonth()) {
            return end.getDayOfMonth();
        }
        int day = end.getDayOfMonth();
        boolean onlyMonthEnds = true;
        for (int i = Math.max(0, ref - CLOSING_DAY_LOOKBACK + 1); i <= ref; i++) {
            LocalDate e = periods.get(i).end();
            day = Math.max(day, e.getDayOfMonth());
            onlyMonthEnds &= e.getDayOfMonth() == e.lengthOfMonth();
        }
        return onlyMonthEnds ? 31 : day;
    }

    private static LocalDate onDay(LocalDate monthOf, int day) {
        return monthOf.withDayOfMonth(Math.min(day, monthOf.lengthOfMonth()));
    }
}
