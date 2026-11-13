package com.financeos.domain.instrument.corporateaction.detect;

import com.financeos.api.instrument.dto.CorporateActionSchemeChild;
import com.financeos.api.instrument.dto.CorporateActionSchemeDetails;
import com.financeos.api.instrument.dto.CorporateActionSchemeHoldingsCheck;
import com.financeos.api.instrument.dto.CorporateActionSchemeSource;
import com.financeos.domain.instrument.Instrument;
import com.financeos.domain.instrument.InstrumentRepository;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * The stored notice readings ({@link SchemeEvidence}) as the API's {@code details}: one evidence query, one children
 * query and one instrument lookup for a whole page, never a network call. A stock with no reading has no entry.
 */
@Component
public class SchemeDetailsLookup {

    private static final String NSE = "NSE";

    /** One thing to look up: the caller's id, the parent's symbol, the scheme kind and ex-date (null for a merger). */
    public record Ask(UUID id, UUID instrumentId, @Nullable String symbol, SchemeNoticeReader.Kind kind, @Nullable LocalDate exDate) {
    }

    private final SchemeEvidenceRepository evidence;
    private final SchemeEvidenceChildRepository children;
    private final InstrumentRepository instruments;
    private final SchemeCheckRepository checks;
    private final com.fasterxml.jackson.databind.ObjectMapper mapper;

    public SchemeDetailsLookup(SchemeEvidenceRepository evidence, SchemeEvidenceChildRepository children,
                               InstrumentRepository instruments, SchemeCheckRepository checks,
                               com.fasterxml.jackson.databind.ObjectMapper mapper) {
        this.checks = checks;
        this.mapper = mapper;
        this.evidence = evidence;
        this.children = children;
        this.instruments = instruments;
    }

    /** Details per ask id; demerger and merger suggestions only (any status). */
    public Map<UUID, CorporateActionSchemeDetails> forSuggestions(Collection<CorporateActionSuggestion> all) {
        List<Ask> asks = new ArrayList<>();
        for (CorporateActionSuggestion s : all) {
            if (s.getKind() == SuggestionKind.demerger) {
                asks.add(new Ask(s.getId(), s.getInstrument().getId(), s.getInstrument().getSymbol(), SchemeNoticeReader.Kind.demerger, s.getExDate()));
            } else if (s.getKind() == SuggestionKind.merger) {
                asks.add(new Ask(s.getId(), s.getInstrument().getId(), s.getInstrument().getSymbol(), SchemeNoticeReader.Kind.merger, null));
            }
        }
        return lookup(asks);
    }

    /** Details per instrument id for held stocks that no longer trade (a merger reading each). */
    public Map<UUID, CorporateActionSchemeDetails> forStoppedStocks(Map<UUID, String> symbolByInstrumentId) {
        List<Ask> asks = new ArrayList<>();
        symbolByInstrumentId.forEach((id, symbol) -> asks.add(new Ask(id, id, symbol, SchemeNoticeReader.Kind.merger, null)));
        return lookup(asks);
    }

    public Map<UUID, CorporateActionSchemeDetails> lookup(List<Ask> asks) {
        Map<UUID, CorporateActionSchemeDetails> out = new HashMap<>();
        Map<SchemeNoticeReader.Kind, Set<String>> symbols = new HashMap<>();
        for (Ask a : asks) {
            if (a.symbol() != null && !a.symbol().isBlank()) {
                symbols.computeIfAbsent(a.kind(), k -> new HashSet<>()).add(a.symbol().trim().toUpperCase(Locale.ROOT));
            }
        }
        List<SchemeEvidence> rows = new ArrayList<>();
        symbols.forEach((kind, set) -> rows.addAll(evidence.findByKindAndSymbols(kind.name(), set)));
        if (rows.isEmpty()) {
            return out;
        }
        Map<UUID, List<SchemeEvidenceChild>> kids = children.findForEvidence(rows.stream().map(SchemeEvidence::getId).toList())
                .stream().collect(Collectors.groupingBy(SchemeEvidenceChild::getEvidenceId));
        Map<String, UUID> instrumentIds = instrumentIds(kids.values().stream().flatMap(List::stream).toList());
        Map<String, SchemeCheck> mine = new HashMap<>();
        UUID userId = com.financeos.core.security.UserContext.getCurrentUserId();
        if (userId != null) {
            for (SchemeCheck c : checks.findOwned(userId)) {
                mine.put(c.getInstrument().getId() + "|" + c.getKind() + "|" + c.getExDate(), c);
            }
        }
        for (Ask a : asks) {
            if (a.symbol() == null) {
                continue;
            }
            String symbol = a.symbol().trim().toUpperCase(Locale.ROOT);
            for (SchemeEvidence e : rows) {
                boolean same = e.getParentSymbol().equals(symbol) && e.getKind().equals(a.kind().name())
                        && (a.exDate() == null ? e.getExDate() == null : a.exDate().equals(e.getExDate()));
                if (same) {
                    out.put(a.id(), details(e, kids.getOrDefault(e.getId(), List.of()), instrumentIds,
                            holdingsCheck(mine.get(a.instrumentId() + "|" + a.kind().name() + "|" + a.exDate()))));
                    break;
                }
            }
        }
        return out;
    }

    private static CorporateActionSchemeDetails details(SchemeEvidence e, List<SchemeEvidenceChild> kids, Map<String, UUID> ids,
                                                        List<CorporateActionSchemeHoldingsCheck> holdingsCheck) {
        List<CorporateActionSchemeChild> children = new ArrayList<>();
        for (SchemeEvidenceChild c : kids) {
            UUID instrumentId = c.getChildIsin() == null ? null : ids.get("I:" + c.getChildIsin());
            if (instrumentId == null) {
                instrumentId = ids.get("S:" + c.getChildSymbol().toUpperCase(Locale.ROOT));
            }
            CorporateActionSchemeSource ratioSource = c.getRatioUrl() == null ? null
                    : new CorporateActionSchemeSource(c.getRatioUrl(), c.getRatioNoticeDate(), c.getRatioQuote() == null ? "" : c.getRatioQuote());
            children.add(new CorporateActionSchemeChild(c.getChildSymbol(), c.getChildName(), c.getChildIsin(), c.getListedOn(),
                    c.getChildSymbol() + ".NS", instrumentId, c.getRatioFrom(), c.getRatioTo(), ratioSource, c.getChildCostPct()));
        }
        CorporateActionSchemeSource costSource = e.getCostUrl() == null ? null
                : new CorporateActionSchemeSource(e.getCostUrl(), e.getCostNoticeDate(), e.getCostQuote() == null ? "" : e.getCostQuote());
        List<String> missing = e.getMissing() == null || e.getMissing().isBlank() ? List.of()
                : Arrays.stream(e.getMissing().split(",")).map(String::trim).filter(s -> !s.isEmpty()).toList();
        return new CorporateActionSchemeDetails(children, e.getParentCostPct(), e.getCostKind(), costSource, missing, e.getCheckedAt(), holdingsCheck);
    }

    /** The stored per-account check as the API shows it; empty when none was made or it cannot be read. */
    private List<CorporateActionSchemeHoldingsCheck> holdingsCheck(@Nullable SchemeCheck check) {
        if (check == null || check.getChecksJson() == null || check.getChecksJson().isBlank()) {
            return List.of();
        }
        try {
            return mapper.readValue(check.getChecksJson(), new com.fasterxml.jackson.core.type.TypeReference<List<CorporateActionSchemeHoldingsCheck>>() { });
        } catch (Exception e) {
            return List.of();
        }
    }

    /** Catalog instrument id by "I:isin" and "S:SYMBOL"; an NSE row wins over another exchange's. */
    private Map<String, UUID> instrumentIds(List<SchemeEvidenceChild> kids) {
        Set<String> isins = new HashSet<>();
        Set<String> symbols = new HashSet<>();
        for (SchemeEvidenceChild c : kids) {
            if (c.getChildIsin() != null) {
                isins.add(c.getChildIsin());
            }
            symbols.add(c.getChildSymbol().toUpperCase(Locale.ROOT));
        }
        Map<String, UUID> out = new HashMap<>();
        if (symbols.isEmpty()) {
            return out;
        }
        Set<String> isinOnNse = new HashSet<>();
        List<Instrument> rows = new ArrayList<>();
        if (!isins.isEmpty()) {
            rows.addAll(instruments.findByIsinIn(isins));
        }
        rows.addAll(instruments.findByNseSymbolIn(symbols));
        for (Instrument i : rows) {
            boolean nse = NSE.equalsIgnoreCase(i.getExchange());
            if (i.getIsin() != null && (!out.containsKey("I:" + i.getIsin()) || (nse && !isinOnNse.contains(i.getIsin())))) {
                out.put("I:" + i.getIsin(), i.getId());
                if (nse) {
                    isinOnNse.add(i.getIsin());
                }
            }
            if (i.getSymbol() != null && nse) {
                out.putIfAbsent("S:" + i.getSymbol().toUpperCase(Locale.ROOT), i.getId());
            }
        }
        return out;
    }
}
