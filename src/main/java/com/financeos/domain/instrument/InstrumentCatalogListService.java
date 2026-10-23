package com.financeos.domain.instrument;

import com.financeos.api.instrument.dto.InstrumentListPage;
import com.financeos.api.instrument.dto.InstrumentResponse;
import com.financeos.core.security.UserContext;
import com.financeos.core.time.AppTime;
import com.financeos.domain.investment.DayChange;
import com.financeos.domain.investment.InvestmentService;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * GET /instruments with the stale and held filters. Stale reuses the app's own price-currency rule
 * ({@link DayChange#CURRENT_WITHIN_DAYS}: a latest price more than that many days before today is not
 * current), and an instrument with no price at all is stale too. Held means the caller has open quantity
 * in some account, by the same lot engine as the positions page. The plain
 * {@link InstrumentService#listInstruments} stays for callers that need neither.
 */
@Service
@Transactional(readOnly = true)
public class InstrumentCatalogListService {

    /** Placeholder id so the held {@code IN} list is never empty. */
    private static final UUID NO_ID = new UUID(0L, 0L);

    private final InstrumentRepository instrumentRepository;
    private final InstrumentPriceRepository priceRepository;
    private final AssetClassOverrideService overrideService;
    private final InvestmentService investmentService;

    public InstrumentCatalogListService(InstrumentRepository instrumentRepository,
                                        InstrumentPriceRepository priceRepository,
                                        AssetClassOverrideService overrideService,
                                        InvestmentService investmentService) {
        this.instrumentRepository = instrumentRepository;
        this.priceRepository = priceRepository;
        this.overrideService = overrideService;
        this.investmentService = investmentService;
    }

    /** The first day a latest price still counts as current today. */
    public static LocalDate staleCutoff(LocalDate today) {
        return today.minusDays(DayChange.CURRENT_WITHIN_DAYS);
    }

    public InstrumentListPage list(@Nullable String search, @Nullable InstrumentType type, @Nullable String sort,
                                   @Nullable Boolean stale, @Nullable Boolean held, int page, int size) {
        UUID userId = UserContext.getCurrentUserId();
        String dir = InstrumentService.sortDirection(sort);
        int safePage = Math.max(0, page);
        int safeSize = Math.min(InstrumentService.MAX_PAGE_SIZE, Math.max(1, size));
        String needle = search == null || search.isBlank() ? null : search.trim().toLowerCase(Locale.ROOT);
        LocalDate today = AppTime.today();

        Set<UUID> heldIds = heldInstrumentIds(userId);
        List<UUID> heldList = heldIds.isEmpty() ? List.of(NO_ID) : List.copyOf(heldIds);
        int staleMode = stale == null ? 0 : stale ? 1 : 2;
        int heldMode = held == null ? 0 : held ? 1 : 2;

        Page<Instrument> found = instrumentRepository.listAsSeenFiltered(userId, needle, type, dir, staleMode,
                staleCutoff(today), today, heldMode, heldList, PageRequest.of(safePage, safeSize));
        InstrumentOverrides overrides = found.isEmpty() ? InstrumentOverrides.NONE
                : InstrumentOverrides.orNone(overrideService.overridesFor(userId));
        Map<UUID, InstrumentPrice> prices = latestPrices(found.getContent(), userId);
        List<InstrumentResponse> items = new ArrayList<>(found.getNumberOfElements());
        for (Instrument inst : found.getContent()) {
            items.add(InstrumentResponse.from(inst, Optional.ofNullable(prices.get(inst.getId())), overrides)
                    .withNotHeld(!heldIds.contains(inst.getId())));
        }
        return new InstrumentListPage(items, safePage, safeSize, found.getTotalElements(), found.getTotalPages());
    }

    /** The instruments {@code userId} has open quantity in, in some account (the engine's figures, one batch). */
    private Set<UUID> heldInstrumentIds(@Nullable UUID userId) {
        Set<UUID> out = new HashSet<>();
        if (userId == null) {
            return out;
        }
        for (InvestmentService.HoldingFigures f : investmentService.figuresOf(userId)) {
            if (f.quantity() != null && f.quantity().compareTo(BigDecimal.ZERO) > 0) {
                out.add(f.instrumentId());
            }
        }
        return out;
    }

    private Map<UUID, InstrumentPrice> latestPrices(List<Instrument> instruments, @Nullable UUID userId) {
        Map<UUID, InstrumentPrice> out = new LinkedHashMap<>();
        List<UUID> ids = instruments.stream().map(Instrument::getId).toList();
        for (List<UUID> chunk : InstrumentLocalSearch.chunks(ids)) {
            out.putAll(PricePrecedence.byInstrument(priceRepository.findLatestByInstrumentIds(chunk, userId)));
        }
        return out;
    }
}
