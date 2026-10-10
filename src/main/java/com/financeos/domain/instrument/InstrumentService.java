package com.financeos.domain.instrument;

import com.financeos.api.instrument.dto.InstrumentListPage;
import com.financeos.api.instrument.dto.InstrumentPriceResponse;
import com.financeos.api.instrument.dto.InstrumentRequest;
import com.financeos.api.instrument.dto.InstrumentResponse;
import com.financeos.api.instrument.dto.UpsertPriceRequest;
import com.financeos.core.exception.ResourceNotFoundException;
import com.financeos.core.exception.ValidationException;
import com.financeos.core.security.UserContext;
import com.financeos.core.time.AppTime;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * The instrument API. The instrument master, its feed prices and its catalog aliases are shared by
 * every user, so nothing a user does here changes what another user sees: display edits (name,
 * symbol, exchange, currency, type) and the asset class are the user's own overrides; an identifier
 * edit (ISIN, AMFI code, Yahoo symbol — what picks the price feed) moves only that user's holdings to
 * the catalog instrument with those identifiers; manual prices belong to the user who entered them.
 */
@Service
@Transactional
public class InstrumentService {

    private final InstrumentRepository instrumentRepository;
    private final InstrumentPriceRepository priceRepository;
    private final InstrumentClassificationService classificationService;
    private final AssetClassOverrideService overrideService;
    /** Moves a user's references to another instrument; null in unit tests that never repoint. */
    @Nullable
    private final InstrumentRepointService repointService;

    /** Without identifier repointing (unit tests); the alias repository and publisher are unused. */
    public InstrumentService(InstrumentRepository instrumentRepository,
                             InstrumentPriceRepository priceRepository,
                             InstrumentAliasRepository aliasRepository,
                             ApplicationEventPublisher eventPublisher,
                             InstrumentClassificationService classificationService,
                             AssetClassOverrideService overrideService) {
        this(instrumentRepository, priceRepository, classificationService, overrideService, null);
    }

    @Autowired
    public InstrumentService(InstrumentRepository instrumentRepository,
                             InstrumentPriceRepository priceRepository,
                             InstrumentClassificationService classificationService,
                             AssetClassOverrideService overrideService,
                             @Nullable InstrumentRepointService repointService) {
        this.instrumentRepository = instrumentRepository;
        this.priceRepository = priceRepository;
        this.classificationService = classificationService;
        this.overrideService = overrideService;
        this.repointService = repointService;
    }

    // ------------------------------------------------------------------ reads

    private InstrumentOverrides overrides(@Nullable UUID userId) {
        return userId == null ? InstrumentOverrides.NONE : InstrumentOverrides.orNone(overrideService.overridesFor(userId));
    }

    private Optional<InstrumentPrice> latestPrice(UUID instrumentId, @Nullable UUID userId) {
        return PricePrecedence.preferred(priceRepository.findLatestVisible(instrumentId, userId));
    }

    /** {@code instrument} as {@code userId} sees it: their overrides and the latest price they see. */
    private InstrumentResponse view(Instrument instrument, @Nullable UUID userId, InstrumentOverrides overrides) {
        return InstrumentResponse.from(instrument, latestPrice(instrument.getId(), userId), overrides);
    }

    private InstrumentResponse view(Instrument instrument, @Nullable UUID userId) {
        return view(instrument, userId, overrides(userId));
    }

    private Instrument find(UUID id) {
        return instrumentRepository.findById(id).orElseThrow(() -> new ResourceNotFoundException("Instrument", id));
    }

    /** Default and largest page of {@link #listInstruments}. */
    public static final int DEFAULT_PAGE_SIZE = 50;
    public static final int MAX_PAGE_SIZE = 200;
    /** The sort keys of {@link #listInstruments}; {@code name} is the name the user sees. */
    public static final List<String> SORT_KEYS = List.of("name");

    /**
     * One page of the catalog instruments as the current user sees them: matching {@code search} (the
     * name and symbol they see, the catalog name and symbol, ISIN, AMFI code, Yahoo symbol; blank = all)
     * and {@code type} (the type they see; null = any), sorted by {@code sort} ({@code <key>[,asc|desc]},
     * key one of {@link #SORT_KEYS}; default {@code name,asc}), with the total over all pages. Each item
     * carries the latest price they see (one batched price query). The database filters, sorts, counts
     * and pages on the user's own overrides (a left join on their override row), so a renamed or retyped
     * instrument lands on the page, and in the count, where they see it.
     */
    @Transactional(readOnly = true)
    public InstrumentListPage listInstruments(@Nullable String search, @Nullable InstrumentType type,
                                              @Nullable String sort, int page, int size) {
        UUID userId = UserContext.getCurrentUserId();
        String dir = sortDirection(sort);
        int safePage = Math.max(0, page);
        int safeSize = Math.min(MAX_PAGE_SIZE, Math.max(1, size));
        String needle = search == null || search.isBlank() ? null : search.trim().toLowerCase(java.util.Locale.ROOT);
        org.springframework.data.domain.Page<Instrument> found = instrumentRepository.listAsSeenBy(userId, needle, type,
                dir, org.springframework.data.domain.PageRequest.of(safePage, safeSize));
        InstrumentOverrides overrides = found.isEmpty() ? InstrumentOverrides.NONE : overrides(userId);
        Map<UUID, InstrumentPrice> prices = latestPrices(found.getContent(), userId);
        List<InstrumentResponse> items = new ArrayList<>(found.getNumberOfElements());
        for (Instrument inst : found.getContent()) {
            items.add(InstrumentResponse.from(inst, Optional.ofNullable(prices.get(inst.getId())), overrides));
        }
        return new InstrumentListPage(items, safePage, safeSize, found.getTotalElements(), found.getTotalPages());
    }

    /** {@code "asc"} or {@code "desc"} from a {@code <key>[,asc|desc]} sort; 400 for an unknown key or direction. */
    static String sortDirection(@Nullable String sort) {
        if (sort == null || sort.isBlank()) {
            return "asc";
        }
        String[] parts = sort.split(",", -1);
        String key = parts[0].trim();
        if (!SORT_KEYS.contains(key) || parts.length > 2) {
            throw new ValidationException("Unsupported instrument sort: " + sort + " (use name, name,asc or name,desc)");
        }
        String dir = parts.length == 2 ? parts[1].trim().toLowerCase(java.util.Locale.ROOT) : "asc";
        if (!dir.equals("asc") && !dir.equals("desc")) {
            throw new ValidationException("Unsupported sort direction: " + parts[1].trim() + " (use asc or desc)");
        }
        return dir;
    }

    /** The latest price {@code userId} sees for each instrument, in batched queries (≤ 900 ids each). */
    private Map<UUID, InstrumentPrice> latestPrices(List<Instrument> instruments, @Nullable UUID userId) {
        Map<UUID, InstrumentPrice> out = new LinkedHashMap<>();
        List<UUID> ids = instruments.stream().map(Instrument::getId).toList();
        for (List<UUID> chunk : InstrumentLocalSearch.chunks(ids)) {
            List<InstrumentPrice> rows = priceRepository.findLatestByInstrumentIds(chunk, userId);
            out.putAll(PricePrecedence.byInstrument(rows));
        }
        return out;
    }

    @Transactional(readOnly = true)
    public InstrumentResponse getInstrumentById(UUID id) {
        return view(find(id), UserContext.getCurrentUserId());
    }

    // ------------------------------------------------------------------ catalog rows

    /**
     * Adds an instrument to the shared catalog — or, when one with the ISIN, AMFI code or Yahoo symbol
     * (case-insensitive) already exists, returns that one unchanged (a create never edits an existing
     * row and never mints a duplicate of one).
     */
    public InstrumentResponse createInstrument(InstrumentRequest request) {
        UUID userId = UserContext.getCurrentUserId();
        Optional<Instrument> existing = findByAnyIdentifier(request.isin(), request.amfiCode(), request.yahooSymbol());
        if (existing.isPresent()) {
            return view(existing.get(), userId);
        }

        Instrument instrument = new Instrument();
        instrument.setType(request.type());
        instrument.setName(request.name().trim());
        instrument.setSymbol(blankToNull(request.symbol()));
        instrument.setExchange(blankToNull(request.exchange()));
        instrument.setIsin(blankToNull(request.isin()));
        instrument.setAmfiCode(blankToNull(request.amfiCode()));
        instrument.setYahooSymbol(blankToNull(request.yahooSymbol()));
        if (request.currency() != null && !request.currency().isBlank()) {
            instrument.setCurrency(request.currency().trim());
        }
        classificationService.classify(instrument);

        Instrument saved = instrumentRepository.save(instrument);
        return InstrumentResponse.from(saved, Optional.empty(), overrides(userId));
    }

    /**
     * Edits the instrument for the current user's account only.
     * <ul>
     *   <li>Identifiers (ISIN, AMFI code, Yahoo symbol) choose the price feed, so a change never touches
     *   the shared row: the catalog instrument with the new identifiers is found (or added) and only
     *   this user's holdings, trades, dividends, SIPs, manual prices, aliases and own corporate actions
     *   move to it — merged into their existing holding of it at the same broker. The answer is that
     *   instrument (a new id).</li>
     *   <li>Display fields (name, symbol, exchange, currency, type) become the user's overrides wherever
     *   they differ from the catalog; one equal to the catalog (or a blank symbol / exchange / currency)
     *   clears that override.</li>
     * </ul>
     */
    public InstrumentResponse updateInstrument(UUID id, InstrumentRequest request) {
        UUID userId = requireUser("Editing an instrument needs a signed-in user");
        Instrument instrument = find(id);

        if (!identifiersChanged(instrument, request)) {
            overrideService.setDisplay(userId, instrument, request.name(), request.symbol(), request.exchange(),
                    request.currency(), request.type());
            return view(instrument, userId);
        }
        if (blankToNull(request.isin()) == null && blankToNull(request.amfiCode()) == null
                && blankToNull(request.yahooSymbol()) == null) {
            throw new ValidationException(
                    "Give an ISIN, AMFI code or Yahoo symbol to switch the price feed; they can't all be cleared");
        }
        if (repointService == null) {
            throw new IllegalStateException("Identifier edits need the repoint service");
        }
        // Only what the user changed relative to how they saw the source follows them to the target;
        // values they left as they were stay the target's own (no carried overrides, no pinned type).
        DisplayChanges changes = DisplayChanges.between(overrides(userId), instrument, request);
        InstrumentRepointService.Result result = repointService.repoint(userId, instrument, request, changes);
        Instrument target = result.target();
        overrideService.setDisplay(userId, target, request.name(), request.symbol(), request.exchange(),
                request.currency(), request.type(), changes);
        return view(target, userId).withMerge(result.merged(), result.mergeChangedFigures()
                ? "The two holdings' trades are now one history, so realised gains and the lots still open "
                        + "differ from before."
                : null);
    }

    /** The catalog instrument with the ISIN, else the AMFI code, else the Yahoo symbol (blank ones skipped). */
    private Optional<Instrument> findByAnyIdentifier(@Nullable String isin, @Nullable String amfiCode,
                                                     @Nullable String yahooSymbol) {
        Optional<Instrument> found = Optional.empty();
        if (blankToNull(isin) != null) {
            found = instrumentRepository.findByIsin(isin.trim());
        }
        if (found.isEmpty() && blankToNull(amfiCode) != null) {
            found = instrumentRepository.findByAmfiCode(amfiCode.trim());
        }
        if (found.isEmpty() && blankToNull(yahooSymbol) != null) {
            found = instrumentRepository.findByYahooSymbol(yahooSymbol.trim());
        }
        return found == null ? Optional.empty() : found;
    }

    /** Whether the request's ISIN / AMFI code / Yahoo symbol differ from the instrument's (case-insensitive). */
    static boolean identifiersChanged(Instrument instrument, InstrumentRequest request) {
        return !sameIdentifier(instrument.getIsin(), request.isin())
                || !sameIdentifier(instrument.getAmfiCode(), request.amfiCode())
                || !sameIdentifier(instrument.getYahooSymbol(), request.yahooSymbol());
    }

    private static boolean sameIdentifier(@Nullable String current, @Nullable String requested) {
        String a = blankToNull(current);
        String b = blankToNull(requested);
        return a == null ? b == null : b != null && a.equalsIgnoreCase(b);
    }

    @Nullable
    private static String blankToNull(@Nullable String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private static UUID requireUser(String message) {
        UUID userId = UserContext.getCurrentUserId();
        if (userId == null) {
            throw new ValidationException(message);
        }
        return userId;
    }

    // ------------------------------------------------------------------ per-user overrides

    /**
     * Pins the current user's own asset class for the instrument (a {@link UserInstrumentOverride};
     * the shared instrument row is not touched, so no other user is affected), or with a null
     * {@code assetClass} removes that override so the global AMFI / rule classification applies again.
     */
    public InstrumentResponse updateAssetClass(UUID id, AssetClass assetClass) {
        Instrument instrument = find(id);
        UUID userId = UserContext.getCurrentUserId();
        if (userId == null) {
            throw new ValidationException("An asset-class override needs a signed-in user");
        }
        overrideService.set(userId, id, assetClass);
        // The fresh class through the one classification path (InstrumentOverrides → AssetClassifier).
        InstrumentOverrides mine = overrides(userId).withAssetClass(id, assetClass);
        return view(instrument, userId, mine);
    }

    /** Removes every override the current user has on the instrument (display fields and asset class). */
    public InstrumentResponse resetOverrides(UUID id) {
        Instrument instrument = find(id);
        UUID userId = requireUser("Resetting an instrument needs a signed-in user");
        overrideService.clear(userId, id);
        return view(instrument, userId, InstrumentOverrides.NONE);
    }

    // ------------------------------------------------------------------ manual prices (the user's own)

    /** Sets the current user's own MANUAL price for a date (default today); only they see it. */
    public InstrumentResponse upsertPrice(UUID id, UpsertPriceRequest request) {
        UUID userId = requireUser("A manual price needs a signed-in user");
        Instrument instrument = find(id);
        if (request.price() == null || request.price().signum() < 0) {
            throw new ValidationException("Price must be non-negative");
        }

        LocalDate asOf = request.asOf() != null ? request.asOf() : AppTime.today();
        InstrumentPrice price = priceRepository.findByInstrumentIdAndAsOfAndUserId(id, asOf, userId)
                .orElseGet(() -> InstrumentPrice.manual(instrument, userId, asOf, request.price()));
        price.setClose(request.price());
        priceRepository.save(price);

        return view(instrument, userId);
    }

    /** One of the current user's own MANUAL prices, else 404 (feed and other users' prices included). */
    private InstrumentPrice ownManualPrice(UUID instrumentId, UUID priceId, UUID userId) {
        InstrumentPrice price = priceRepository.findById(priceId)
                .orElseThrow(() -> new ResourceNotFoundException("InstrumentPrice", priceId));
        if (price.getInstrument() == null || !Objects.equals(price.getInstrument().getId(), instrumentId)
                || !price.isOwnManual(userId)) {
            throw new ResourceNotFoundException("InstrumentPrice", priceId);
        }
        return price;
    }

    public InstrumentResponse updateManualPrice(UUID instrumentId, UUID priceId, BigDecimal newClose) {
        if (newClose == null || newClose.compareTo(BigDecimal.ZERO) < 0) {
            throw new ValidationException("Price must be non-negative");
        }
        UUID userId = requireUser("Editing a price needs a signed-in user");
        InstrumentPrice price = ownManualPrice(instrumentId, priceId, userId);
        price.setClose(newClose);
        priceRepository.save(price);
        // Positions read the latest visible price dynamically, so nothing else needs recomputing.
        return view(price.getInstrument(), userId);
    }

    public void deleteManualPrice(UUID instrumentId, UUID priceId) {
        UUID userId = requireUser("Deleting a price needs a signed-in user");
        priceRepository.delete(ownManualPrice(instrumentId, priceId, userId));
    }

    /**
     * The prices the current user sees, newest first: feed prices plus their own MANUAL prices (theirs
     * in place of the feed's on a date both have).
     */
    @Transactional(readOnly = true)
    public List<InstrumentPriceResponse> getPriceHistory(UUID instrumentId, LocalDate from, LocalDate to) {
        if (!instrumentRepository.existsById(instrumentId)) {
            throw new ResourceNotFoundException("Instrument", instrumentId);
        }
        UUID userId = UserContext.getCurrentUserId();
        return PricePrecedence.collapse(priceRepository.findPriceHistory(instrumentId, userId, from, to)).stream()
                .map(p -> InstrumentPriceResponse.from(p, userId))
                .toList();
    }
}
