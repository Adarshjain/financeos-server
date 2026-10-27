package com.financeos.domain.investment;

import com.financeos.api.investment.dto.HoldingDetailResponse;
import com.financeos.api.investment.dto.HoldingsResponse;
import com.financeos.api.investment.dto.PositionDto;
import com.financeos.api.investment.dto.SellEstimateResponse;
import com.financeos.api.investment.dto.TaxHarvestResponse.HarvestRealised;
import com.financeos.core.exception.ResourceNotFoundException;
import com.financeos.core.exception.ValidationException;
import com.financeos.core.security.UserContext;
import com.financeos.core.time.AppTime;
import com.financeos.domain.instrument.InstrumentType;
import com.financeos.domain.instrument.PriceSource;
import com.financeos.domain.instrument.TaxClass;
import com.financeos.domain.investment.InvestmentService.HoldingDetail;
import com.financeos.domain.investment.dto.RealizedLot;
import com.financeos.domain.investment.returncalc.XirrCalculator;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * The holdings pages: one row per instrument with the user's holdings in it (one per broker account)
 * summed, the holding page of one instrument, and what selling some of it would book. Every figure comes
 * from {@link InvestmentService#getHoldingDetails} (one engine pass, reads batch-loaded), so the numbers
 * are the positions page's.
 */
@Service
@Transactional(readOnly = true)
public class HoldingsService {

    public static final int DEFAULT_PAGE_SIZE = 50;
    public static final int MAX_PAGE_SIZE = 200;
    private static final BigDecimal HUNDRED = BigDecimal.valueOf(100);

    private final InvestmentService investmentService;
    private final InvestmentTransactionRepository transactionRepository;

    public HoldingsService(InvestmentService investmentService, InvestmentTransactionRepository transactionRepository) {
        this.investmentService = investmentService;
        this.transactionRepository = transactionRepository;
    }

    /** What one instrument's holdings add up to. */
    private record Aggregate(UUID instrumentId, List<HoldingDetail> details, PositionDto first, BigDecimal quantity,
                             BigDecimal invested, BigDecimal currentValue, BigDecimal unrealised,
                             @Nullable BigDecimal ltp, @Nullable LocalDate priceAsOf, @Nullable OffsetDateTime priceAt, boolean manualPrice,
                             @Nullable BigDecimal dayChange, @Nullable BigDecimal previousValue,
                             @Nullable String mergedIntoName, @Nullable LocalDate mergedIntoDate) {

        static Aggregate of(UUID instrumentId, List<HoldingDetail> details) {
            BigDecimal qty = BigDecimal.ZERO;
            BigDecimal invested = BigDecimal.ZERO;
            BigDecimal value = BigDecimal.ZERO;
            BigDecimal unrealised = BigDecimal.ZERO;
            BigDecimal ltp = null;
            LocalDate asOf = null;
            OffsetDateTime at = null;
            boolean manual = false;
            BigDecimal day = null;
            BigDecimal previous = null;
            String mergedName = null;
            LocalDate mergedDate = null;
            for (HoldingDetail d : details) {
                PositionDto p = d.dto();
                qty = qty.add(p.quantity());
                invested = invested.add(p.invested());
                if (p.currentValue() != null) {
                    value = value.add(p.currentValue());
                }
                if (p.unrealizedGainLoss() != null) {
                    unrealised = unrealised.add(p.unrealizedGainLoss());
                }
                if (p.lastPrice() != null && (ltp == null || (p.lastPriceAsOf() != null
                        && (asOf == null || p.lastPriceAsOf().isAfter(asOf))))) {
                    ltp = p.lastPrice();
                    asOf = p.lastPriceAsOf();
                    at = p.lastPriceAt();
                    manual = p.lastPriceSource() == PriceSource.MANUAL;
                }
                if (p.dayChange() != null && p.previousClose() != null) {
                    day = (day == null ? BigDecimal.ZERO : day).add(p.dayChange());
                    previous = (previous == null ? BigDecimal.ZERO : previous).add(p.previousClose().multiply(p.quantity()));
                }
                if (mergedName == null && p.mergedIntoName() != null) {
                    mergedName = p.mergedIntoName();
                    mergedDate = p.mergedIntoDate();
                }
            }
            return new Aggregate(instrumentId, details, details.get(0).dto(), qty, invested, value, unrealised, ltp, asOf,
                    at, manual, day, previous, mergedName, mergedDate);
        }

        boolean open() {
            return quantity.signum() > 0;
        }

        /** Shown when something is held, or when it was fully merged into another instrument. */
        boolean shown() {
            return open() || mergedIntoName != null;
        }

        BigDecimal avgCost() {
            return open() ? invested.divide(quantity, 4, RoundingMode.HALF_UP) : BigDecimal.ZERO;
        }

        @Nullable
        BigDecimal unrealisedPct() {
            return pct(unrealised, invested);
        }

        @Nullable
        BigDecimal dayChangePct() {
            return dayChange == null ? null : pct(dayChange, previousValue);
        }

        boolean noPrice() {
            return open() && ltp == null;
        }

        InstrumentType assetType() {
            return first.instrument().type();
        }

        String name() {
            return first.instrument().name();
        }

        @Nullable
        String symbol() {
            return first.instrument().symbol();
        }

        int brokerCount() {
            long open = details.stream().filter(d -> d.dto().quantity().signum() > 0).count();
            return (int) (open > 0 ? open : details.size());
        }
    }

    @Nullable
    private static BigDecimal pct(@Nullable BigDecimal part, @Nullable BigDecimal base) {
        if (part == null || base == null || base.signum() == 0) {
            return null;
        }
        return part.multiply(HUNDRED).divide(base, 2, RoundingMode.HALF_UP);
    }

    private static BigDecimal money(BigDecimal v) {
        return v.setScale(2, RoundingMode.HALF_UP);
    }

    private static Map<UUID, List<HoldingDetail>> byInstrument(List<HoldingDetail> details) {
        Map<UUID, List<HoldingDetail>> out = new LinkedHashMap<>();
        for (HoldingDetail d : details) {
            out.computeIfAbsent(d.dto().instrument().id(), k -> new ArrayList<>()).add(d);
        }
        return out;
    }

    /**
     * Rows are ordered by asset-type group first ({@link InstrumentType} declaration order: stock, mutual_fund,
     * etf, the order the groups are listed in), then by {@code sort} and {@code dir}, then paged.
     *
     * @param sort name (A-Z, default) | return_pct | return_abs (best first)
     * @param dir  asc | desc (case-insensitive); absent = the key's own order (name A-Z, returns best first);
     *             otherwise it flips that order. Nulls stay last either way.
     */
    public HoldingsResponse list(@Nullable UUID brokerAccountId, @Nullable String assetType, @Nullable String search,
                                 @Nullable String sort, @Nullable String dir, @Nullable Integer page, @Nullable Integer size) {
        InstrumentType type = parseType(assetType);
        String sortKey = sort == null || sort.isBlank() ? "name" : sort.trim().toLowerCase(Locale.ROOT);
        if (!List.of("name", "return_pct", "return_abs").contains(sortKey)) {
            throw new ValidationException("sort must be name, return_pct or return_abs");
        }
        String dirKey = dir == null || dir.isBlank() ? null : dir.trim().toLowerCase(Locale.ROOT);
        if (dirKey != null && !dirKey.equals("asc") && !dirKey.equals("desc")) {
            throw new ValidationException("dir must be asc or desc");
        }
        String needle = search == null || search.isBlank() ? null : search.trim().toLowerCase(Locale.ROOT);
        int pageSize = size == null || size < 1 ? DEFAULT_PAGE_SIZE : Math.min(size, MAX_PAGE_SIZE);
        int pageNo = page == null ? 0 : Math.max(0, page);

        List<HoldingDetail> details = investmentService.getHoldingDetails(null).stream()
                .filter(d -> brokerAccountId == null || brokerAccountId.equals(d.dto().brokerAccountId()))
                .toList();
        List<Aggregate> matching = new ArrayList<>();
        for (Map.Entry<UUID, List<HoldingDetail>> e : byInstrument(details).entrySet()) {
            Aggregate a = Aggregate.of(e.getKey(), e.getValue());
            if (type != null && a.assetType() != type) {
                continue;
            }
            if (needle != null && !(contains(a.symbol(), needle) || contains(a.name(), needle))) {
                continue;
            }
            matching.add(a);
        }

        // Header and groups: the whole filtered set. XIRR also counts what was sold out (its cashflows).
        BigDecimal value = BigDecimal.ZERO;
        BigDecimal invested = BigDecimal.ZERO;
        BigDecimal unrealised = BigDecimal.ZERO;
        BigDecimal day = null;
        BigDecimal previous = null;
        LocalDate asOf = null;
        OffsetDateTime asOfAt = null;
        Map<InstrumentType, BigDecimal> groupValue = new EnumMap<>(InstrumentType.class);
        Map<InstrumentType, Integer> groupCount = new EnumMap<>(InstrumentType.class);
        List<XirrCalculator.Cashflow> cashflows = new ArrayList<>();
        for (Aggregate a : matching) {
            a.details().forEach(d -> cashflows.addAll(d.cashflows()));
            if (!a.open()) {
                continue;
            }
            value = value.add(a.currentValue());
            invested = invested.add(a.invested());
            unrealised = unrealised.add(a.unrealised());
            if (a.dayChange() != null) {
                day = (day == null ? BigDecimal.ZERO : day).add(a.dayChange());
                previous = (previous == null ? BigDecimal.ZERO : previous).add(a.previousValue());
            }
            if (a.priceAsOf() != null && (asOf == null || a.priceAsOf().isAfter(asOf))) {
                asOf = a.priceAsOf();
                asOfAt = a.priceAt();
            }
            groupValue.merge(a.assetType(), a.currentValue(), BigDecimal::add);
            groupCount.merge(a.assetType(), 1, Integer::sum);
        }
        HoldingsResponse.Header header = new HoldingsResponse.Header(money(value),
                day == null ? null : money(day), day == null ? null : pct(day, previous), money(invested), money(unrealised),
                pct(unrealised, invested), matching.isEmpty() ? null : investmentService.xirrPercent(cashflows), asOf, asOfAt);
        List<HoldingsResponse.Group> groups = new ArrayList<>();
        groupValue.forEach((t, v) -> groups.add(new HoldingsResponse.Group(t, money(v), groupCount.get(t))));

        List<Aggregate> rows = matching.stream().filter(Aggregate::shown).sorted(comparator(sortKey, dirKey)).toList();
        long total = rows.size();
        int from = (int) Math.min((long) pageNo * pageSize, total);
        int to = (int) Math.min((long) from + pageSize, total);
        int totalPages = total == 0 ? 0 : (int) Math.ceil((double) total / pageSize);
        List<HoldingsResponse.Row> pageRows = rows.subList(from, to).stream().map(HoldingsService::row).toList();
        return new HoldingsResponse(header, groups, pageRows, pageNo, pageSize, total, totalPages);
    }

    private static boolean contains(@Nullable String text, String lowerNeedle) {
        return text != null && text.toLowerCase(Locale.ROOT).contains(lowerNeedle);
    }

    private static InstrumentType parseType(@Nullable String assetType) {
        if (assetType == null || assetType.isBlank()) {
            return null;
        }
        try {
            return InstrumentType.valueOf(assetType.trim().toLowerCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new ValidationException("assetType must be stock, mutual_fund or etf");
        }
    }

    /** Asset-type group first, then the key in its direction (nulls last), then name and id so paging is stable. */
    private static Comparator<Aggregate> comparator(String sortKey, @Nullable String dirKey) {
        Comparator<Aggregate> byName = Comparator.comparing((Aggregate a) -> a.name() == null ? "" : a.name().toLowerCase(Locale.ROOT))
                .thenComparing(a -> a.instrumentId().toString());
        // The key's own order: name A-Z, returns best (highest) first.
        boolean ascending = dirKey == null ? sortKey.equals("name") : dirKey.equals("asc");
        Comparator<BigDecimal> order = ascending ? Comparator.<BigDecimal>naturalOrder() : Comparator.<BigDecimal>reverseOrder();
        Comparator<Aggregate> byKey = switch (sortKey) {
            case "return_pct" -> Comparator.comparing(Aggregate::unrealisedPct, Comparator.nullsLast(order));
            case "return_abs" -> Comparator.comparing(Aggregate::unrealised, Comparator.nullsLast(order));
            default -> ascending ? byName : byName.reversed();
        };
        return Comparator.comparing(Aggregate::assetType).thenComparing(byKey).thenComparing(byName);
    }

    private static HoldingsResponse.Row row(Aggregate a) {
        return new HoldingsResponse.Row(a.instrumentId(), a.symbol(), a.name(), a.assetType(), a.quantity(), a.avgCost(),
                a.ltp(), a.priceAsOf(), a.priceAt(), a.manualPrice(), a.noPrice(), money(a.currentValue()), money(a.invested()),
                money(a.unrealised()), a.unrealisedPct(), a.dayChange() == null ? null : money(a.dayChange()),
                a.dayChangePct(), a.brokerCount(), a.mergedIntoName(), a.mergedIntoDate());
    }

    /** The holding page of one instrument; 404 when the user holds (or held) none of it. */
    public HoldingDetailResponse detail(UUID instrumentId) {
        List<HoldingDetail> details = investmentService.getHoldingDetails(instrumentId);
        if (details.isEmpty()) {
            throw new ResourceNotFoundException("Holding", instrumentId);
        }
        Aggregate a = Aggregate.of(instrumentId, details);
        LocalDate today = AppTime.today();
        BigDecimal realised = BigDecimal.ZERO;
        BigDecimal charges = BigDecimal.ZERO;
        BigDecimal dividends = BigDecimal.ZERO;
        List<XirrCalculator.Cashflow> cashflows = new ArrayList<>();
        List<String> terms = new ArrayList<>();
        List<HoldingDetailResponse.Broker> brokers = new ArrayList<>();
        for (HoldingDetail d : details) {
            HoldingPosition p = d.position();
            realised = realised.add(p.realized()).add(p.intradayRealized());
            charges = charges.add(p.totalCharges());
            dividends = dividends.add(p.dividends());
            cashflows.addAll(d.cashflows());
            for (HoldingTrace.OpenLot lot : d.openLots()) {
                if (lot.quantity().signum() > 0) {
                    String term = CapitalGainsTerm.term(d.classification().taxClass(), lot.buyDate(), today);
                    if (!terms.contains(term)) {
                        terms.add(term);
                    }
                }
            }
            PositionDto dto = d.dto();
            if (dto.quantity().signum() > 0) {
                brokers.add(new HoldingDetailResponse.Broker(dto.brokerAccountId(), dto.brokerName(), dto.quantity(),
                        dto.avgCost(), money(dto.currentValue() != null ? dto.currentValue() : BigDecimal.ZERO)));
            }
        }
        String term = terms.isEmpty() ? null : terms.size() == 1 ? terms.get(0) : "mixed";
        LocalDate firstBuy = transactionRepository.findFirstBuyDate(UserContext.getCurrentUserId(), instrumentId);
        return new HoldingDetailResponse(instrumentId, a.symbol(), a.name(), a.assetType(), a.quantity(), a.avgCost(),
                a.ltp(), a.priceAsOf(), a.priceAt(), a.manualPrice(), a.noPrice(), money(a.currentValue()), money(a.invested()),
                money(a.unrealised()), a.unrealisedPct(), a.dayChange() == null ? null : money(a.dayChange()),
                a.dayChangePct(), a.mergedIntoName(), a.mergedIntoDate(), money(realised), money(charges), money(dividends),
                investmentService.xirrPercent(cashflows), firstBuy, term, brokers);
    }

    /**
     * Selling {@code quantity} (all when null) today: the oldest open lots across broker accounts first.
     * Equity-oriented holdings also get the tax this adds to the current financial year: the year's
     * booked lots (all instruments) with and without this sale go through the harvest set-off and
     * exemption, so what the sale adds is what the estimate shows.
     */
    public SellEstimateResponse sellEstimate(UUID instrumentId, @Nullable BigDecimal quantity) {
        List<HoldingDetail> everything = investmentService.getHoldingDetails(null);
        List<HoldingDetail> details = everything.stream()
                .filter(d -> instrumentId.equals(d.dto().instrument().id())).toList();
        if (details.isEmpty()) {
            throw new ResourceNotFoundException("Holding", instrumentId);
        }
        LocalDate today = AppTime.today();
        record OpenLotOf(HoldingDetail detail, HoldingTrace.OpenLot lot) {}
        List<OpenLotOf> open = new ArrayList<>();
        BigDecimal held = BigDecimal.ZERO;
        for (HoldingDetail d : details) {
            for (HoldingTrace.OpenLot lot : d.openLots()) {
                if (lot.quantity().signum() > 0) {
                    open.add(new OpenLotOf(d, lot));
                    held = held.add(lot.quantity());
                }
            }
        }
        open.sort(Comparator.comparing(o -> o.lot().buyDate()));
        BigDecimal sell = quantity == null ? held : quantity;
        if (sell.signum() <= 0) {
            throw new ValidationException(quantity == null ? "Nothing is held to sell" : "quantity must be positive");
        }
        if (sell.compareTo(held) > 0) {
            throw new ValidationException("quantity exceeds the " + held.stripTrailingZeros().toPlainString() + " held");
        }

        Aggregate a = Aggregate.of(instrumentId, details);
        BigDecimal price = a.ltp();
        boolean noPrice = price == null;
        TaxClass taxClass = details.get(0).classification().taxClass();
        boolean equity = taxClass == TaxClass.EQUITY_ORIENTED;

        List<SellEstimateResponse.Lot> lots = new ArrayList<>();
        List<RealizedLot> hypothetical = new ArrayList<>();
        BigDecimal remaining = sell;
        BigDecimal cost = BigDecimal.ZERO;
        BigDecimal proceeds = BigDecimal.ZERO;
        BigDecimal longGain = BigDecimal.ZERO;
        BigDecimal shortGain = BigDecimal.ZERO;
        for (OpenLotOf o : open) {
            if (remaining.signum() <= 0) {
                break;
            }
            BigDecimal take = remaining.min(o.lot().quantity());
            remaining = remaining.subtract(take);
            BigDecimal lotCost = take.multiply(o.lot().costPerUnit());
            BigDecimal lotProceeds = noPrice ? lotCost : take.multiply(price);
            BigDecimal gain = lotProceeds.subtract(lotCost);
            String term = CapitalGainsTerm.term(o.detail().classification().taxClass(), o.lot().buyDate(), today);
            cost = cost.add(lotCost);
            proceeds = proceeds.add(lotProceeds);
            if (CapitalGainsTerm.LONG.equals(term)) {
                longGain = longGain.add(gain);
            } else {
                shortGain = shortGain.add(gain);
            }
            lots.add(new SellEstimateResponse.Lot(o.lot().buyDate(), take.stripTrailingZeros(),
                    o.lot().costPerUnit().setScale(4, RoundingMode.HALF_UP), money(lotCost), money(lotProceeds), money(gain), term));
            if (equity) {
                HoldingPosition p = o.detail().position();
                hypothetical.add(new RealizedLot(p.holding().getId(), p.holding().getBrokerAccount().getId(),
                        p.holding().getBrokerAccount().getName(), instrumentId, a.name(), a.assetType(), o.lot().buyDate(),
                        today, take, lotCost, lotProceeds, gain, ChronoUnit.DAYS.between(o.lot().buyDate(), today), term,
                        o.detail().classification().assetClass(), taxClass,
                        CapitalGainsTerm.grandfathered(taxClass, o.lot().buyDate())));
            }
        }

        int fy = TaxHarvestService.currentFy(today);
        BigDecimal stcgTax = null;
        BigDecimal ltcgTax = null;
        BigDecimal tax = null;
        BigDecimal exemptionBefore = null;
        BigDecimal exemptionAfter = null;
        BigDecimal bookedShort = null;
        BigDecimal bookedEquityLong = null;
        BigDecimal bookedOtherLong = null;
        BigDecimal exemptionLimit = null;
        if (equity) {
            LocalDate fyStart = LocalDate.of(fy, 4, 1);
            LocalDate fyEnd = LocalDate.of(fy + 1, 3, 31);
            List<RealizedLot> booked = new ArrayList<>();
            for (HoldingDetail d : everything) {
                for (RealizedLot lot : d.realizedLots()) {
                    if (lot.sellDate() != null && !lot.sellDate().isBefore(fyStart) && !lot.sellDate().isAfter(fyEnd)) {
                        booked.add(lot);
                    }
                }
            }
            BigDecimal limit = TaxHarvestService.exemptionLimit(fy);
            bookedShort = BigDecimal.ZERO;
            bookedEquityLong = BigDecimal.ZERO;
            bookedOtherLong = BigDecimal.ZERO;
            for (RealizedLot lot : booked) {
                BigDecimal pnl = lot.realizedPnl() != null ? lot.realizedPnl() : BigDecimal.ZERO;
                if (CapitalGainsTerm.LONG.equals(lot.term())) {
                    if (lot.taxClass() == TaxClass.EQUITY_ORIENTED) {
                        bookedEquityLong = bookedEquityLong.add(pnl);
                    } else {
                        bookedOtherLong = bookedOtherLong.add(pnl);
                    }
                } else {
                    bookedShort = bookedShort.add(pnl);
                }
            }
            bookedShort = money(bookedShort);
            bookedEquityLong = money(bookedEquityLong);
            bookedOtherLong = money(bookedOtherLong);
            exemptionLimit = money(limit);
            HarvestRealised before = TaxHarvestService.realised(booked, limit);
            List<RealizedLot> withSale = new ArrayList<>(booked);
            withSale.addAll(hypothetical);
            HarvestRealised after = TaxHarvestService.realised(withSale, limit);
            stcgTax = money(after.netStcg().subtract(before.netStcg()).max(BigDecimal.ZERO).multiply(TaxHarvestService.STCG_RATE));
            ltcgTax = money(after.taxableLtcg().subtract(before.taxableLtcg()).max(BigDecimal.ZERO).multiply(TaxHarvestService.LTCG_RATE));
            tax = stcgTax.add(ltcgTax);
            exemptionBefore = before.exemptionLeft();
            exemptionAfter = after.exemptionLeft();
        }
        return new SellEstimateResponse(instrumentId, sell.stripTrailingZeros(), price, noPrice, money(proceeds), money(cost),
                money(proceeds.subtract(cost)), money(longGain), money(shortGain), equity, fy,
                equity ? TaxHarvestService.STCG_RATE : null, equity ? TaxHarvestService.LTCG_RATE : null,
                stcgTax, ltcgTax, tax, exemptionBefore, exemptionAfter, lots, bookedShort, bookedEquityLong, bookedOtherLong, exemptionLimit);
    }
}
