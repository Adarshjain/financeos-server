package com.financeos.domain.instrument.corporateaction;

import com.financeos.api.instrument.dto.CorporateActionPage;
import com.financeos.api.instrument.dto.CorporateActionResponse;
import com.financeos.api.instrument.dto.CorporateActionRowResponse;
import com.financeos.core.exception.ValidationException;
import com.financeos.core.security.UserContext;
import com.financeos.domain.holding.Holding;
import com.financeos.domain.holding.HoldingRepository;
import com.financeos.domain.instrument.AssetClassOverrideService;
import com.financeos.domain.instrument.InstrumentLocalSearch;
import com.financeos.domain.instrument.InstrumentOverrides;
import com.financeos.domain.investment.InvestmentService;
import com.financeos.domain.investment.InvestmentTransaction;
import com.financeos.domain.investment.InvestmentTransactionRepository;
import com.financeos.domain.investment.TradeSettlementClassification;
import com.financeos.domain.investment.TradeSettlementClassificationRepository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * The paginated corporate actions list: the caller's own actions filtered, sorted and paged by the
 * database, each with the caller's open quantity just before and after its ex-date, computed by the lot
 * engine from reads batch-loaded once for the page (never one query per row).
 */
@Service
@Transactional(readOnly = true)
public class CorporateActionPageService {

    public static final int DEFAULT_PAGE_SIZE = 25;
    public static final int MAX_PAGE_SIZE = 200;
    public static final List<String> SORTS = List.of("newest", "oldest", "name");

    private final CorporateActionRepository corporateActionRepository;
    private final AssetClassOverrideService overrideService;
    private final HoldingRepository holdingRepository;
    private final InvestmentTransactionRepository transactionRepository;
    private final TradeSettlementClassificationRepository classificationRepository;
    private final InvestmentService investmentService;

    public CorporateActionPageService(CorporateActionRepository corporateActionRepository,
                                      AssetClassOverrideService overrideService,
                                      HoldingRepository holdingRepository,
                                      InvestmentTransactionRepository transactionRepository,
                                      TradeSettlementClassificationRepository classificationRepository,
                                      InvestmentService investmentService) {
        this.corporateActionRepository = corporateActionRepository;
        this.overrideService = overrideService;
        this.holdingRepository = holdingRepository;
        this.transactionRepository = transactionRepository;
        this.classificationRepository = classificationRepository;
        this.investmentService = investmentService;
    }

    public CorporateActionPage page(@Nullable CorporateActionType type, @Nullable String search,
                                    @Nullable String sort, int page, int size) {
        String sortKey = sort == null || sort.isBlank() ? "newest" : sort.trim().toLowerCase(Locale.ROOT);
        if (!SORTS.contains(sortKey)) {
            throw new ValidationException("Unsupported corporate action sort: " + sort + " (use newest, oldest or name)");
        }
        int safePage = Math.max(0, page);
        int safeSize = Math.min(MAX_PAGE_SIZE, Math.max(1, size));
        UUID userId = UserContext.getCurrentUserId();
        if (userId == null) {
            return new CorporateActionPage(List.of(), safePage, safeSize, 0, 0);
        }
        String needle = search == null || search.isBlank() ? null : search.trim().toLowerCase(Locale.ROOT);
        Page<CorporateAction> found = corporateActionRepository.pageOwned(userId, type, needle, sortKey,
                PageRequest.of(safePage, safeSize));
        InstrumentOverrides overrides = found.isEmpty() ? InstrumentOverrides.NONE : overrideService.overridesForCurrentUser();
        Map<UUID, BigDecimal[]> effects = effects(userId, found.getContent());
        List<CorporateActionRowResponse> items = new ArrayList<>(found.getNumberOfElements());
        for (CorporateAction ca : found.getContent()) {
            BigDecimal[] e = effects.get(ca.getId());
            items.add(CorporateActionRowResponse.of(CorporateActionResponse.from(ca, overrides),
                    e == null ? null : e[0], e == null ? null : e[1], e == null ? null : e[2], e == null ? null : e[3]));
        }
        return new CorporateActionPage(items, safePage, safeSize, found.getTotalElements(), found.getTotalPages());
    }

    /**
     * Per action id, {@code [quantityBefore, quantityAfter, costPerUnitBefore, costPerUnitAfter]} summed over the caller's holdings of the
     * action's instrument; absent when they hold none. One query each for the holdings, trades,
     * intraday classifications, and actions on / into the instruments; the engine then runs in memory.
     */
    private Map<UUID, BigDecimal[]> effects(UUID userId, List<CorporateAction> actions) {
        Map<UUID, BigDecimal[]> out = new HashMap<>();
        if (actions.isEmpty()) {
            return out;
        }
        Set<UUID> instrumentIds = new HashSet<>();
        for (CorporateAction ca : actions) {
            instrumentIds.add(ca.getInstrument().getId());
        }
        Map<UUID, List<Holding>> holdingsByInstrument = new HashMap<>();
        Set<UUID> holdingIds = new HashSet<>();
        for (Holding h : holdingRepository.findAllWithDetailsOfUser(userId)) {
            if (instrumentIds.contains(h.getInstrument().getId())) {
                holdingsByInstrument.computeIfAbsent(h.getInstrument().getId(), k -> new ArrayList<>()).add(h);
                holdingIds.add(h.getId());
            }
        }
        if (holdingIds.isEmpty()) {
            return out;
        }
        Map<UUID, List<InvestmentTransaction>> txns = new HashMap<>();
        for (InvestmentTransaction t : transactionRepository.findByUser_IdOrderByTradeDateAscCreatedAtAsc(userId)) {
            if (t.getHolding() != null && holdingIds.contains(t.getHolding().getId())) {
                txns.computeIfAbsent(t.getHolding().getId(), k -> new ArrayList<>()).add(t);
            }
        }
        Map<UUID, List<TradeSettlementClassification>> classes = new HashMap<>();
        for (TradeSettlementClassification c : classificationRepository.findByUser_Id(userId)) {
            if (c.getHolding() != null && holdingIds.contains(c.getHolding().getId())) {
                classes.computeIfAbsent(c.getHolding().getId(), k -> new ArrayList<>()).add(c);
            }
        }
        Map<UUID, List<CorporateAction>> actionsByInstrument = new HashMap<>();
        Set<UUID> seededInstruments = new HashSet<>();
        for (List<UUID> chunk : InstrumentLocalSearch.chunks(instrumentIds)) {
            for (CorporateAction ca : corporateActionRepository.findOwnedByInstrumentIds(userId, chunk)) {
                actionsByInstrument.computeIfAbsent(ca.getInstrument().getId(), k -> new ArrayList<>()).add(ca);
            }
            for (CorporateAction ca : corporateActionRepository.findOwnedByTargetInstrumentIds(userId, chunk)) {
                seededInstruments.add(ca.getTargetInstrument().getId());
            }
        }
        // Holdings of an instrument that other actions seed shares into carry those seeds (rare; read per holding).
        Map<UUID, List<InvestmentService.SeedLot>> seeds = new HashMap<>();
        for (Map.Entry<UUID, List<Holding>> e : holdingsByInstrument.entrySet()) {
            if (seededInstruments.contains(e.getKey())) {
                for (Holding h : e.getValue()) {
                    seeds.put(h.getId(), investmentService.seedLotsFor(h));
                }
            }
        }
        for (CorporateAction ca : actions) {
            List<Holding> held = holdingsByInstrument.get(ca.getInstrument().getId());
            if (held == null) {
                continue;
            }
            List<CorporateAction> instrumentActions = actionsByInstrument.getOrDefault(ca.getInstrument().getId(), List.of());
            BigDecimal before = BigDecimal.ZERO;
            BigDecimal after = BigDecimal.ZERO;
            BigDecimal costBefore = BigDecimal.ZERO;
            BigDecimal costAfter = BigDecimal.ZERO;
            for (Holding h : held) {
                List<InvestmentTransaction> t = txns.getOrDefault(h.getId(), List.of());
                List<TradeSettlementClassification> c = classes.getOrDefault(h.getId(), List.of());
                List<InvestmentService.SeedLot> s = seeds.get(h.getId());
                List<InvestmentService.Lot> lotsBefore = investmentService.buildOpenLotsBeforeDate(
                        h, ca.getExDate(), true, null, t, instrumentActions, s, c);
                before = before.add(quantity(lotsBefore));
                costBefore = costBefore.add(cost(lotsBefore));
                // The engine runs corporate actions before same-day trades, so "after" excludes the ex-date's trades.
                LocalDate exDate = ca.getExDate();
                List<InvestmentTransaction> tBefore = t.stream().filter(x -> x.getTradeDate().isBefore(exDate)).toList();
                List<TradeSettlementClassification> cBefore = c.stream().filter(x -> x.getTradeDate().isBefore(exDate)).toList();
                List<InvestmentService.Lot> lotsAfter = investmentService.buildOpenLotsBeforeDate(
                        h, exDate, false, null, tBefore, instrumentActions, s, cBefore);
                after = after.add(quantity(lotsAfter));
                costAfter = costAfter.add(cost(lotsAfter));
            }
            out.put(ca.getId(), new BigDecimal[]{before.setScale(4, RoundingMode.HALF_UP),
                    after.setScale(4, RoundingMode.HALF_UP), perUnit(costBefore, before), perUnit(costAfter, after)});
        }
        return out;
    }

    private static BigDecimal quantity(List<InvestmentService.Lot> lots) {
        BigDecimal total = BigDecimal.ZERO;
        for (InvestmentService.Lot lot : lots) {
            total = total.add(lot.remainingQty);
        }
        return total.max(BigDecimal.ZERO);
    }

    private static BigDecimal cost(List<InvestmentService.Lot> lots) {
        BigDecimal total = BigDecimal.ZERO;
        for (InvestmentService.Lot lot : lots) {
            total = total.add(lot.remainingQty.multiply(lot.costPerUnit));
        }
        return total;
    }

    /** Average cost per unit; null when nothing is open. */
    private static BigDecimal perUnit(BigDecimal cost, BigDecimal qty) {
        return qty.signum() <= 0 ? null : cost.divide(qty, 4, RoundingMode.HALF_UP);
    }
}
