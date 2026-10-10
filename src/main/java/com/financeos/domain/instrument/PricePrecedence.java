package com.financeos.domain.instrument;

import org.springframework.lang.Nullable;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Which of the prices a user sees counts on a date: their own MANUAL row ({@code userId} set) over a
 * feed row ({@code userId} null). The visible-price queries never return another user's rows, so a
 * row with a user id is always the reader's own.
 */
public final class PricePrecedence {

    /** Non-id stand-in for "no user" in native queries that compare {@code user_id} to a string. */
    public static final String NO_USER = "-";

    private PricePrecedence() {
    }

    /** The user id as the native price queries take it ({@link #NO_USER} for none). */
    public static String userParam(@Nullable UUID userId) {
        return userId != null ? userId.toString() : NO_USER;
    }

    /** Of rows sharing one date (and instrument), the one that counts. */
    public static Optional<InstrumentPrice> preferred(@Nullable Collection<InstrumentPrice> sameDate) {
        if (sameDate == null) {
            return Optional.empty();
        }
        InstrumentPrice best = null;
        for (InstrumentPrice p : sameDate) {
            if (p != null && (best == null || (best.getUserId() == null && p.getUserId() != null))) {
                best = p;
            }
        }
        return Optional.ofNullable(best);
    }

    /** The counting row per instrument, from rows that are each instrument's latest date. */
    public static Map<UUID, InstrumentPrice> byInstrument(@Nullable Collection<InstrumentPrice> rows) {
        Map<UUID, InstrumentPrice> out = new LinkedHashMap<>();
        if (rows == null) {
            return out;
        }
        for (InstrumentPrice p : rows) {
            if (p == null || p.getInstrument() == null) {
                continue;
            }
            out.merge(p.getInstrument().getId(), p, (a, b) -> a.getUserId() == null && b.getUserId() != null ? b : a);
        }
        return out;
    }

    /**
     * One row per (instrument, date), keeping the order of first appearance: where a date has a feed
     * row and the user's own row, the user's row stands in the feed row's place.
     */
    public static List<InstrumentPrice> collapse(@Nullable Collection<InstrumentPrice> rows) {
        if (rows == null || rows.isEmpty()) {
            return List.of();
        }
        Map<Key, InstrumentPrice> out = new LinkedHashMap<>();
        for (InstrumentPrice p : rows) {
            if (p == null) {
                continue;
            }
            Key key = new Key(p.getInstrument() != null ? p.getInstrument().getId() : null, p.getAsOf());
            out.merge(key, p, (a, b) -> a.getUserId() == null && b.getUserId() != null ? b : a);
        }
        return new ArrayList<>(out.values());
    }

    private record Key(UUID instrumentId, LocalDate asOf) {
    }
}
