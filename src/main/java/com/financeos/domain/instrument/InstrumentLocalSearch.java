package com.financeos.domain.instrument;

import org.springframework.data.domain.PageRequest;
import org.springframework.lang.Nullable;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * Catalog search as one user sees the catalog: the type filter and the text match apply to their own
 * view (their renamed / retyped instruments), and the database does the paging. Used by the catalog
 * picker (GET /instruments/catalog-search); the instrument list (GET /instruments) filters, sorts and
 * counts on the user's view in one query instead ({@link InstrumentRepository#listAsSeenBy}).
 */
public final class InstrumentLocalSearch {

    /** Oracle's IN-list limit is 1000; stay below it. */
    public static final int IN_CHUNK = 900;

    private InstrumentLocalSearch() {
    }

    /**
     * One page of the catalog instruments matching {@code search} (name, symbol, ISIN, AMFI code;
     * blank = all) whose type — as {@code overrides} shows it — is {@code type} (null = any). The
     * database filters on the catalog type and pages ({@code page}, {@code size}); the first page also
     * carries the user's own renamed or retyped instruments that match only in their view (so it may
     * hold a few more than {@code size}), and a row the user retyped away from {@code type} is dropped.
     */
    public static List<Instrument> find(InstrumentRepository repository, @Nullable String search,
                                        @Nullable InstrumentType type, InstrumentOverrides overrides,
                                        int page, int size) {
        String needle = search == null || search.isBlank() ? null : search.trim();
        Map<UUID, Instrument> found = new LinkedHashMap<>();
        if (page == 0 && !overrides.isEmpty()) {
            List<UUID> mine = new ArrayList<>();
            for (UUID id : overrides.instrumentIds()) {
                InstrumentOverrides.Fields f = overrides.get(id);
                if (f == null) {
                    continue;
                }
                boolean renamedMatch = needle != null && (contains(f.name(), needle) || contains(f.symbol(), needle));
                boolean retypedInto = type != null && f.type() == type;
                if (renamedMatch || retypedInto) {
                    mine.add(id);
                }
            }
            for (List<UUID> chunk : chunks(mine)) {
                for (Instrument inst : repository.findAllById(chunk)) {
                    if (needle == null || matches(inst, overrides, needle)) {
                        found.put(inst.getId(), inst);
                    }
                }
            }
        }
        List<Instrument> rows = repository.searchInstrumentsPage(needle, type, PageRequest.of(page, size));
        if (rows != null) {
            for (Instrument inst : rows) {
                found.putIfAbsent(inst.getId(), inst);
            }
        }
        List<Instrument> out = new ArrayList<>(found.size());
        for (Instrument inst : found.values()) {
            if (type == null || overrides.type(inst) == type) {
                out.add(inst);
            }
        }
        return out;
    }

    /** Whether the instrument matches as the user sees it (their name / symbol) or by its catalog fields. */
    private static boolean matches(Instrument inst, InstrumentOverrides overrides, String needle) {
        return contains(overrides.name(inst), needle) || contains(overrides.symbol(inst), needle)
                || contains(inst.getName(), needle) || contains(inst.getSymbol(), needle)
                || contains(inst.getIsin(), needle) || contains(inst.getAmfiCode(), needle);
    }

    private static boolean contains(@Nullable String value, String needle) {
        return value != null && value.toLowerCase(Locale.ROOT).contains(needle.toLowerCase(Locale.ROOT));
    }

    /** {@code ids} in lists of at most {@link #IN_CHUNK}. */
    public static <T> List<List<T>> chunks(Collection<T> ids) {
        List<T> all = new ArrayList<>(ids);
        List<List<T>> out = new ArrayList<>();
        for (int i = 0; i < all.size(); i += IN_CHUNK) {
            out.add(all.subList(i, Math.min(all.size(), i + IN_CHUNK)));
        }
        return out;
    }
}
