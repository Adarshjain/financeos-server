package com.financeos.domain.instrument.search;

import com.financeos.api.instrument.dto.InstrumentCandidate;
import com.financeos.api.instrument.dto.InstrumentResponse;
import com.financeos.api.instrument.dto.ResolveInstrumentRequest;
import com.financeos.domain.instrument.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;

@Service
@Transactional
public class InstrumentSearchService {

    private final InstrumentRepository instrumentRepository;
    private final InstrumentPriceRepository priceRepository;
    private final List<InstrumentSearchProvider> searchProviders;

    public InstrumentSearchService(InstrumentRepository instrumentRepository,
                                   InstrumentPriceRepository priceRepository,
                                   List<InstrumentSearchProvider> searchProviders) {
        this.instrumentRepository = instrumentRepository;
        this.priceRepository = priceRepository;
        this.searchProviders = searchProviders;
    }

    @Transactional(readOnly = true)
    public List<InstrumentCandidate> catalogSearch(String q, InstrumentType type) {
        if (q == null || q.trim().length() < 2) {
            return List.of();
        }

        String search = q.trim();
        List<InstrumentCandidate> candidates = new ArrayList<>();

        // 1. Local first
        List<Instrument> localInstruments = instrumentRepository.searchInstruments(search, type);
        for (Instrument inst : localInstruments) {
            Optional<InstrumentPrice> latestPrice = priceRepository.findTopByInstrumentIdOrderByAsOfDesc(inst.getId());
            InstrumentCandidate.PricePreview pricePreview = latestPrice
                    .map(p -> new InstrumentCandidate.PricePreview(p.getClose(), p.getAsOf()))
                    .orElse(null);

            candidates.add(new InstrumentCandidate(
                    "LOCAL",
                    inst.getType(),
                    inst.getName(),
                    inst.getSymbol(),
                    inst.getExchange(),
                    inst.getIsin(),
                    inst.getAmfiCode(),
                    inst.getYahooSymbol(),
                    inst.getCurrency(),
                    pricePreview,
                    inst.getId()
            ));
        }

        // 2. External search
        for (InstrumentSearchProvider provider : searchProviders) {
            if (provider.supports(type)) {
                try {
                    List<InstrumentCandidate> externalCandidates = provider.search(search, type);
                    for (InstrumentCandidate ext : externalCandidates) {
                        if (!isDuplicate(ext, candidates)) {
                            candidates.add(ext);
                        }
                    }
                } catch (Exception e) {
                    // Fail soft
                }
            }
        }

        // 3. Cap total (~25)
        if (candidates.size() > 25) {
            return candidates.subList(0, 25);
        }
        return candidates;
    }

    private boolean isDuplicate(InstrumentCandidate candidate, List<InstrumentCandidate> existingList) {
        for (InstrumentCandidate existing : existingList) {
            if (matches(candidate, existing)) {
                return true;
            }
        }
        return false;
    }

    private boolean matches(InstrumentCandidate a, InstrumentCandidate b) {
        if (a.isin() != null && !a.isin().isBlank() && b.isin() != null && !b.isin().isBlank()) {
            if (a.isin().trim().equalsIgnoreCase(b.isin().trim())) {
                return true;
            }
        }
        if (a.amfiCode() != null && !a.amfiCode().isBlank() && b.amfiCode() != null && !b.amfiCode().isBlank()) {
            if (a.amfiCode().trim().equalsIgnoreCase(b.amfiCode().trim())) {
                return true;
            }
        }
        if (a.yahooSymbol() != null && !a.yahooSymbol().isBlank() && b.yahooSymbol() != null && !b.yahooSymbol().isBlank()) {
            if (a.yahooSymbol().trim().equalsIgnoreCase(b.yahooSymbol().trim())) {
                return true;
            }
        }
        if (a.symbol() != null && !a.symbol().isBlank() && a.exchange() != null && !a.exchange().isBlank()
                && b.symbol() != null && !b.symbol().isBlank() && b.exchange() != null && !b.exchange().isBlank()) {
            if (a.symbol().trim().equalsIgnoreCase(b.symbol().trim())
                    && a.exchange().trim().equalsIgnoreCase(b.exchange().trim())) {
                return true;
            }
        }
        return false;
    }

    public InstrumentResponse resolve(ResolveInstrumentRequest req) {
        // Fast path: the user picked an instrument already in their catalog. Reuse that exact row.
        // (Falls through to key-based dedup/create if the id is stale/missing.)
        if (req.existingInstrumentId() != null) {
            Optional<Instrument> byId = instrumentRepository.findById(req.existingInstrumentId());
            if (byId.isPresent()) {
                Instrument inst = byId.get();
                Optional<InstrumentPrice> latestPrice = priceRepository.findTopByInstrumentIdOrderByAsOfDesc(inst.getId());
                return InstrumentResponse.from(inst, latestPrice);
            }
        }

        Optional<Instrument> existing = findExistingInstrument(req);

        if (existing.isPresent()) {
            Instrument inst = existing.get();
            boolean updated = false;

            if (isEmpty(inst.getAmfiCode()) && !isEmpty(req.amfiCode())) {
                inst.setAmfiCode(req.amfiCode().trim());
                updated = true;
            }
            if (isEmpty(inst.getYahooSymbol()) && !isEmpty(req.yahooSymbol())) {
                inst.setYahooSymbol(req.yahooSymbol().trim());
                updated = true;
            }
            if (isEmpty(inst.getIsin()) && !isEmpty(req.isin())) {
                inst.setIsin(req.isin().trim());
                updated = true;
            }
            if (isEmpty(inst.getSymbol()) && !isEmpty(req.symbol())) {
                inst.setSymbol(req.symbol().trim());
                updated = true;
            }
            if (isEmpty(inst.getExchange()) && !isEmpty(req.exchange())) {
                inst.setExchange(req.exchange().trim());
                updated = true;
            }

            if (updated) {
                inst = instrumentRepository.save(inst);
            }

            Optional<InstrumentPrice> latestPrice = priceRepository.findTopByInstrumentIdOrderByAsOfDesc(inst.getId());
            return InstrumentResponse.from(inst, latestPrice);
        }

        Instrument newInst = new Instrument();
        newInst.setType(req.type());
        newInst.setName(req.name().trim());
        newInst.setSymbol(!isEmpty(req.symbol()) ? req.symbol().trim() : null);
        newInst.setExchange(!isEmpty(req.exchange()) ? req.exchange().trim() : null);
        newInst.setIsin(!isEmpty(req.isin()) ? req.isin().trim() : null);
        newInst.setAmfiCode(!isEmpty(req.amfiCode()) ? req.amfiCode().trim() : null);
        newInst.setYahooSymbol(!isEmpty(req.yahooSymbol()) ? req.yahooSymbol().trim() : null);
        newInst.setCurrency(!isEmpty(req.currency()) ? req.currency().trim() : "INR");

        Instrument saved = instrumentRepository.save(newInst);
        return InstrumentResponse.from(saved, Optional.empty());
    }

    private Optional<Instrument> findExistingInstrument(ResolveInstrumentRequest req) {
        if (!isEmpty(req.isin())) {
            Optional<Instrument> byIsin = instrumentRepository.findByIsin(req.isin().trim());
            if (byIsin.isPresent()) {
                return byIsin;
            }
        }
        if (!isEmpty(req.amfiCode())) {
            Optional<Instrument> byAmfi = instrumentRepository.findByAmfiCode(req.amfiCode().trim());
            if (byAmfi.isPresent()) {
                return byAmfi;
            }
        }
        if (!isEmpty(req.yahooSymbol())) {
            Optional<Instrument> byYahoo = instrumentRepository.findByYahooSymbol(req.yahooSymbol().trim());
            if (byYahoo.isPresent()) {
                return byYahoo;
            }
        }
        if (!isEmpty(req.symbol()) && !isEmpty(req.exchange())) {
            Optional<Instrument> bySymEx = instrumentRepository.findBySymbolAndExchange(req.symbol().trim(), req.exchange().trim());
            if (bySymEx.isPresent()) {
                return bySymEx;
            }
        }
        return Optional.empty();
    }

    private boolean isEmpty(String str) {
        return str == null || str.isBlank();
    }
}
