package com.financeos.domain.investment;

import com.financeos.api.investment.dto.TaxHarvestResponse.HarvestLosses;
import com.financeos.api.investment.dto.TaxHarvestResponse.HarvestOpenLot;
import com.financeos.api.investment.dto.TaxHarvestResponse.HarvestOpenLotPage;
import com.financeos.api.investment.dto.TaxHarvestResponse.HarvestOtherGains;
import com.financeos.api.investment.dto.TaxHarvestResponse.HarvestRealised;
import com.financeos.api.investment.dto.TaxHarvestResponse.HarvestSummary;
import com.financeos.api.investment.dto.TaxHarvestResponse.HarvestTurningLongTerm;
import com.financeos.api.investment.dto.TaxHarvestResponse;
import com.financeos.core.exception.ValidationException;
import com.financeos.core.time.AppTime;
import com.financeos.domain.holding.Holding;
import com.financeos.domain.instrument.AssetClassifier;
import com.financeos.domain.instrument.InstrumentOverrides;
import com.financeos.domain.instrument.TaxClass;
import com.financeos.domain.investment.dto.RealizedLot;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * The tax-harvesting view of the portfolio for one Indian financial year (April–March): gains
 * booked in the year with the Indian set-off rules pooled across asset classes and the equity LTCG
 * exemption applied (see {@link HarvestRealised} for the order), and — for the current financial
 * year only — today's open lots with their term, unrealised gain and when they turn long term.
 *
 * <p>One engine pass per holding ({@link InvestmentService#getAllHoldingLots}, its reads batch-loaded
 * per user) yields both the realised lots and the open lots, with corporate actions applied exactly
 * as on the positions page and the user's own asset-class overrides; each lot is valued at its
 * holding's latest price (the price the position itself uses), so no query runs per lot.
 */
@Service
@Transactional(readOnly = true)
public class TaxHarvestService {

    /** The s.112A exemption from FY 2024-25 on (₹1.25 lakh). */
    public static final BigDecimal EXEMPTION_LIMIT = new BigDecimal("125000");
    /** The s.112A exemption before FY 2024-25 (₹1 lakh). */
    public static final BigDecimal EXEMPTION_LIMIT_BEFORE_FY2024 = new BigDecimal("100000");
    public static final int TURNING_LONG_WITHIN_DAYS = 30;
    public static final int DEFAULT_PAGE_SIZE = 20;
    public static final int MAX_PAGE_SIZE = 100;

    private final InvestmentService investmentService;

    public TaxHarvestService(InvestmentService investmentService) {
        this.investmentService = investmentService;
    }

    /** The current Indian financial year's start year for {@code today}. */
    public static int currentFy(LocalDate today) {
        return today.getMonthValue() >= 4 ? today.getYear() : today.getYear() - 1;
    }

    /** The equity LTCG exemption for the financial year starting in {@code fy}: ₹1.25L from 2024, ₹1L before. */
    public static BigDecimal exemptionLimit(int fy) {
        return fy >= 2024 ? EXEMPTION_LIMIT : EXEMPTION_LIMIT_BEFORE_FY2024;
    }

    public TaxHarvestResponse harvest(Integer fy, Integer page, Integer size) {
        LocalDate today = AppTime.today();
        int currentFy = currentFy(today);
        int year = fy != null ? fy : currentFy;
        if (year < 2000 || year > 2100) {
            throw new ValidationException("fy must be a financial year's start year, e.g. " + currentFy);
        }
        int pSize = size == null || size < 1 ? DEFAULT_PAGE_SIZE : Math.min(size, MAX_PAGE_SIZE);
        int pNum = page == null ? 0 : Math.max(0, page);
        LocalDate fyStart = LocalDate.of(year, 4, 1);
        LocalDate fyEnd = LocalDate.of(year + 1, 3, 31);
        // Open lots are today's: they only mean something against the current year's exemption.
        boolean withOpenLots = year == currentFy;

        List<RealizedLot> realised = new ArrayList<>();
        List<HarvestOpenLot> openLots = new ArrayList<>();
        List<List<HarvestOpenLot>> openLotsByHolding = new ArrayList<>();
        List<InvestmentService.HoldingLots> holdingLotsList = investmentService.getAllHoldingLots();
        InstrumentOverrides overrides = holdingLotsList.isEmpty() || !withOpenLots
                ? InstrumentOverrides.NONE : InstrumentOverrides.orNone(investmentService.instrumentOverrides());
        for (InvestmentService.HoldingLots h : holdingLotsList) {
            for (RealizedLot lot : h.realizedLots()) {
                if (lot.sellDate() != null && !lot.sellDate().isBefore(fyStart) && !lot.sellDate().isAfter(fyEnd)) {
                    realised.add(lot);
                }
            }
            if (!withOpenLots) {
                continue;
            }
            List<HarvestOpenLot> holdingLots = new ArrayList<>();
            for (HoldingTrace.OpenLot lot : h.openLots()) {
                if (lot.quantity() != null && lot.quantity().signum() > 0) {
                    holdingLots.add(openLot(h.holding(), overrides.name(h.holding().getInstrument()), h.classification(),
                            h.position().latestPrice(), lot, today));
                }
            }
            openLotsByHolding.add(holdingLots);
            openLots.addAll(holdingLots);
        }
        openLots.sort(Comparator.comparing(HarvestOpenLot::gain).reversed()
                .thenComparing(HarvestOpenLot::buyDate)
                .thenComparing(HarvestOpenLot::instrument, Comparator.nullsLast(Comparator.naturalOrder())));

        HarvestRealised booked = realised(realised, exemptionLimit(year));
        return new TaxHarvestResponse(year, fyStart, fyEnd, booked,
                withOpenLots ? summary(openLotsByHolding, booked.exemptionLeft(), today) : null,
                page(openLots, pNum, pSize));
    }

    /** {@link #realised(List, BigDecimal)} with the current ₹1.25L exemption. */
    static HarvestRealised realised(List<RealizedLot> lots) {
        return realised(lots, EXEMPTION_LIMIT);
    }

    /**
     * The year's booked gains. Buckets per lot: slab lots (specified debt bought on/after
     * 2023-04-01; deemed short term) → {@code slabGains}; equity-oriented → stcg/stcl/ltcg/ltcl by
     * term; everything else (OTHER, debt bought earlier, unclassified) → {@code otherGains} by term.
     * Then the pooled set-off, in this order:
     * <ol>
     *   <li>Within each head gains and losses net: all short-term P&amp;L (equity, other, slab) into one
     *       short-term figure; long-term P&amp;L netted per class (equity / other).</li>
     *   <li>Short-term losses offset short-term gains of any class ({@code netStcg} is what is left).</li>
     *   <li>Long-term losses (equity or other) offset long-term gains only — other-class LTCG first,
     *       then equity LTCG (the order that keeps most of the equity exemption usable).</li>
     *   <li>Short-term losses still left offset the remaining long-term gains, other-class first.</li>
     *   <li>The exemption applies to the equity-oriented long-term gain left after set-off (s.112A)
     *       only; {@code taxableLtcg} = {@code netLtcg} − {@code exemptionUsed}. Unabsorbed losses carry
     *       forward.</li>
     * </ol>
     */
    static HarvestRealised realised(List<RealizedLot> lots, BigDecimal exemptionLimit) {
        BigDecimal stcg = BigDecimal.ZERO;
        BigDecimal stcl = BigDecimal.ZERO;
        BigDecimal ltcg = BigDecimal.ZERO;
        BigDecimal ltcl = BigDecimal.ZERO;
        BigDecimal slab = BigDecimal.ZERO;
        BigDecimal otherShort = BigDecimal.ZERO;
        BigDecimal otherLong = BigDecimal.ZERO;
        for (RealizedLot lot : lots) {
            BigDecimal pnl = lot.realizedPnl() != null ? lot.realizedPnl() : BigDecimal.ZERO;
            if (CapitalGainsTerm.SLAB.equals(lot.term())) {
                slab = slab.add(pnl);
                continue;
            }
            boolean longTerm = CapitalGainsTerm.LONG.equals(lot.term());
            if (lot.taxClass() != TaxClass.EQUITY_ORIENTED) {
                // OTHER, and specified debt bought before 2023-04-01 (its term is short/long, not slab).
                if (longTerm) {
                    otherLong = otherLong.add(pnl);
                } else {
                    otherShort = otherShort.add(pnl);
                }
                continue;
            }
            if (pnl.signum() >= 0) {
                if (longTerm) {
                    ltcg = ltcg.add(pnl);
                } else {
                    stcg = stcg.add(pnl);
                }
            } else if (longTerm) {
                ltcl = ltcl.add(pnl.negate());
            } else {
                stcl = stcl.add(pnl.negate());
            }
        }
        // 1. Intra-head netting: one short-term figure; long term per class.
        BigDecimal shortNet = stcg.subtract(stcl).add(otherShort).add(slab);
        BigDecimal equityLongNet = ltcg.subtract(ltcl);
        // 2. Short-term losses against short-term gains.
        BigDecimal netStcg = shortNet.max(BigDecimal.ZERO);
        BigDecimal stclLeft = shortNet.negate().max(BigDecimal.ZERO);
        // 3. Long-term losses against long-term gains, other class first.
        BigDecimal otherLtcg = otherLong.max(BigDecimal.ZERO);
        BigDecimal equityLtcg = equityLongNet.max(BigDecimal.ZERO);
        BigDecimal ltclLeft = otherLong.negate().max(BigDecimal.ZERO).add(equityLongNet.negate().max(BigDecimal.ZERO));
        BigDecimal used = ltclLeft.min(otherLtcg);
        otherLtcg = otherLtcg.subtract(used);
        ltclLeft = ltclLeft.subtract(used);
        used = ltclLeft.min(equityLtcg);
        equityLtcg = equityLtcg.subtract(used);
        ltclLeft = ltclLeft.subtract(used);
        // 4. Short-term losses left against the long-term gains left, other class first.
        used = stclLeft.min(otherLtcg);
        otherLtcg = otherLtcg.subtract(used);
        stclLeft = stclLeft.subtract(used);
        used = stclLeft.min(equityLtcg);
        equityLtcg = equityLtcg.subtract(used);
        stclLeft = stclLeft.subtract(used);
        // 5. The exemption on the equity-oriented part only.
        BigDecimal netLtcg = otherLtcg.add(equityLtcg);
        BigDecimal exemptionUsed = equityLtcg.min(exemptionLimit);
        return new HarvestRealised(money(stcg), money(stcl), money(ltcg), money(ltcl), money(netStcg), money(netLtcg),
                money(equityLtcg), money(stclLeft), money(ltclLeft), money(slab), money(exemptionLimit),
                money(exemptionUsed), money(exemptionLimit.subtract(exemptionUsed)), money(netLtcg.subtract(exemptionUsed)),
                new HarvestOtherGains(money(otherShort), money(otherLong), money(otherShort.add(otherLong))));
    }

    /** {@link #openLot(Holding, AssetClassifier.Classification, BigDecimal, HoldingTrace.OpenLot, LocalDate)} with the global class. */
    static HarvestOpenLot openLot(Holding holding, BigDecimal price, HoldingTrace.OpenLot lot, LocalDate today) {
        return openLot(holding, AssetClassifier.effective(holding.getInstrument()), price, lot, today);
    }

    static HarvestOpenLot openLot(Holding holding, AssetClassifier.Classification classification, BigDecimal price,
                                  HoldingTrace.OpenLot lot, LocalDate today) {
        return openLot(holding, holding.getInstrument().getName(), classification, price, lot, today);
    }

    /** The open lot with the instrument shown as {@code instrumentName} (the user's own name for it). */
    static HarvestOpenLot openLot(Holding holding, String instrumentName, AssetClassifier.Classification classification,
                                  BigDecimal price, HoldingTrace.OpenLot lot, LocalDate today) {
        TaxClass taxClass = classification.taxClass();
        BigDecimal cost = money(lot.cost());
        BigDecimal value = price != null ? money(lot.quantity().multiply(price)) : cost;
        String term = CapitalGainsTerm.term(taxClass, lot.buyDate(), today);
        LocalDate longTermOn = CapitalGainsTerm.longTermOn(taxClass, lot.buyDate());
        Integer daysToLongTerm = longTermOn == null ? null
                : (int) Math.max(0, ChronoUnit.DAYS.between(today, longTermOn));
        return new HarvestOpenLot(holding.getId(), holding.getInstrument().getId(), instrumentName,
                holding.getBrokerAccount() != null ? holding.getBrokerAccount().getName() : null,
                classification.assetClass(), taxClass, lot.buyDate(), lot.quantity().stripTrailingZeros(),
                lot.costPerUnit().setScale(4, RoundingMode.HALF_UP), cost, price, value, value.subtract(cost),
                term, longTermOn, daysToLongTerm, CapitalGainsTerm.grandfathered(taxClass, lot.buyDate()));
    }

    /**
     * What a holding's open lots (in the engine's FIFO order, oldest first) can book as long-term
     * equity gain: a sale always consumes the oldest lots first, so only a leading run of long-term
     * equity-oriented lots can be sold as LTCG, and the best amount is the largest running total of
     * their gains over that run (never below zero). E.g. lots of −10,000 then +50,000 → 40,000.
     */
    static BigDecimal harvestableLongTermEquityGain(List<HarvestOpenLot> fifoLots) {
        BigDecimal running = BigDecimal.ZERO;
        BigDecimal best = BigDecimal.ZERO;
        for (HarvestOpenLot lot : fifoLots) {
            if (lot.taxClass() != TaxClass.EQUITY_ORIENTED || !CapitalGainsTerm.LONG.equals(lot.term())) {
                break;
            }
            running = running.add(lot.gain());
            best = best.max(running);
        }
        return best;
    }

    /**
     * @param lotsByHolding each holding's open lots in FIFO order (oldest first)
     */
    static HarvestSummary summary(List<List<HarvestOpenLot>> lotsByHolding, BigDecimal exemptionLeft, LocalDate today) {
        BigDecimal longEquityGain = BigDecimal.ZERO;
        int soonCount = 0;
        BigDecimal soonGain = BigDecimal.ZERO;
        BigDecimal shortLoss = BigDecimal.ZERO;
        BigDecimal longLoss = BigDecimal.ZERO;
        LocalDate horizon = today.plusDays(TURNING_LONG_WITHIN_DAYS);
        for (List<HarvestOpenLot> holdingLots : lotsByHolding) {
            longEquityGain = longEquityGain.add(harvestableLongTermEquityGain(holdingLots));
            for (HarvestOpenLot lot : holdingLots) {
                boolean longTerm = CapitalGainsTerm.LONG.equals(lot.term());
                if (CapitalGainsTerm.SHORT.equals(lot.term()) && lot.longTermOn() != null
                        && !lot.longTermOn().isAfter(horizon)) {
                    soonCount++;
                    soonGain = soonGain.add(lot.gain());
                }
                if (lot.gain().signum() < 0) {
                    if (longTerm) {
                        longLoss = longLoss.add(lot.gain());
                    } else {
                        shortLoss = shortLoss.add(lot.gain());
                    }
                }
            }
        }
        return new HarvestSummary(money(longEquityGain.min(exemptionLeft)), money(longEquityGain),
                new HarvestTurningLongTerm(TURNING_LONG_WITHIN_DAYS, soonCount, money(soonGain)),
                new HarvestLosses(money(shortLoss), money(longLoss), money(shortLoss.add(longLoss))));
    }

    static HarvestOpenLotPage page(List<HarvestOpenLot> lots, int page, int size) {
        int total = lots.size();
        int totalPages = total == 0 ? 1 : (int) Math.ceil((double) total / size);
        int from = (int) Math.min((long) page * size, total);
        int to = Math.min(from + size, total);
        return new HarvestOpenLotPage(List.copyOf(lots.subList(from, to)), page, size, total, totalPages);
    }

    private static BigDecimal money(BigDecimal value) {
        return value.setScale(2, RoundingMode.HALF_UP);
    }
}
