package com.financeos.domain.instrument;

import org.springframework.lang.Nullable;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Which catalog row an ISIN means for a user. One company can have a row per exchange (NSE and BSE), and
 * they are the same shares, so an import must never split them into two holdings. The rule, in order:
 * (a) the listing the user already holds, (b) the listing on the exchange the row / file names, when that
 * row exists, (c) the default listing (NSE, else the oldest row). With one row for the ISIN it is that row.
 */
public final class InstrumentListings {

    private InstrumentListings() {
    }

    /** The default listing of {@code rows} (all of one ISIN): the NSE one, else the oldest. */
    public static Instrument defaultListing(List<Instrument> rows) {
        List<Instrument> sorted = oldestFirst(rows);
        for (Instrument i : sorted) {
            if ("NSE".equalsIgnoreCase(i.getExchange())) {
                return i;
            }
        }
        return sorted.get(0);
    }

    /** The rule above over {@code rows} (all of one ISIN); {@code heldIds} are the ids the user holds. */
    public static Instrument pick(List<Instrument> rows, Set<UUID> heldIds, @Nullable String exchange) {
        List<Instrument> pool = rows;
        if (heldIds != null && !heldIds.isEmpty()) {
            List<Instrument> held = rows.stream().filter(i -> heldIds.contains(i.getId())).toList();
            if (!held.isEmpty()) {
                pool = held;
            }
        }
        if (exchange != null && !exchange.isBlank()) {
            for (Instrument i : oldestFirst(pool)) {
                if (exchange.trim().equalsIgnoreCase(i.getExchange())) {
                    return i;
                }
            }
        }
        return defaultListing(pool);
    }

    /**
     * The instrument {@code userId} means by {@code isin}, {@code exchange} being the one the row / file
     * names (may be null). Empty when no row has the ISIN. One query when only one row has it, and for an
     * ISIN with a second listing one more for what the user holds.
     */
    public static Optional<Instrument> byIsin(InstrumentRepository repository, @Nullable UUID userId, String isin,
                                              @Nullable String exchange) {
        Optional<Instrument> base = repository.findByIsin(isin);
        if (base == null || base.isEmpty() || !"NSE".equalsIgnoreCase(base.get().getExchange())) {
            // No row, or no NSE row (so no second listing to choose from: the default is the oldest).
            return base == null ? Optional.empty() : base;
        }
        Optional<Instrument> bse = repository.findByIsinAndExchange(isin, "BSE");
        if (bse == null || bse.isEmpty()) {
            return base;
        }
        List<Instrument> rows = List.of(base.get(), bse.get());
        return Optional.of(pick(rows, held(repository, userId, rows), exchange));
    }

    /**
     * {@link #byIsin} for many ISINs at once (ISIN to the exchange its row names, null when none): the rows
     * in chunked queries, and one more for the user's holdings when some ISIN has two listings. Keyed by ISIN
     * as stored.
     */
    public static Map<String, Instrument> byIsins(InstrumentRepository repository, @Nullable UUID userId,
                                                  Map<String, String> exchangeByIsin) {
        Batch batch = load(repository, userId, exchangeByIsin.keySet(), false);
        Map<String, Instrument> out = new HashMap<>();
        exchangeByIsin.forEach((isin, exchange) -> batch.get(isin, exchange).ifPresent(i -> out.put(i.getIsin(), i)));
        return out;
    }

    /**
     * The catalog rows of many ISINs and what the user holds of them, loaded once; {@link #get} then answers
     * per row (ISIN and exchange) with {@link #byIsin}'s rule and no query. With {@code sticky}, the first
     * answer for an ISIN that has two listings is kept, as if the holding it leads to already existed (a
     * commit that creates holdings as it goes must not split one company across the listings).
     */
    public static final class Batch {
        private final Map<String, List<Instrument>> rows;
        private final Set<UUID> held;
        private final boolean sticky;
        private final Map<String, Instrument> chosen = new HashMap<>();

        private Batch(Map<String, List<Instrument>> rows, Set<UUID> held, boolean sticky) {
            this.rows = rows;
            this.held = held;
            this.sticky = sticky;
        }

        /** The listing of {@code isin} (trimmed) for {@code exchange} (may be null); empty when no row has it. */
        public Optional<Instrument> get(@Nullable String isin, @Nullable String exchange) {
            List<Instrument> list = isin == null ? null : rows.get(isin.trim());
            if (list == null || list.isEmpty()) {
                return Optional.empty();
            }
            if (list.size() == 1) {
                return Optional.of(list.get(0));
            }
            String key = isin.trim();
            Instrument kept = sticky ? chosen.get(key) : null;
            if (kept == null) {
                kept = pick(list, held, exchange);
                if (sticky) {
                    chosen.put(key, kept);
                }
            }
            return Optional.of(kept);
        }

        /** A row saved after the load (a new instrument), so later lookups of its ISIN find it. */
        public void add(Instrument instrument) {
            if (instrument.getIsin() != null) {
                List<Instrument> list = rows.computeIfAbsent(instrument.getIsin().trim(), k -> new ArrayList<>());
                if (!list.contains(instrument)) {
                    list.add(instrument);
                }
            }
        }
    }

    /** Loads the rows of {@code isins} (trimmed) and, when some ISIN has two listings, the user's holdings among them. */
    public static Batch load(InstrumentRepository repository, @Nullable UUID userId, java.util.Collection<String> isins,
                             boolean sticky) {
        Set<String> keys = new LinkedHashSet<>();
        for (String isin : isins) {
            if (isin != null && !isin.isBlank()) {
                keys.add(isin.trim());
            }
        }
        Map<String, List<Instrument>> byIsin = new HashMap<>();
        for (List<String> chunk : InstrumentLocalSearch.chunks(keys)) {
            for (Instrument i : repository.findByIsinIn(chunk)) {
                byIsin.computeIfAbsent(i.getIsin(), k -> new ArrayList<>()).add(i);
            }
        }
        List<Instrument> contested = new ArrayList<>();
        byIsin.values().stream().filter(l -> l.size() > 1).forEach(contested::addAll);
        return new Batch(byIsin, held(repository, userId, contested), sticky);
    }

    private static Set<UUID> held(InstrumentRepository repository, @Nullable UUID userId, List<Instrument> rows) {
        if (userId == null || rows.isEmpty()) {
            return Set.of();
        }
        Set<UUID> out = new HashSet<>();
        for (List<UUID> chunk : InstrumentLocalSearch.chunks(rows.stream().map(Instrument::getId).toList())) {
            List<UUID> found = repository.findHeldInstrumentIds(userId, chunk);
            if (found != null) {
                out.addAll(found);
            }
        }
        return out;
    }

    private static List<Instrument> oldestFirst(List<Instrument> rows) {
        List<Instrument> sorted = new ArrayList<>(rows);
        sorted.sort(Comparator.comparing(Instrument::getCreatedAt, Comparator.nullsLast(Comparator.naturalOrder()))
                .thenComparing(Instrument::getId, Comparator.nullsLast(Comparator.naturalOrder())));
        return sorted;
    }
}
