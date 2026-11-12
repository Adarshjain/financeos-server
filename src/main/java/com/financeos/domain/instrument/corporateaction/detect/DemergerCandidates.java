package com.financeos.domain.instrument.corporateaction.detect;

import com.financeos.api.instrument.dto.CorporateActionChildCandidate;
import com.financeos.domain.instrument.Instrument;
import com.financeos.domain.instrument.InstrumentRepository;
import com.financeos.domain.instrument.corporateaction.detect.DemergerChildFinder.ChildCandidate;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** The possible children for pending demerger suggestions, with the catalog instrument each one already has. */
@Component
public class DemergerCandidates {

    private static final String NSE = "NSE";

    private final DemergerChildFinder finder;
    private final InstrumentRepository instruments;

    public DemergerCandidates(DemergerChildFinder finder, InstrumentRepository instruments) {
        this.finder = finder;
        this.instruments = instruments;
    }

    /** Candidates per suggestion id; only pending demergers get an entry. One list read and one instrument lookup in all. */
    public Map<UUID, List<CorporateActionChildCandidate>> forSuggestions(Collection<CorporateActionSuggestion> all) {
        Map<UUID, List<ChildCandidate>> found = new HashMap<>();
        Set<String> isins = new HashSet<>();
        Set<String> symbols = new HashSet<>();
        for (CorporateActionSuggestion s : all) {
            if (s.getKind() != SuggestionKind.demerger || s.getStatus() != SuggestionStatus.pending) {
                continue;
            }
            List<ChildCandidate> c = finder.find(parentName(s), s.getInstrument().getSymbol(), s.getExDate());
            found.put(s.getId(), c);
            for (ChildCandidate x : c) {
                if (x.isin() != null) {
                    isins.add(x.isin());
                }
                symbols.add(x.symbol().toUpperCase(Locale.ROOT));
            }
        }
        Map<String, UUID> byIsin = new HashMap<>();
        Map<String, UUID> bySymbol = new HashMap<>();
        Set<String> isinOnNse = new HashSet<>();
        if (!symbols.isEmpty()) {
            List<Instrument> rows = new ArrayList<>();
            if (!isins.isEmpty()) {
                rows.addAll(instruments.findByIsinIn(isins));
            }
            rows.addAll(instruments.findByNseSymbolIn(symbols));
            for (Instrument i : rows) {
                if (i.getIsin() != null) {
                    boolean nse = NSE.equalsIgnoreCase(i.getExchange());
                    if (!byIsin.containsKey(i.getIsin()) || (nse && !isinOnNse.contains(i.getIsin()))) {
                        byIsin.put(i.getIsin(), i.getId());
                        if (nse) {
                            isinOnNse.add(i.getIsin());
                        }
                    }
                }
                if (i.getSymbol() != null && NSE.equalsIgnoreCase(i.getExchange())) {
                    bySymbol.putIfAbsent(i.getSymbol().toUpperCase(Locale.ROOT), i.getId());
                }
            }
        }
        Map<UUID, List<CorporateActionChildCandidate>> out = new HashMap<>();
        found.forEach((id, list) -> out.put(id, list.stream().map(c -> {
            UUID instrumentId = c.isin() == null ? null : byIsin.get(c.isin());
            if (instrumentId == null) {
                instrumentId = bySymbol.get(c.symbol().toUpperCase(Locale.ROOT));
            }
            return new CorporateActionChildCandidate(c.symbol(), c.name(), c.listedOn(), c.isin(),
                    c.symbol() + ".NS", instrumentId);
        }).toList()));
        return out;
    }

    public List<CorporateActionChildCandidate> forSuggestion(CorporateActionSuggestion s) {
        return forSuggestions(List.of(s)).getOrDefault(s.getId(), List.of());
    }

    /** The instrument's name, else its symbol (the suggestion keeps no link to the market event). */
    private static String parentName(CorporateActionSuggestion s) {
        String name = s.getInstrument().getName();
        return name == null || name.isBlank() ? s.getInstrument().getSymbol() : name;
    }
}
