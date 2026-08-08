package com.financeos.domain.report.datasource.impl;

import com.financeos.domain.holding.Holding;
import com.financeos.domain.holding.HoldingRepository;
import com.financeos.domain.instrument.InstrumentPrice;
import com.financeos.domain.instrument.InstrumentPriceRepository;
import com.financeos.domain.instrument.InstrumentType;
import com.financeos.domain.investment.InvestmentService;
import com.financeos.domain.investment.InvestmentService.Lot;
import com.financeos.domain.investment.InvestmentTransactionRepository;
import com.financeos.domain.report.ReportType;
import com.financeos.domain.report.datasource.Aggregation;
import com.financeos.domain.report.datasource.ComputedReportDatasource;
import com.financeos.domain.report.datasource.DatasourceCatalog.FieldDef;
import com.financeos.domain.report.datasource.FieldRole;
import com.financeos.domain.report.datasource.FieldType;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Computed report datasource generating month-end portfolio valuation series.
 *
 * <p>Caveats:
 * <ul>
 *   <li>Month-end snapshot series computed on the fly.</li>
 *   <li>Prices carry forward: uses the latest stored price on or before date d.</li>
 *   <li>Fallback when no price exists: uses the holding's average cost basis at date d.</li>
 *   <li>Quantities apply corporate actions up to each valuation date, including CA-seeded shares
 *       (demerger child / merger target); stored prices immediately around an ex-date may still be
 *       in pre-CA terms, so a step artifact near CA dates is possible.</li>
 * </ul>
 */
@Component
public class PortfolioValueDatasource implements ComputedReportDatasource {

    private static final List<ReportType> CHART_TABLE = List.of(ReportType.CHART, ReportType.TABLE);
    private static final List<ReportType> TABLE_ONLY = List.of(ReportType.TABLE);

    private final HoldingRepository holdingRepository;
    private final InvestmentTransactionRepository transactionRepository;
    private final InstrumentPriceRepository priceRepository;
    private final com.financeos.domain.instrument.corporateaction.CorporateActionRepository corporateActionRepository;
    private final InvestmentService investmentService;
    private final List<FieldDef> fields;

    public PortfolioValueDatasource(
            HoldingRepository holdingRepository,
            InvestmentTransactionRepository transactionRepository,
            InstrumentPriceRepository priceRepository,
            com.financeos.domain.instrument.corporateaction.CorporateActionRepository corporateActionRepository,
            InvestmentService investmentService) {
        this.holdingRepository = holdingRepository;
        this.transactionRepository = transactionRepository;
        this.priceRepository = priceRepository;
        this.corporateActionRepository = corporateActionRepository;
        this.investmentService = investmentService;
        this.fields = buildCatalog();
    }

    @Override
    public String name() {
        return "portfolio_value";
    }

    @Override
    public String label() {
        return "Portfolio Value";
    }

    @Override
    public List<FieldDef> fields() {
        return fields;
    }

    @Override
    public List<Map<String, Object>> rows() {
        List<Holding> holdings = holdingRepository.findAllWithDetails();
        if (holdings.isEmpty()) {
            return List.of();
        }

        // Preload price history for all involved instruments in ONE query
        List<java.util.UUID> instrumentIds = holdings.stream()
                .map(h -> h.getInstrument().getId())
                .distinct()
                .toList();
        List<InstrumentPrice> allPrices = priceRepository.findByInstrumentIdInOrderByAsOfAsc(instrumentIds);
        Map<java.util.UUID, List<InstrumentPrice>> priceMap = new LinkedHashMap<>();
        for (InstrumentPrice p : allPrices) {
            priceMap.computeIfAbsent(p.getInstrument().getId(), k -> new ArrayList<>()).add(p);
        }

        // Prefetch each holding's inputs once — the per-date lot builds below reuse them instead
        // of re-querying per (holding × month). Seed lots cover shares that arrived via a
        // demerger/merger corporate action rather than a buy transaction.
        Map<java.util.UUID, List<com.financeos.domain.investment.InvestmentTransaction>> txnsByHolding = new LinkedHashMap<>();
        Map<java.util.UUID, List<com.financeos.domain.instrument.corporateaction.CorporateAction>> casByHolding = new LinkedHashMap<>();
        Map<java.util.UUID, List<InvestmentService.SeedLot>> seedsByHolding = new LinkedHashMap<>();
        LocalDate earliestDate = null;
        for (Holding h : holdings) {
            var txns = transactionRepository.findByHoldingIdOrderByTradeDateAscCreatedAtAsc(h.getId());
            txnsByHolding.put(h.getId(), txns);
            casByHolding.put(h.getId(),
                    corporateActionRepository.findByInstrumentIdOrderByExDateAsc(h.getInstrument().getId()));
            List<InvestmentService.SeedLot> seeds = investmentService.seedLotsFor(h);
            seedsByHolding.put(h.getId(), seeds);
            if (!txns.isEmpty()) {
                LocalDate d = txns.get(0).getTradeDate();
                if (earliestDate == null || d.isBefore(earliestDate)) {
                    earliestDate = d;
                }
            }
            for (InvestmentService.SeedLot seed : seeds) {
                if (earliestDate == null || seed.date().isBefore(earliestDate)) {
                    earliestDate = seed.date();
                }
            }
        }
        if (earliestDate == null) {
            return List.of();
        }

        LocalDate today = LocalDate.now();
        List<LocalDate> valueDates = new ArrayList<>();
        LocalDate currentMonthEnd = earliestDate.withDayOfMonth(1).plusMonths(1).minusDays(1);
        while (currentMonthEnd.isBefore(today.withDayOfMonth(1))) {
            valueDates.add(currentMonthEnd);
            currentMonthEnd = currentMonthEnd.plusMonths(1).withDayOfMonth(1).plusMonths(1).minusDays(1);
        }
        valueDates.add(today);

        List<Map<String, Object>> rows = new ArrayList<>();
        int rowIdx = 0;

        for (LocalDate d : valueDates) {
            for (Holding holding : holdings) {
                List<Lot> openLots = investmentService.buildOpenLotsBeforeDate(holding, d, false, null,
                        txnsByHolding.get(holding.getId()), casByHolding.get(holding.getId()),
                        seedsByHolding.get(holding.getId()));
                BigDecimal totalQty = BigDecimal.ZERO;
                BigDecimal totalCost = BigDecimal.ZERO;
                for (Lot lot : openLots) {
                    totalQty = totalQty.add(lot.remainingQty);
                    totalCost = totalCost.add(lot.remainingQty.multiply(lot.costPerUnit));
                }

                if (totalQty.compareTo(BigDecimal.ZERO) > 0) {
                    BigDecimal price = findLatestPriceOnOrBefore(priceMap.get(holding.getInstrument().getId()), d);
                    BigDecimal val = price != null ? totalQty.multiply(price) : totalCost;

                    Map<String, Object> map = new LinkedHashMap<>();
                    map.put("id", holding.getId() + "_" + rowIdx++);
                    map.put("valueDate", d);
                    map.put("value", val.setScale(2, RoundingMode.HALF_UP));
                    map.put("broker", holding.getBrokerAccount().getName());
                    map.put("instrumentType", holding.getInstrument().getType().name());
                    map.put("instrument", holding.getInstrument().getName());

                    rows.add(map);
                }
            }
        }

        return rows;
    }

    private BigDecimal findLatestPriceOnOrBefore(List<InstrumentPrice> prices, LocalDate date) {
        if (prices == null || prices.isEmpty()) {
            return null;
        }
        BigDecimal lastPrice = null;
        for (InstrumentPrice p : prices) {
            if (p.getAsOf() != null && !p.getAsOf().isAfter(date)) {
                lastPrice = p.getClose();
            } else if (p.getAsOf() != null && p.getAsOf().isAfter(date)) {
                break;
            }
        }
        return lastPrice;
    }

    private List<FieldDef> buildCatalog() {
        List<String> instTypeValues = Arrays.stream(InstrumentType.values()).map(Enum::name).toList();

        return List.of(
                new FieldDef("valueDate", "Value Date", FieldType.DATE, FieldRole.DIMENSION, null, null, null, CHART_TABLE),
                new FieldDef("value", "Portfolio Value", FieldType.NUMBER, FieldRole.MEASURE, List.of(Aggregation.SUM), null, null, CHART_TABLE, "currency"),
                new FieldDef("broker", "Broker", FieldType.ENUM, FieldRole.DIMENSION, null, null, true, CHART_TABLE),
                new FieldDef("instrumentType", "Instrument Type", FieldType.ENUM, FieldRole.DIMENSION, null, instTypeValues, null, CHART_TABLE),
                new FieldDef("instrument", "Instrument", FieldType.ENUM, FieldRole.DIMENSION, null, null, true, TABLE_ONLY)
        );
    }
}
