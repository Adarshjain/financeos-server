package com.financeos.domain.transaction.autolink;

import java.time.LocalDate;
import java.util.Collection;
import java.util.function.Function;

/**
 * The span of transaction dates an auto-link run has to look at: {@code from..to} inclusive, or everything
 * ({@link #ALL}). A run for newly written transactions only needs the anchors those can have changed
 * (within the linker's match window of the span) and the candidates needed to judge them.
 */
public record LinkScope(LocalDate from, LocalDate to) {

    /** The whole history (a null span). */
    public static final LinkScope ALL = new LinkScope(null, null);

    public LinkScope {
        if ((from == null) != (to == null)) {
            throw new IllegalArgumentException("A scope has both ends or neither");
        }
        if (from != null && to.isBefore(from)) {
            LocalDate swap = from;
            from = to;
            to = swap;
        }
    }

    public static LinkScope of(LocalDate from, LocalDate to) {
        return from == null || to == null ? ALL : new LinkScope(from, to);
    }

    /** The span of the given dates; any null date (or no item at all) means a full scope. */
    public static <T> LinkScope spanOf(Collection<T> items, Function<T, LocalDate> date) {
        LocalDate min = null;
        LocalDate max = null;
        for (T item : items) {
            LocalDate d = date.apply(item);
            if (d == null) {
                return ALL;
            }
            min = min == null || d.isBefore(min) ? d : min;
            max = max == null || d.isAfter(max) ? d : max;
        }
        return of(min, max);
    }

    public boolean isAll() {
        return from == null;
    }

    /** The smallest scope covering both; ALL wins. */
    public LinkScope union(LinkScope other) {
        if (isAll() || other.isAll()) {
            return ALL;
        }
        return new LinkScope(from.isBefore(other.from) ? from : other.from, to.isAfter(other.to) ? to : other.to);
    }

    /** This span grown by {@code days} on both sides (ALL stays ALL). */
    public LinkScope widen(int days) {
        return isAll() ? ALL : new LinkScope(from.minusDays(days), to.plusDays(days));
    }

    /** Anchors that can change when transactions in this span arrive: within the match window of it. */
    public LinkScope anchors(int window) {
        return widen(window);
    }

    /** Candidates needed to decide those anchors, including the "only fit on both sides" checks. */
    public LinkScope candidates(int window) {
        return widen(3 * window);
    }

    public boolean contains(LocalDate date) {
        return isAll() || (!date.isBefore(from) && !date.isAfter(to));
    }
}
