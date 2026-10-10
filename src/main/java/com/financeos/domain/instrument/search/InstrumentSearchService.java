package com.financeos.domain.instrument.search;

import com.financeos.api.instrument.dto.InstrumentCandidate;
import com.financeos.api.instrument.dto.InstrumentResponse;
import com.financeos.api.instrument.dto.ResolveInstrumentRequest;
import com.financeos.core.security.UserContext;
import com.financeos.domain.instrument.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;

@Service
@Transactional
public class InstrumentSearchService {

    private final InstrumentRepository instrumentRepository;
    private final InstrumentPriceRepository priceRepository;
    private final InstrumentAliasRepository aliasRepository;
    private final List<InstrumentSearchProvider> searchProviders;
    private final InstrumentClassificationService classificationService;
    /** Per-user instrument overrides; null in unit tests (then nobody has one). */
    @Nullable
    private final AssetClassOverrideService overrideService;

    /**
     * How a resolve may touch an instrument already in the shared catalog. {@link #TRUSTED} — the
     * import path, whose data comes from the server's own Yahoo / AMFI search — may fill empty
     * identifiers and refresh an ISIN match (renames). {@link #CREATE_ONLY} — anything a user sends —
     * reuses a matching row as it is and only ever adds new rows, so no user changes what others see.
     */
    public enum CatalogWrite { TRUSTED, CREATE_ONLY }

    /** Without per-user overrides (unit tests). */
    public InstrumentSearchService(InstrumentRepository instrumentRepository,
                                   InstrumentPriceRepository priceRepository,
                                   InstrumentAliasRepository aliasRepository,
                                   List<InstrumentSearchProvider> searchProviders,
                                   InstrumentClassificationService classificationService) {
        this(instrumentRepository, priceRepository, aliasRepository, searchProviders, classificationService, null);
    }

    @Autowired
    public InstrumentSearchService(InstrumentRepository instrumentRepository,
                                   InstrumentPriceRepository priceRepository,
                                   InstrumentAliasRepository aliasRepository,
                                   List<InstrumentSearchProvider> searchProviders,
                                   InstrumentClassificationService classificationService,
                                   @Nullable AssetClassOverrideService overrideService) {
        this.instrumentRepository = instrumentRepository;
        this.priceRepository = priceRepository;
        this.aliasRepository = aliasRepository;
        this.searchProviders = searchProviders;
        this.classificationService = classificationService;
        this.overrideService = overrideService;
    }

    /** The current user's instrument overrides ({@link InstrumentOverrides#NONE} without a user or the service). */
    @Transactional(readOnly = true)
    public InstrumentOverrides currentUserOverrides() {
        UUID userId = UserContext.getCurrentUserId();
        return overrideService == null || userId == null
                ? InstrumentOverrides.NONE : InstrumentOverrides.orNone(overrideService.overridesFor(userId));
    }

    private Optional<InstrumentPrice> latestPrice(UUID instrumentId, @Nullable UUID userId) {
        return PricePrecedence.preferred(priceRepository.findLatestVisible(instrumentId, userId));
    }

    /** Local rows the catalog picker shows at most (external results fill up to 25 in all). */
    static final int LOCAL_LIMIT = 25;

    @Transactional(readOnly = true)
    public List<InstrumentCandidate> catalogSearch(String q, InstrumentType type) {
        if (q == null || q.trim().length() < 2) {
            return List.of();
        }

        String search = q.trim();
        List<InstrumentCandidate> candidates = new ArrayList<>();

        // 1. Local first, as the current user sees each instrument: the type filter and the text match
        // apply to their view (renamed / retyped instruments included), prices in one batched query.
        UUID userId = UserContext.getCurrentUserId();
        InstrumentOverrides overrides = currentUserOverrides();
        List<Instrument> localInstruments = InstrumentLocalSearch.find(instrumentRepository, search, type, overrides,
                0, LOCAL_LIMIT);
        Map<UUID, InstrumentPrice> prices = new HashMap<>();
        for (List<UUID> chunk : InstrumentLocalSearch.chunks(localInstruments.stream().map(Instrument::getId).toList())) {
            prices.putAll(PricePrecedence.byInstrument(priceRepository.findLatestByInstrumentIds(chunk, userId)));
        }
        for (Instrument inst : localInstruments) {
            InstrumentPrice latest = prices.get(inst.getId());
            InstrumentCandidate.PricePreview pricePreview = latest == null ? null
                    : new InstrumentCandidate.PricePreview(latest.getClose(), latest.getAsOf());

            candidates.add(new InstrumentCandidate(
                    "LOCAL",
                    overrides.type(inst),
                    overrides.name(inst),
                    overrides.symbol(inst),
                    overrides.exchange(inst),
                    inst.getIsin(),
                    inst.getAmfiCode(),
                    inst.getYahooSymbol(),
                    overrides.currency(inst),
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

    /**
     * The import path's resolve ({@link CatalogWrite#TRUSTED}): the matching or new catalog row, with
     * the shared catalog values and the feed price (callers re-read the instrument by id). TRUSTED may
     * fill an existing row's empty identifiers, so {@code req} must carry only what a search provider
     * returned (never a value from a user's file) and no {@code existingInstrumentId} chosen by a user.
     */
    public InstrumentResponse resolve(ResolveInstrumentRequest req) {
        Instrument inst = resolveInstrument(req, CatalogWrite.TRUSTED);
        return InstrumentResponse.from(inst, latestPrice(inst.getId(), UserContext.getCurrentUserId()));
    }

    /**
     * The catalog instrument {@code req} identifies: the picked row, else the first match by ISIN,
     * AMFI code, Yahoo symbol, then symbol + exchange, else a new row. {@code mode} says whether an
     * existing row may be updated from the request ({@link CatalogWrite}).
     */
    public Instrument resolveInstrument(ResolveInstrumentRequest req, CatalogWrite mode) {
        // Fast path: the user picked an instrument already in their catalog. Reuse that exact row.
        // (Falls through to key-based dedup/create if the id is stale/missing.)
        if (req.existingInstrumentId() != null) {
            Optional<Instrument> byId = instrumentRepository.findById(req.existingInstrumentId());
            if (byId.isPresent()) {
                Instrument inst = byId.get();
                // A derived class (AMFI header / name rules), not user data: fine to store for everyone.
                if (inst.getAssetClass() == null && classificationService.classify(inst)) {
                    inst = instrumentRepository.save(inst);
                }
                return inst;
            }
        }

        boolean matchedByIsin = false;
        Optional<Instrument> existing = Optional.empty();

        if (!isEmpty(req.isin())) {
            existing = instrumentRepository.findByIsin(req.isin().trim());
            if (existing.isPresent()) {
                matchedByIsin = true;
            }
        }
        if (existing.isEmpty()) {
            existing = findByFeedIdentifiers(null, req.amfiCode(), req.yahooSymbol());
        }
        if (existing.isEmpty() && !isEmpty(req.symbol()) && !isEmpty(req.exchange())) {
            existing = instrumentRepository.findBySymbolAndExchange(req.symbol().trim(), req.exchange().trim());
        }

        if (existing.isPresent() && mode == CatalogWrite.CREATE_ONLY) {
            Instrument inst = existing.get();
            if (inst.getAssetClass() == null && classificationService.classify(inst)) {
                inst = instrumentRepository.save(inst);
            }
            return inst;
        }

        if (existing.isPresent()) {
            Instrument inst = existing.get();
            boolean updated = false;

            if (matchedByIsin) {
                // ISIN-authoritative refresh: update symbol, exchange, yahooSymbol, name even if already populated
                if (!isEmpty(req.symbol()) && !req.symbol().trim().equalsIgnoreCase(inst.getSymbol())) {
                    if (!isEmpty(inst.getSymbol())) {
                        aliasRepository.save(new InstrumentAlias(inst, inst.getSymbol(), inst.getName(), "IMPORT_RESOLVE"));
                    }
                    inst.setSymbol(req.symbol().trim());
                    updated = true;
                }
                if (!isEmpty(req.name()) && !req.name().trim().equalsIgnoreCase(inst.getName())) {
                    inst.setName(req.name().trim());
                    updated = true;
                }
                if (!isEmpty(req.exchange()) && !req.exchange().trim().equalsIgnoreCase(inst.getExchange())) {
                    inst.setExchange(req.exchange().trim());
                    updated = true;
                }
                if (!isEmpty(req.yahooSymbol()) && !req.yahooSymbol().trim().equalsIgnoreCase(inst.getYahooSymbol())) {
                    inst.setYahooSymbol(req.yahooSymbol().trim());
                    updated = true;
                }
                if (!isEmpty(req.amfiCode()) && !req.amfiCode().trim().equalsIgnoreCase(inst.getAmfiCode())) {
                    inst.setAmfiCode(req.amfiCode().trim());
                    updated = true;
                }
            } else {
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
            }

            // Codes or the name may have just changed; MANUAL classifications stay as they are.
            if (classificationService.classify(inst)) {
                updated = true;
            }
            if (updated) {
                inst = instrumentRepository.save(inst);
            }
            return inst;
        }

        return createFromRequest(req);
    }

    /**
     * The catalog instrument with one of these identifiers, checked in order of authority: ISIN, then
     * AMFI code, then Yahoo symbol (blank ones are skipped).
     */
    public Optional<Instrument> findByFeedIdentifiers(@Nullable String isin, @Nullable String amfiCode,
                                                      @Nullable String yahooSymbol) {
        Optional<Instrument> found = Optional.empty();
        if (!isEmpty(isin)) {
            found = orEmpty(instrumentRepository.findByIsin(isin.trim()));
        }
        if (found.isEmpty() && !isEmpty(amfiCode)) {
            found = orEmpty(instrumentRepository.findByAmfiCode(amfiCode.trim()));
        }
        if (found.isEmpty() && !isEmpty(yahooSymbol)) {
            found = orEmpty(instrumentRepository.findByYahooSymbol(yahooSymbol.trim()));
        }
        return found;
    }

    /**
     * Adds the request's instrument to the catalog as a new row (classified) — unless a row with its
     * ISIN, AMFI code or Yahoo symbol (case-insensitive) exists: that one is returned unchanged, so no
     * path mints a duplicate.
     */
    public Instrument createFromRequest(ResolveInstrumentRequest req) {
        Optional<Instrument> existing = findByFeedIdentifiers(req.isin(), req.amfiCode(), req.yahooSymbol());
        if (existing.isPresent()) {
            return existing.get();
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
        classificationService.classify(newInst);

        return instrumentRepository.save(newInst);
    }


    /** Null-safe for unstubbed mocks. */
    private static Optional<Instrument> orEmpty(@Nullable Optional<Instrument> found) {
        return found != null ? found : Optional.empty();
    }

    private boolean isEmpty(String str) {
        return str == null || str.isBlank();
    }
}
