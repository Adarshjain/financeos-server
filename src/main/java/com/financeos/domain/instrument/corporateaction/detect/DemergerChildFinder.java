package com.financeos.domain.instrument.corporateaction.detect;

import com.financeos.domain.instrument.corporateaction.detect.NseListingsClient.Listing;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Possible children of a demerger: companies the exchange listed shortly after the ex-date whose name shares the
 * parent's leading name word. A pick for the user, never an answer.
 */
@Component
public class DemergerChildFinder {

    static final int WINDOW_DAYS = 180;
    static final int MAX = 3;
    private static final Set<String> FILLER = Set.of("limited", "ltd", "the", "and", "of", "company", "co",
            "corporation", "corp", "india", "industries", "enterprises", "pvt", "private");

    /** A possible child: the listing row. */
    public record ChildCandidate(String symbol, String name, LocalDate listedOn, @Nullable String isin) {
    }

    private final NseListingsClient listings;

    public DemergerChildFinder(NseListingsClient listings) {
        this.listings = listings;
    }

    public List<ChildCandidate> find(String parentName, @Nullable String parentSymbol, LocalDate exDate) {
        List<String> parent = tokens(parentName);
        if (parent.isEmpty()) {
            parent = tokens(parentSymbol);
        }
        if (parent.isEmpty()) {
            return List.of();
        }
        String first = parent.get(0);
        Set<String> parentSet = new HashSet<>(parent);
        int required = Math.min(2, parentSet.size());
        LocalDate last = exDate.plusDays(WINDOW_DAYS);
        record Ranked(Listing listing, int shared) {
        }
        List<Ranked> hits = new ArrayList<>();
        for (Listing l : listings.listings()) {
            if (!l.listedOn().isAfter(exDate) || l.listedOn().isAfter(last)) {
                continue;
            }
            if (parentSymbol != null && parentSymbol.trim().equalsIgnoreCase(l.symbol())) {
                continue;
            }
            List<String> t = tokens(l.name());
            if (!t.contains(first)) {
                continue;
            }
            int shared = (int) t.stream().distinct().filter(parentSet::contains).count();
            if (shared < required) {
                continue;
            }
            hits.add(new Ranked(l, shared));
        }
        hits.sort(Comparator.comparingInt(Ranked::shared).reversed()
                .thenComparing(r -> r.listing().listedOn()).thenComparing(r -> r.listing().symbol()));
        return hits.stream().limit(MAX)
                .map(r -> new ChildCandidate(r.listing().symbol(), r.listing().name(), r.listing().listedOn(), r.listing().isin()))
                .toList();
    }

    /** Lowercase words (2+ characters) of the name without punctuation (apostrophes dropped) and filler words. */
    static List<String> tokens(@Nullable String name) {
        List<String> out = new ArrayList<>();
        if (name == null) {
            return out;
        }
        for (String w : name.toLowerCase(Locale.ROOT).replaceAll("['\u2019]", "").replaceAll("[^a-z0-9]+", " ").trim().split(" ")) {
            if (w.length() >= 2 && !FILLER.contains(w)) {
                out.add(w);
            }
        }
        return out;
    }
}
