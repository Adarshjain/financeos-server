package com.financeos.domain.investment.reconcile;

import com.financeos.api.instrument.dto.InstrumentCandidate;
import com.financeos.api.instrument.dto.InstrumentResponse;
import com.financeos.api.instrument.dto.ResolveInstrumentRequest;
import com.financeos.api.investment.dto.*;
import com.financeos.api.investment.dto.ImportCommitRequest.CreateInstrumentDto;
import com.financeos.core.exception.ResourceNotFoundException;
import com.financeos.core.exception.ValidationException;
import com.financeos.core.security.UserContext;
import com.financeos.domain.account.Account;
import com.financeos.domain.account.AccountRepository;
import com.financeos.domain.account.AccountType;
import com.financeos.domain.holding.Holding;
import com.financeos.domain.holding.HoldingRepository;
import com.financeos.domain.instrument.*;
import com.financeos.domain.instrument.price.PriceRefreshEvent;
import com.financeos.domain.instrument.search.InstrumentSearchService;
import com.financeos.domain.investment.InvestmentTransaction;
import com.financeos.domain.investment.InvestmentTransactionRepository;
import com.financeos.domain.investment.InvestmentTransactionType;
import com.financeos.domain.investment.SettlementType;
import com.financeos.domain.investment.TradeSettlementClassification;
import com.financeos.domain.investment.TradeSettlementClassificationRepository;
import com.financeos.domain.investment.charges.ChargeCalculator;
import com.financeos.domain.investment.imports.ImportParser;
import com.financeos.domain.investment.imports.ParseContext;
import com.financeos.domain.investment.imports.ParsedRow;
import com.financeos.domain.investment.imports.ZerodhaTradebookParser;
import com.financeos.domain.user.User;
import com.financeos.domain.user.UserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.InputStream;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.*;

@Service
@Transactional
public class BrokerReconciliationService {

    private static final Logger log = LoggerFactory.getLogger(BrokerReconciliationService.class);

    private final ZerodhaTradebookParser zerodhaTradebookParser;
    private final ZerodhaTaxPnlParser zerodhaTaxPnlParser;
    private final GrowwOrderHistoryParser growwOrderHistoryParser;
    private final GrowwCapitalGainsParser growwCapitalGainsParser;
    private final ChargeCalculator chargeCalculator;
    private final InstrumentRepository instrumentRepository;
    private final InstrumentAliasRepository aliasRepository;
    private final HoldingRepository holdingRepository;
    private final InvestmentTransactionRepository transactionRepository;
    private final AccountRepository accountRepository;
    private final UserRepository userRepository;
    private final InstrumentSearchService instrumentSearchService;
    private final HoldingsSnapshotParser holdingsSnapshotParser;
    private final TradeSettlementClassificationRepository classificationRepository;
    private final ApplicationEventPublisher eventPublisher;

    public BrokerReconciliationService(
            ZerodhaTradebookParser zerodhaTradebookParser,
            ZerodhaTaxPnlParser zerodhaTaxPnlParser,
            GrowwOrderHistoryParser growwOrderHistoryParser,
            GrowwCapitalGainsParser growwCapitalGainsParser,
            ChargeCalculator chargeCalculator,
            InstrumentRepository instrumentRepository,
            InstrumentAliasRepository aliasRepository,
            HoldingRepository holdingRepository,
            InvestmentTransactionRepository transactionRepository,
            AccountRepository accountRepository,
            UserRepository userRepository,
            InstrumentSearchService instrumentSearchService,
            HoldingsSnapshotParser holdingsSnapshotParser,
            TradeSettlementClassificationRepository classificationRepository,
            ApplicationEventPublisher eventPublisher
    ) {
        this.zerodhaTradebookParser = zerodhaTradebookParser;
        this.zerodhaTaxPnlParser = zerodhaTaxPnlParser;
        this.growwOrderHistoryParser = growwOrderHistoryParser;
        this.growwCapitalGainsParser = growwCapitalGainsParser;
        this.chargeCalculator = chargeCalculator;
        this.instrumentRepository = instrumentRepository;
        this.aliasRepository = aliasRepository;
        this.holdingRepository = holdingRepository;
        this.transactionRepository = transactionRepository;
        this.accountRepository = accountRepository;
        this.userRepository = userRepository;
        this.instrumentSearchService = instrumentSearchService;
        this.holdingsSnapshotParser = holdingsSnapshotParser;
        this.classificationRepository = classificationRepository;
        this.eventPublisher = eventPublisher;
    }

    private record InternalExecution(
            String isin,
            String symbol,
            LocalDate date,
            InvestmentTransactionType type,
            BigDecimal qty,
            BigDecimal price,
            BigDecimal value,
            String exchange,
            String orderId,
            String tradeId,
            String execTime
    ) {}

    private record IntradayAgg(BigDecimal qty, BigDecimal buyValue, BigDecimal sellValue) {}

    public ReconcilePreviewResponse preview(
            Broker broker,
            UUID brokerAccountId,
            List<InputStream> tradebookStreams,
            List<InputStream> taxpnlStreams
    ) {
        return preview(broker, brokerAccountId, tradebookStreams, taxpnlStreams, null, null);
    }

    public ReconcilePreviewResponse preview(
            Broker broker,
            UUID brokerAccountId,
            List<InputStream> tradebookStreams,
            List<InputStream> taxpnlStreams,
            InputStream holdingsSnapshotStream,
            String holdingsFilename
    ) {
        Account brokerAccount = accountRepository.findById(brokerAccountId)
                .orElseThrow(() -> new ResourceNotFoundException("Account", brokerAccountId));

        if (brokerAccount.getType() != AccountType.broker) {
            throw new ValidationException("Account must be a broker account");
        }

        // 1. Parse raw executions
        List<InternalExecution> rawExecs = new ArrayList<>();
        if (broker == Broker.zerodha) {
            for (InputStream is : tradebookStreams) {
                List<ParsedRow> rows = zerodhaTradebookParser.parse(is, new ParseContext(brokerAccountId, null));
                for (ParsedRow r : rows) {
                    if (r.error() == null && r.type() != null && r.quantity() != null && r.price() != null && r.tradeDate() != null) {
                        rawExecs.add(new InternalExecution(
                                r.parsedIsin(),
                                r.parsedSymbol(),
                                r.tradeDate(),
                                r.type(),
                                r.quantity(),
                                r.price(),
                                r.quantity().multiply(r.price()),
                                r.exchange() != null ? r.exchange() : "NSE",
                                r.rawData() != null ? r.rawData().get("order_id") : null,
                                r.rawData() != null ? r.rawData().get("trade_id") : null,
                                r.rawData() != null ? r.rawData().get("order_execution_time") : null
                        ));
                    }
                }
            }
        } else if (broker == Broker.groww) {
            for (InputStream is : tradebookStreams) {
                List<GrowwOrderHistoryParser.GrowwExecution> rows = growwOrderHistoryParser.parse(is);
                for (GrowwOrderHistoryParser.GrowwExecution r : rows) {
                    if (r.tradeDate() == null || r.quantity() == null || r.price() == null) {
                        continue; // skip unparseable rows (e.g. bad date) instead of crashing downstream
                    }
                    rawExecs.add(new InternalExecution(
                            r.isin(),
                            r.symbol(),
                            r.tradeDate(),
                            r.type(),
                            r.quantity(),
                            r.price(),
                            r.value(),
                            r.exchange(),
                            r.orderId(),
                            "",
                            r.execTime()
                    ));
                }
            }
        }

        // 2. Deduplicate executions by full-row identity
        List<InternalExecution> execs = dedupeExecutions(rawExecs);

        // 3. Parse classifier files
        Map<String, Map<LocalDate, IntradayAgg>> intradayMap = new HashMap<>(); // ISIN -> (Date -> Agg)
        Map<String, Map<LocalDate, ItemizedChargesDto>> zerodhaExitCharges = new HashMap<>(); // ISIN -> (Date -> Charges)
        List<ReconcilePreviewResponse.ReconcileWarningDto> warnings = new ArrayList<>();

        BigDecimal classifierDeliveryRealized = BigDecimal.ZERO;
        BigDecimal classifierIntradayRealized = BigDecimal.ZERO;

        // C3: per-scrip authoritative DELIVERY sold qty/value from the classifier
        // (STCG + LTCG + Buyback). Compared later to the tradebook-derived sold qty to
        // detect off-market removals (buyback / merger / delisting) that never appear in
        // the tradebook as a sell and would otherwise leave holdings overstated.
        Map<String, BigDecimal> classifierDelivSoldQty = new HashMap<>();
        Map<String, BigDecimal> classifierDelivSoldValue = new HashMap<>();
        Map<String, LocalDate> classifierLatestExitDate = new HashMap<>();

        if (broker == Broker.zerodha) {
            for (InputStream is : taxpnlStreams) {
                List<ZerodhaTaxPnlParser.TaxPnlExit> exits = zerodhaTaxPnlParser.parse(is);
                for (ZerodhaTaxPnlParser.TaxPnlExit x : exits) {
                    if (x.bucket() == null) continue;
                    String isinKey = x.isin() != null ? x.isin() : x.symbol();

                    if ("INTRADAY".equalsIgnoreCase(x.bucket())) {
                        LocalDate exitDate = x.exitDate() != null ? x.exitDate() : x.entryDate();
                        if (exitDate != null) {
                            intradayMap.computeIfAbsent(isinKey, k -> new HashMap<>())
                                    .merge(exitDate,
                                            new IntradayAgg(x.quantity(), x.buyValue(), x.sellValue()),
                                            (a, b) -> new IntradayAgg(
                                                    a.qty.add(b.qty),
                                                    a.buyValue.add(b.buyValue),
                                                    a.sellValue.add(b.sellValue)
                                            )
                                    );
                        }
                        classifierIntradayRealized = classifierIntradayRealized.add(x.profit());
                    } else if ("STCG".equalsIgnoreCase(x.bucket()) || "LTCG".equalsIgnoreCase(x.bucket())) {
                        if (x.exitDate() != null && x.charges() != null) {
                            zerodhaExitCharges.computeIfAbsent(isinKey, k -> new HashMap<>())
                                    .put(x.exitDate(), x.charges());
                        }
                        accumulateClassifierSold(classifierDelivSoldQty, classifierDelivSoldValue,
                                classifierLatestExitDate, isinKey, x.quantity(), x.sellValue(), x.exitDate());
                        classifierDeliveryRealized = classifierDeliveryRealized.add(x.profit());
                    } else if ("BUYBACK".equalsIgnoreCase(x.bucket())) {
                        // Buyback shares leave the demat off-market and are NOT in the tradebook;
                        // count them as a delivery removal so holdings get reduced (see C3 below).
                        accumulateClassifierSold(classifierDelivSoldQty, classifierDelivSoldValue,
                                classifierLatestExitDate, isinKey, x.quantity(), x.sellValue(), x.exitDate());
                        classifierDeliveryRealized = classifierDeliveryRealized.add(x.profit());
                        warnings.add(new ReconcilePreviewResponse.ReconcileWarningDto(
                                "BUYBACK_EXIT", "INFO", x.isin(), x.symbol(),
                                String.format("Buyback exit: %s shares of %s on %s — treated as an off-market delivery removal.",
                                        x.quantity().toPlainString(), x.symbol(), x.exitDate())
                        ));
                    }
                }
            }
        } else if (broker == Broker.groww) {
            for (InputStream is : taxpnlStreams) {
                List<GrowwCapitalGainsParser.GrowwCapitalGainsExit> exits = growwCapitalGainsParser.parse(is);
                for (GrowwCapitalGainsParser.GrowwCapitalGainsExit x : exits) {
                    if (x.bucket() == null) continue;
                    String isinKey = x.isin() != null ? x.isin() : x.stockName();

                    if ("INTRADAY".equalsIgnoreCase(x.bucket())) {
                        LocalDate exitDate = x.sellDate() != null ? x.sellDate() : x.buyDate();
                        if (exitDate != null) {
                            intradayMap.computeIfAbsent(isinKey, k -> new HashMap<>())
                                    .merge(exitDate,
                                            new IntradayAgg(x.quantity(), x.buyValue(), x.sellValue()),
                                            (a, b) -> new IntradayAgg(
                                                    a.qty.add(b.qty),
                                                    a.buyValue.add(b.buyValue),
                                                    a.sellValue.add(b.sellValue)
                                            )
                                    );
                        }
                        classifierIntradayRealized = classifierIntradayRealized.add(x.realisedPnl());
                    } else if ("STCG".equalsIgnoreCase(x.bucket()) || "LTCG".equalsIgnoreCase(x.bucket())) {
                        accumulateClassifierSold(classifierDelivSoldQty, classifierDelivSoldValue,
                                classifierLatestExitDate, isinKey, x.quantity(), x.sellValue(), x.sellDate());
                        classifierDeliveryRealized = classifierDeliveryRealized.add(x.realisedPnl());
                    } else if ("BUYBACK".equalsIgnoreCase(x.bucket())) {
                        accumulateClassifierSold(classifierDelivSoldQty, classifierDelivSoldValue,
                                classifierLatestExitDate, isinKey, x.quantity(), x.sellValue(), x.sellDate());
                        warnings.add(new ReconcilePreviewResponse.ReconcileWarningDto(
                                "BUYBACK_EXIT", "INFO", x.isin(), x.stockName(),
                                String.format("Buyback exit: %s shares of %s on %s — treated as an off-market delivery removal.",
                                        x.quantity().toPlainString(), x.stockName(), x.sellDate())
                        ));
                    }
                }
            }
        }

        // 4. Classify executions & calculate charges
        // Map per (ISIN, Date) to split buys/sells into Intraday vs Delivery
        Map<String, List<InternalExecution>> execsByScrip = new HashMap<>();
        for (InternalExecution e : execs) {
            String key = e.isin != null ? e.isin : e.symbol;
            execsByScrip.computeIfAbsent(key, k -> new ArrayList<>()).add(e);
        }

        List<ReconcilePreviewResponse.ReconciledExecutionDto> classifiedDtos = new ArrayList<>();
        Map<String, List<DeliveryEvent>> deliveryEventsPerScrip = new HashMap<>();
        BigDecimal computedIntradayRealized = BigDecimal.ZERO;
        BigDecimal totalChargesAcc = BigDecimal.ZERO;

        int rowIndexCounter = 1;
        int deliveryCount = 0;
        int intradayCount = 0;
        int duplicateCount = 0;

        // Scrip symbol map
        Map<String, String> symbolOfKey = new HashMap<>();
        Map<String, String> isinOfKey = new HashMap<>();

        for (Map.Entry<String, List<InternalExecution>> entry : execsByScrip.entrySet()) {
            String scripKey = entry.getKey();
            List<InternalExecution> scripExecs = entry.getValue();

            // Group by trade date
            Map<LocalDate, List<InternalExecution>> dayMap = new HashMap<>();
            for (InternalExecution e : scripExecs) {
                if (e.symbol != null) symbolOfKey.putIfAbsent(scripKey, e.symbol);
                if (e.isin != null) isinOfKey.putIfAbsent(scripKey, e.isin);
                dayMap.computeIfAbsent(e.date, k -> new ArrayList<>()).add(e);
            }

            for (Map.Entry<LocalDate, List<InternalExecution>> dayEntry : dayMap.entrySet()) {
                LocalDate date = dayEntry.getKey();
                List<InternalExecution> dayExecs = dayEntry.getValue();

                IntradayAgg intraAgg = intradayMap.getOrDefault(scripKey, Collections.emptyMap()).get(date);
                BigDecimal targetIntradayQty = intraAgg != null ? intraAgg.qty : BigDecimal.ZERO;

                BigDecimal dayBuyQty = BigDecimal.ZERO;
                BigDecimal dayBuyVal = BigDecimal.ZERO;
                BigDecimal daySellQty = BigDecimal.ZERO;
                BigDecimal daySellVal = BigDecimal.ZERO;

                for (InternalExecution e : dayExecs) {
                    if (e.type == InvestmentTransactionType.buy) {
                        dayBuyQty = dayBuyQty.add(e.qty);
                        dayBuyVal = dayBuyVal.add(e.value);
                    } else {
                        daySellQty = daySellQty.add(e.qty);
                        daySellVal = daySellVal.add(e.value);
                    }
                }

                // ---- Per-execution settlement classification (greedy, split at the boundary) ----
                // The day's authoritative intraday quantity (targetIntradayQty) is allocated across
                // each side's executions in tradebook order. An execution that straddles the
                // intraday/delivery boundary is SPLIT into an intraday row and a delivery row so
                // every row is wholly one settlement type; the per-execution badges then sum exactly
                // to the classifier's intraday qty. The old proportional rule flagged whole intraday
                // buys as delivery on any day that also had delivery activity in the same scrip.
                BigDecimal remainingIntradayBuy = targetIntradayQty;
                BigDecimal remainingIntradaySell = targetIntradayQty;
                for (InternalExecution e : dayExecs) {
                    boolean isBuy = e.type == InvestmentTransactionType.buy;
                    BigDecimal remaining = isBuy ? remainingIntradayBuy : remainingIntradaySell;
                    BigDecimal intraQty = remaining.min(e.qty).max(BigDecimal.ZERO);
                    if (isBuy) {
                        remainingIntradayBuy = remainingIntradayBuy.subtract(intraQty).max(BigDecimal.ZERO);
                    } else {
                        remainingIntradaySell = remainingIntradaySell.subtract(intraQty).max(BigDecimal.ZERO);
                    }
                    BigDecimal delivQty = e.qty.subtract(intraQty);
                    boolean split = intraQty.signum() > 0 && delivQty.signum() > 0;

                    ReconcilePreviewResponse.MatchedInstrumentDto matchedInst = resolveInstrumentLocally(e.isin, e.symbol, e.exchange);
                    boolean isDup = checkDuplicateInDb(brokerAccountId, matchedInst != null ? matchedInst.id() : null, e);
                    if (isDup) duplicateCount++;
                    String baseRef = (e.tradeId != null && !e.tradeId.isBlank()) ? e.tradeId : e.orderId;

                    // Intraday portion
                    if (intraQty.signum() > 0) {
                        ItemizedChargesDto charges = (broker == Broker.groww)
                                ? chargeCalculator.calculateGrowwCharges(e.type, SettlementType.intraday, intraQty, e.price, e.date, e.exchange)
                                : ItemizedChargesDto.empty();
                        totalChargesAcc = totalChargesAcc.add(calculateTotalCharges(charges));
                        classifiedDtos.add(new ReconcilePreviewResponse.ReconciledExecutionDto(
                                rowIndexCounter++, e.date, e.type, SettlementType.intraday,
                                e.symbol, e.isin, e.exchange, intraQty, e.price, intraQty.multiply(e.price),
                                charges, split ? baseRef + "-I" : baseRef, matchedInst, isDup,
                                split ? "Intraday portion of a split execution" : null));
                        intradayCount++;
                    }

                    // Delivery portion
                    if (delivQty.signum() > 0) {
                        ItemizedChargesDto charges;
                        if (broker == Broker.groww) {
                            charges = chargeCalculator.calculateGrowwCharges(e.type, SettlementType.delivery, delivQty, e.price, e.date, e.exchange);
                        } else if (e.type == InvestmentTransactionType.sell) {
                            // Zerodha: attach Tax P&L exit charges to the delivery SELL portion
                            ItemizedChargesDto exitCharges = zerodhaExitCharges.getOrDefault(scripKey, Collections.emptyMap()).get(date);
                            charges = exitCharges != null ? exitCharges : ItemizedChargesDto.empty();
                        } else {
                            charges = ItemizedChargesDto.empty();
                        }
                        totalChargesAcc = totalChargesAcc.add(calculateTotalCharges(charges));
                        classifiedDtos.add(new ReconcilePreviewResponse.ReconciledExecutionDto(
                                rowIndexCounter++, e.date, e.type, SettlementType.delivery,
                                e.symbol, e.isin, e.exchange, delivQty, e.price, delivQty.multiply(e.price),
                                charges, split ? baseRef + "-D" : baseRef, matchedInst, isDup,
                                split ? "Delivery portion of a split execution" : null));
                        deliveryCount++;
                    }
                }

                // C1: build delivery events at the DAY level via value subtraction, exactly
                // like InvestmentService — NOT per raw execution. The per-execution
                // settlementType above is a display hint only; holdings must be derived the
                // same way here as after commit, or the preview lies on mixed straddle days.
                BigDecimal intraBuyVal = intraAgg != null ? intraAgg.buyValue : BigDecimal.ZERO;
                BigDecimal intraSellVal = intraAgg != null ? intraAgg.sellValue : BigDecimal.ZERO;
                BigDecimal delivBuyQty = dayBuyQty.subtract(targetIntradayQty).max(BigDecimal.ZERO);
                BigDecimal delivBuyVal = dayBuyVal.subtract(intraBuyVal).max(BigDecimal.ZERO);
                BigDecimal delivSellQty = daySellQty.subtract(targetIntradayQty).max(BigDecimal.ZERO);
                BigDecimal delivSellVal = daySellVal.subtract(intraSellVal).max(BigDecimal.ZERO);

                if (delivBuyQty.compareTo(BigDecimal.ZERO) > 0) {
                    deliveryEventsPerScrip.computeIfAbsent(scripKey, k -> new ArrayList<>())
                            .add(new DeliveryEvent(date, InvestmentTransactionType.buy, delivBuyQty, delivBuyVal));
                }
                if (delivSellQty.compareTo(BigDecimal.ZERO) > 0) {
                    deliveryEventsPerScrip.computeIfAbsent(scripKey, k -> new ArrayList<>())
                            .add(new DeliveryEvent(date, InvestmentTransactionType.sell, delivSellQty, delivSellVal));
                }

                // I5: the broker's intraday qty must fit within that day's tradebook buys AND
                // sells; if not, the classifier and tradebook disagree — surface it instead of
                // silently clamping to zero.
                if (targetIntradayQty.compareTo(BigDecimal.ZERO) > 0
                        && (targetIntradayQty.compareTo(dayBuyQty) > 0 || targetIntradayQty.compareTo(daySellQty) > 0)) {
                    warnings.add(new ReconcilePreviewResponse.ReconcileWarningDto(
                            "CLASSIFIER_MISMATCH", "WARNING", isinOfKey.get(scripKey),
                            symbolOfKey.getOrDefault(scripKey, scripKey),
                            String.format("Intraday qty %s on %s exceeds tradebook buys (%s) or sells (%s) — classifier/tradebook mismatch.",
                                    targetIntradayQty.toPlainString(), date, dayBuyQty.toPlainString(), daySellQty.toPlainString())
                    ));
                }

                // Compute intraday realized for day
                if (targetIntradayQty.compareTo(BigDecimal.ZERO) > 0 && intraAgg != null) {
                    computedIntradayRealized = computedIntradayRealized.add(intraAgg.sellValue.subtract(intraAgg.buyValue));
                }
            }
        }

        // 5. Delivery FIFO -> Open Holdings & Realized P&L & Gap Detection
        List<ReconcilePreviewResponse.DerivedHoldingDto> derivedHoldings = new ArrayList<>();
        BigDecimal computedDeliveryRealized = BigDecimal.ZERO;

        for (Map.Entry<String, List<DeliveryEvent>> entry : deliveryEventsPerScrip.entrySet()) {
            String scripKey = entry.getKey();
            List<DeliveryEvent> events = entry.getValue();
            events.sort(Comparator.comparing(DeliveryEvent::date));

            LinkedList<FifoLot> openLots = new LinkedList<>();
            BigDecimal shortSoldQty = BigDecimal.ZERO;
            BigDecimal tradebookSoldQty = BigDecimal.ZERO;

            for (DeliveryEvent ev : events) {
                if (ev.type == InvestmentTransactionType.buy) {
                    openLots.add(new FifoLot(ev.qty, ev.value.divide(ev.qty, 8, RoundingMode.HALF_UP)));
                } else {
                    tradebookSoldQty = tradebookSoldQty.add(ev.qty);
                    BigDecimal[] m = fifoMatch(openLots, ev.qty);
                    if (m[1].compareTo(new BigDecimal("0.000001")) > 0) {
                        shortSoldQty = shortSoldQty.add(m[1]);
                    }
                    computedDeliveryRealized = computedDeliveryRealized.add(ev.value.subtract(m[0]));
                }
            }

            // C3: off-market removal (buyback / merger / delisting). The classifier's
            // authoritative delivery-sold qty can exceed what the tradebook recorded as sells,
            // because tendered/merged shares leave the demat off-market with no tradebook row.
            // Inject a synthetic delivery SELL for the shortfall so holdings are reduced here
            // AND (via classifiedDtos -> commit) in InvestmentService. Otherwise holdings stay
            // overstated for those scrips.
            BigDecimal classifierSold = classifierDelivSoldQty.getOrDefault(scripKey, BigDecimal.ZERO);
            BigDecimal offMarketQty = classifierSold.subtract(tradebookSoldQty);
            BigDecimal openBeforeOffMarket = currentOpenQty(openLots);
            if (offMarketQty.compareTo(new BigDecimal("0.000001")) > 0 && openBeforeOffMarket.compareTo(BigDecimal.ZERO) > 0) {
                BigDecimal removeQty = offMarketQty.min(openBeforeOffMarket);
                BigDecimal soldVal = classifierDelivSoldValue.getOrDefault(scripKey, BigDecimal.ZERO);
                BigDecimal classifierAvgExit = classifierSold.compareTo(BigDecimal.ZERO) > 0
                        ? soldVal.divide(classifierSold, 8, RoundingMode.HALF_UP) : BigDecimal.ZERO;
                BigDecimal exitValue = removeQty.multiply(classifierAvgExit);
                BigDecimal[] m = fifoMatch(openLots, removeQty);
                computedDeliveryRealized = computedDeliveryRealized.add(exitValue.subtract(m[0]));

                LocalDate exitDate = classifierLatestExitDate.getOrDefault(scripKey, LocalDate.of(2100, 1, 1));
                String sym = symbolOfKey.getOrDefault(scripKey, scripKey);
                String isin = isinOfKey.get(scripKey);
                ReconcilePreviewResponse.MatchedInstrumentDto matchedInst = resolveInstrumentLocally(isin, sym, "NSE");
                classifiedDtos.add(new ReconcilePreviewResponse.ReconciledExecutionDto(
                        rowIndexCounter++, exitDate, InvestmentTransactionType.sell, SettlementType.delivery,
                        sym, isin, "NSE", removeQty, classifierAvgExit, exitValue,
                        ItemizedChargesDto.empty(), "CA_EXIT_" + (isin != null ? isin : sym) + "_" + exitDate,
                        matchedInst, false, "Off-market delivery removal (buyback/merger) — synthesized; not in tradebook"));
                warnings.add(new ReconcilePreviewResponse.ReconcileWarningDto(
                        "OFF_MARKET_EXIT", "WARNING", isin, sym,
                        String.format("%s shares of %s left the demat off-market (buyback/merger); holdings reduced via a synthetic delivery sell.",
                                removeQty.setScale(0, RoundingMode.HALF_UP).toPlainString(), sym)));
            }

            BigDecimal openQty = BigDecimal.ZERO;
            BigDecimal openCostVal = BigDecimal.ZERO;
            for (FifoLot lot : openLots) {
                openQty = openQty.add(lot.remainingQty);
                openCostVal = openCostVal.add(lot.remainingQty.multiply(lot.costPerUnit));
            }

            if (shortSoldQty.compareTo(new BigDecimal("0.000001")) > 0) {
                String sym = symbolOfKey.getOrDefault(scripKey, scripKey);
                warnings.add(new ReconcilePreviewResponse.ReconcileWarningDto(
                        "DATA_GAP",
                        "WARNING",
                        isinOfKey.get(scripKey),
                        sym,
                        String.format("Incomplete tradebook history: %s shares of %s sold with no prior buy. Holdings may be inaccurate until earlier tradebooks are exported.",
                                shortSoldQty.setScale(0, RoundingMode.HALF_UP).toPlainString(), sym)
                ));
            }

            if (openQty.compareTo(new BigDecimal("0.000001")) > 0) {
                BigDecimal avgCost = openCostVal.divide(openQty, 4, RoundingMode.HALF_UP);
                String sym = symbolOfKey.getOrDefault(scripKey, scripKey);
                String isin = isinOfKey.get(scripKey);
                ReconcilePreviewResponse.MatchedInstrumentDto matchedInst = resolveInstrumentLocally(isin, sym, "NSE");

                derivedHoldings.add(new ReconcilePreviewResponse.DerivedHoldingDto(
                        matchedInst != null ? matchedInst.id() : null,
                        sym,
                        isin,
                        matchedInst != null ? matchedInst.name() : sym,
                        openQty.setScale(4, RoundingMode.HALF_UP),
                        avgCost,
                        openCostVal.setScale(2, RoundingMode.HALF_UP)
                ));
            }
        }

        // Sort holdings by cost value descending
        derivedHoldings.sort(Comparator.comparing(ReconcilePreviewResponse.DerivedHoldingDto::costValue).reversed());

        // Instrument resolution check
        Set<String> unresolvedScrips = new HashSet<>();
        for (ReconcilePreviewResponse.ReconciledExecutionDto dto : classifiedDtos) {
            if (dto.matchedInstrument() == null) {
                String scripStr = dto.symbol() != null ? dto.symbol() : dto.isin();
                if (scripStr != null) unresolvedScrips.add(scripStr);
            }
        }
        for (String unres : unresolvedScrips) {
            warnings.add(new ReconcilePreviewResponse.ReconcileWarningDto(
                    "UNRESOLVED_INSTRUMENT",
                    "WARNING",
                    null,
                    unres,
                    "Instrument " + unres + " is not mapped to catalog. Map or auto-create before importing."
            ));
        }

        // Build classifications DTO list
        List<ReconcilePreviewResponse.TradeSettlementClassificationDto> classificationDtos = new ArrayList<>();
        for (Map.Entry<String, Map<LocalDate, IntradayAgg>> isinEntry : intradayMap.entrySet()) {
            String isinKey = isinEntry.getKey();
            for (Map.Entry<LocalDate, IntradayAgg> dateEntry : isinEntry.getValue().entrySet()) {
                LocalDate date = dateEntry.getKey();
                IntradayAgg agg = dateEntry.getValue();
                classificationDtos.add(new ReconcilePreviewResponse.TradeSettlementClassificationDto(
                        isinKey.startsWith("INE") || isinKey.startsWith("IN") ? isinKey : null,
                        isinKey.startsWith("INE") || isinKey.startsWith("IN") ? null : isinKey,
                        date,
                        agg.qty,
                        agg.buyValue,
                        agg.sellValue
                ));
            }
        }

        // Holdings Snapshot Validation Anchor
        if (holdingsSnapshotStream != null && holdingsSnapshotParser != null) {
            List<HoldingsSnapshotParser.SnapshotItem> snapshotItems = holdingsSnapshotParser.parse(holdingsSnapshotStream, holdingsFilename);
            if (!snapshotItems.isEmpty()) {
                Map<String, HoldingsSnapshotParser.SnapshotItem> snapshotMap = new HashMap<>();
                for (HoldingsSnapshotParser.SnapshotItem item : snapshotItems) {
                    if (item.isin() != null && !item.isin().isBlank()) snapshotMap.put(item.isin().trim(), item);
                    if (item.symbol() != null && !item.symbol().isBlank()) snapshotMap.put(item.symbol().trim(), item);
                }
                for (ReconcilePreviewResponse.DerivedHoldingDto dh : derivedHoldings) {
                    String key = dh.isin() != null ? dh.isin().trim() : dh.symbol();
                    HoldingsSnapshotParser.SnapshotItem snap = snapshotMap.get(key);
                    if (snap != null && snap.quantity() != null && dh.quantity().compareTo(snap.quantity()) != 0) {
                        warnings.add(new ReconcilePreviewResponse.ReconcileWarningDto(
                                "DATA_GAP",
                                "WARNING",
                                dh.isin(),
                                dh.symbol(),
                                String.format("Holdings snapshot mismatch for %s: Derived FIFO qty %s vs Demat snapshot qty %s. Demat snapshot is demat truth.",
                                        dh.symbol(), dh.quantity().toPlainString(), snap.quantity().toPlainString())
                        ));
                    }
                }
            }
        }

        // Realized cross-check
        BigDecimal delivDiff = computedDeliveryRealized.subtract(classifierDeliveryRealized);
        BigDecimal intraDiff = computedIntradayRealized.subtract(classifierIntradayRealized);

        ReconcilePreviewResponse.RealizedSummaryDto realizedSummary = new ReconcilePreviewResponse.RealizedSummaryDto(
                computedDeliveryRealized.setScale(2, RoundingMode.HALF_UP),
                computedIntradayRealized.setScale(2, RoundingMode.HALF_UP),
                totalChargesAcc.setScale(2, RoundingMode.HALF_UP),
                classifierDeliveryRealized.setScale(2, RoundingMode.HALF_UP),
                classifierIntradayRealized.setScale(2, RoundingMode.HALF_UP),
                delivDiff.setScale(2, RoundingMode.HALF_UP),
                intraDiff.setScale(2, RoundingMode.HALF_UP)
        );

        ReconcilePreviewResponse.SummaryStatsDto stats = new ReconcilePreviewResponse.SummaryStatsDto(
                classifiedDtos.size(),
                deliveryCount,
                intradayCount,
                classifiedDtos.size() - unresolvedScrips.size(),
                unresolvedScrips.size(),
                duplicateCount,
                warnings.size()
        );

        return new ReconcilePreviewResponse(
                classifiedDtos,
                derivedHoldings,
                realizedSummary,
                warnings,
                stats,
                classificationDtos
        );
    }

    public ImportCommitResponse commit(ReconcileCommitRequest request) {
        Account brokerAccount = accountRepository.findById(request.brokerAccountId())
                .orElseThrow(() -> new ResourceNotFoundException("Account", request.brokerAccountId()));

        if (brokerAccount.getType() != AccountType.broker) {
            throw new ValidationException("Account must be a broker account");
        }

        UUID userId = UserContext.getCurrentUserId();
        User user = userRepository.getReferenceById(userId);

        int committed = 0;
        int skipped = 0;
        List<ImportCommitResponse.FailedCommitItem> failedList = new ArrayList<>();
        List<ImportCommitResponse.SkippedCommitItem> skippedItems = new ArrayList<>();
        Set<UUID> touchedInstrumentIds = new HashSet<>();

        for (ReconcileCommitRequest.CommitExecutionDto execDto : request.executions()) {
            if (execDto.skip()) {
                skipped++;
                boolean dup = execDto.instrumentId() != null
                        && checkDuplicateInDb(brokerAccount.getId(), execDto.instrumentId(), execDto);
                skippedItems.add(new ImportCommitResponse.SkippedCommitItem(
                        execDto.rowIndex(), execDto.symbol(),
                        dup ? "Duplicate — already in your portfolio" : "Excluded during review"));
                continue;
            }

            try {
                // Resolve or create Instrument
                Instrument instrument = null;
                if (execDto.instrumentId() != null) {
                    instrument = instrumentRepository.findById(execDto.instrumentId())
                            .orElseThrow(() -> new ResourceNotFoundException("Instrument", execDto.instrumentId()));
                } else if (execDto.newInstrument() != null) {
                    CreateInstrumentDto newInstDto = execDto.newInstrument();
                    if (newInstDto.isin() != null && !newInstDto.isin().isBlank()) {
                        instrument = instrumentRepository.findByIsin(newInstDto.isin().trim()).orElse(null);
                    }
                    if (instrument == null && newInstDto.symbol() != null && !newInstDto.symbol().isBlank()) {
                        List<Instrument> searchResults = instrumentRepository.searchInstruments(newInstDto.symbol().trim(), null);
                        for (Instrument inst : searchResults) {
                            if (inst.getSymbol() != null && inst.getSymbol().equalsIgnoreCase(newInstDto.symbol().trim())) {
                                instrument = inst;
                                break;
                            }
                        }
                    }
                    if (instrument == null) {
                        Instrument newInst = new Instrument();
                        newInst.setType(newInstDto.type() != null ? newInstDto.type() : InstrumentType.stock);
                        newInst.setName(newInstDto.name() != null ? newInstDto.name() : newInstDto.symbol());
                        newInst.setSymbol(newInstDto.symbol());
                        newInst.setExchange(newInstDto.exchange() != null ? newInstDto.exchange() : "NSE");
                        newInst.setIsin(newInstDto.isin() != null ? newInstDto.isin().trim() : null);
                        newInst.setAmfiCode(newInstDto.amfiCode());

                        String yahooSym = newInstDto.yahooSymbol();
                        if ((yahooSym == null || yahooSym.isBlank()) && newInst.getType() == InstrumentType.stock && newInst.getSymbol() != null) {
                            yahooSym = newInst.getSymbol() + ("BSE".equalsIgnoreCase(newInst.getExchange()) ? ".BO" : ".NS");
                        }
                        newInst.setYahooSymbol(yahooSym);
                        instrument = instrumentRepository.save(newInst);
                    }
                } else {
                    // Try auto-matching by ISIN / symbol
                    ReconcilePreviewResponse.MatchedInstrumentDto m = resolveInstrumentLocally(execDto.isin(), execDto.symbol(), execDto.exchange());
                    if (m != null) {
                        instrument = instrumentRepository.findById(m.id()).orElse(null);
                    }
                }

                if (instrument == null) {
                    throw new ValidationException("No instrument mapped — set a match in the review step before importing");
                }

                // Resolve or create Holding
                final Instrument finalInstrument = instrument;
                Holding holding = holdingRepository.findByBrokerAccountIdAndInstrumentId(brokerAccount.getId(), finalInstrument.getId())
                        .orElseGet(() -> {
                            Holding h = new Holding(brokerAccount, finalInstrument, null);
                            h.setUser(user);
                            return holdingRepository.save(h);
                        });

                // Duplicate check
                boolean isDup = checkDuplicateInDb(brokerAccount.getId(), finalInstrument.getId(), execDto);
                if (isDup) {
                    skipped++;
                    skippedItems.add(new ImportCommitResponse.SkippedCommitItem(
                            execDto.rowIndex(), execDto.symbol(), "Duplicate — already in your portfolio"));
                    log.info("Skipping execution row {} as duplicate in DB", execDto.rowIndex());
                    continue;
                }

                InvestmentTransaction txn = new InvestmentTransaction();
                txn.setUser(user);
                txn.setHolding(holding);
                txn.setType(execDto.type());
                txn.setSettlementType(execDto.settlementType() != null ? execDto.settlementType() : SettlementType.delivery);
                txn.setQuantity(execDto.quantity());
                txn.setPrice(execDto.price());
                txn.setTradeDate(execDto.tradeDate());
                txn.setSource("import");
                txn.setExternalRef(execDto.externalRef());

                if (execDto.charges() != null) {
                    txn.setBrokerage(execDto.charges().brokerage());
                    txn.setStt(execDto.charges().stt());
                    txn.setExchangeTxnCharges(execDto.charges().exchangeTxnCharges());
                    txn.setSebiCharges(execDto.charges().sebiCharges());
                    txn.setStampDuty(execDto.charges().stampDuty());
                    txn.setGst(execDto.charges().gst());
                    txn.setDpCharges(execDto.charges().dpCharges());
                    txn.setOtherCharges(execDto.charges().otherCharges());
                }

                transactionRepository.save(txn);
                committed++;
                touchedInstrumentIds.add(finalInstrument.getId());

            } catch (Exception e) {
                log.error("Error committing reconciled execution row " + execDto.rowIndex(), e);
                failedList.add(new ImportCommitResponse.FailedCommitItem(execDto.rowIndex(), execDto.symbol(), e.getMessage()));
            }
        }

        // Save TradeSettlementClassification records
        if (request.classifications() != null) {
            for (ReconcileCommitRequest.CommitClassificationDto cDto : request.classifications()) {
                try {
                    ReconcilePreviewResponse.MatchedInstrumentDto m = resolveInstrumentLocally(cDto.isin(), cDto.symbol(), null);
                    if (m != null) {
                        Instrument instrument = instrumentRepository.findById(m.id()).orElse(null);
                        if (instrument != null) {
                            Holding holding = holdingRepository.findByBrokerAccountIdAndInstrumentId(brokerAccount.getId(), instrument.getId())
                                    .orElseGet(() -> {
                                        Holding h = new Holding(brokerAccount, instrument, null);
                                        h.setUser(user);
                                        return holdingRepository.save(h);
                                    });

                            Optional<TradeSettlementClassification> existingClass = classificationRepository
                                    .findByBrokerAccountIdAndInstrumentIdAndTradeDate(brokerAccount.getId(), instrument.getId(), cDto.tradeDate());

                            if (existingClass.isEmpty()) {
                                TradeSettlementClassification classification = new TradeSettlementClassification(
                                        user,
                                        brokerAccount,
                                        holding,
                                        instrument,
                                        cDto.tradeDate(),
                                        cDto.intradayQty(),
                                        cDto.intradayBuyValue(),
                                        cDto.intradaySellValue()
                                );
                                classificationRepository.save(classification);
                            }
                        }
                    }
                } catch (Exception e) {
                    log.error("Error committing settlement classification for date " + cDto.tradeDate(), e);
                }
            }
        }

        if (!touchedInstrumentIds.isEmpty()) {
            eventPublisher.publishEvent(new PriceRefreshEvent(touchedInstrumentIds));
        }

        return new ImportCommitResponse(committed, skipped, failedList, skippedItems);
    }

    private List<InternalExecution> dedupeExecutions(List<InternalExecution> rawExecs) {
        Set<TupleKey> seen = new HashSet<>();
        List<InternalExecution> out = new ArrayList<>();
        for (InternalExecution e : rawExecs) {
            TupleKey key = new TupleKey(
                    e.tradeId != null ? e.tradeId : "",
                    e.orderId != null ? e.orderId : "",
                    e.date,
                    e.isin != null ? e.isin : "",
                    e.type,
                    e.qty,
                    e.price,
                    e.execTime != null ? e.execTime : ""
            );
            if (!seen.contains(key)) {
                seen.add(key);
                out.add(e);
            }
        }
        return out;
    }

    private record TupleKey(
            String tradeId, String orderId, LocalDate date, String isin,
            InvestmentTransactionType type, BigDecimal qty, BigDecimal price, String execTime
    ) {}

    private record DeliveryEvent(LocalDate date, InvestmentTransactionType type, BigDecimal qty, BigDecimal value) {}

    /** FIFO-match a sell of {@code qty} against {@code openLots}, mutating the lots.
     *  Returns [matchedCost, unmatchedQty]. */
    private static BigDecimal[] fifoMatch(LinkedList<FifoLot> openLots, BigDecimal qty) {
        BigDecimal remaining = qty;
        BigDecimal matchedCost = BigDecimal.ZERO;
        while (remaining.compareTo(BigDecimal.ZERO) > 0 && !openLots.isEmpty()) {
            FifoLot oldest = openLots.peek();
            BigDecimal take = remaining.min(oldest.remainingQty);
            matchedCost = matchedCost.add(take.multiply(oldest.costPerUnit));
            oldest.remainingQty = oldest.remainingQty.subtract(take);
            remaining = remaining.subtract(take);
            if (oldest.remainingQty.compareTo(BigDecimal.ZERO) == 0) {
                openLots.poll();
            }
        }
        return new BigDecimal[]{matchedCost, remaining};
    }

    private static BigDecimal currentOpenQty(LinkedList<FifoLot> openLots) {
        BigDecimal q = BigDecimal.ZERO;
        for (FifoLot lot : openLots) {
            q = q.add(lot.remainingQty);
        }
        return q;
    }

    private void accumulateClassifierSold(Map<String, BigDecimal> qtyMap, Map<String, BigDecimal> valMap,
                                          Map<String, LocalDate> dateMap, String key,
                                          BigDecimal qty, BigDecimal sellValue, LocalDate exitDate) {
        if (key == null || qty == null) return;
        qtyMap.merge(key, qty, BigDecimal::add);
        if (sellValue != null) valMap.merge(key, sellValue, BigDecimal::add);
        if (exitDate != null) dateMap.merge(key, exitDate, (a, b) -> b.isAfter(a) ? b : a);
    }

    private static class FifoLot {
        BigDecimal remainingQty;
        BigDecimal costPerUnit;
        FifoLot(BigDecimal remainingQty, BigDecimal costPerUnit) {
            this.remainingQty = remainingQty;
            this.costPerUnit = costPerUnit;
        }
    }

    private ReconcilePreviewResponse.MatchedInstrumentDto resolveInstrumentLocally(String isin, String symbol, String exchange) {
        Instrument matched = null;
        if (isin != null && !isin.isBlank()) {
            matched = instrumentRepository.findByIsin(isin.trim()).orElse(null);
        }
        if (matched == null && symbol != null && !symbol.isBlank()) {
            List<Instrument> list = instrumentRepository.searchInstruments(symbol.trim(), null);
            for (Instrument inst : list) {
                if (inst.getSymbol() != null && inst.getSymbol().equalsIgnoreCase(symbol.trim())) {
                    if (exchange == null || inst.getExchange() == null || inst.getExchange().equalsIgnoreCase(exchange.trim())) {
                        matched = inst;
                        break;
                    }
                }
            }
            if (matched == null && aliasRepository != null) {
                matched = aliasRepository.findFirstByOldSymbolIgnoreCase(symbol.trim())
                        .map(InstrumentAlias::getInstrument)
                        .orElse(null);
            }
        }
        if (matched != null) {
            return new ReconcilePreviewResponse.MatchedInstrumentDto(
                    matched.getId(),
                    matched.getType(),
                    matched.getName(),
                    matched.getSymbol(),
                    matched.getExchange(),
                    matched.getIsin()
            );
        }
        return null;
    }

    /** True if the existing txn is the same trade as the incoming one.
     *  When BOTH sides carry a broker ref, the ref is the sole arbiter (no fuzzy fallback);
     *  the fuzzy tuple is used only when a reliable ref is missing on either side. */
    private static boolean isSameTrade(String incomingRef, LocalDate date,
                                       InvestmentTransactionType type, BigDecimal qty, BigDecimal price,
                                       InvestmentTransaction t) {
        String existingRef = t.getExternalRef();
        boolean bothHaveRef = incomingRef != null && !incomingRef.isBlank()
                && existingRef != null && !existingRef.isBlank();
        if (bothHaveRef) {
            return incomingRef.equalsIgnoreCase(existingRef);   // ref is authoritative; NO fuzzy fallback
        }
        return date != null && date.equals(t.getTradeDate())
                && type == t.getType()
                && qty != null && qty.compareTo(t.getQuantity()) == 0
                && price != null && price.compareTo(t.getPrice()) == 0;
    }

    private boolean checkDuplicateInDb(UUID brokerAccountId, UUID instrumentId, InternalExecution e) {
        if (instrumentId == null) return false;
        Page<InvestmentTransaction> existing = transactionRepository.findFilteredTransactions(
                brokerAccountId, instrumentId, null, null, Pageable.unpaged());

        String incomingRef = (e.tradeId != null && !e.tradeId.isBlank()) ? e.tradeId : e.orderId;

        for (InvestmentTransaction t : existing.getContent()) {
            if (isSameTrade(incomingRef, e.date, e.type, e.qty, e.price, t)) {
                return true;
            }
        }
        return false;
    }

    private boolean checkDuplicateInDb(UUID brokerAccountId, UUID instrumentId, ReconcileCommitRequest.CommitExecutionDto e) {
        if (instrumentId == null) return false;
        Page<InvestmentTransaction> existing = transactionRepository.findFilteredTransactions(
                brokerAccountId, instrumentId, null, null, Pageable.unpaged());

        String incomingRef = e.externalRef();

        for (InvestmentTransaction t : existing.getContent()) {
            if (isSameTrade(incomingRef, e.tradeDate(), e.type(), e.quantity(), e.price(), t)) {
                return true;
            }
        }
        return false;
    }

    private BigDecimal calculateTotalCharges(ItemizedChargesDto c) {
        BigDecimal sum = BigDecimal.ZERO;
        if (c.brokerage() != null) sum = sum.add(c.brokerage());
        if (c.stt() != null) sum = sum.add(c.stt());
        if (c.exchangeTxnCharges() != null) sum = sum.add(c.exchangeTxnCharges());
        if (c.sebiCharges() != null) sum = sum.add(c.sebiCharges());
        if (c.stampDuty() != null) sum = sum.add(c.stampDuty());
        if (c.gst() != null) sum = sum.add(c.gst());
        if (c.dpCharges() != null) sum = sum.add(c.dpCharges());
        if (c.otherCharges() != null) sum = sum.add(c.otherCharges());
        return sum;
    }
}
