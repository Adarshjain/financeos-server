package com.financeos.domain.investment.dividend;

import com.financeos.api.investment.dto.*;
import com.financeos.core.exception.ResourceNotFoundException;
import com.financeos.core.exception.ValidationException;
import com.financeos.core.security.UserContext;
import com.financeos.core.time.AppTime;
import com.financeos.domain.holding.Holding;
import com.financeos.domain.holding.HoldingRepository;
import com.financeos.domain.instrument.Instrument;
import com.financeos.domain.instrument.InstrumentOverrides;
import com.financeos.domain.instrument.InstrumentType;
import com.financeos.domain.instrument.corporateaction.CorporateAction;
import com.financeos.domain.instrument.corporateaction.CorporateActionRepository;
import com.financeos.domain.instrument.price.YahooDividendEventsClient;
import com.financeos.domain.investment.InvestmentService;
import com.financeos.domain.investment.InvestmentTransaction;
import com.financeos.domain.investment.InvestmentTransactionRepository;
import com.financeos.domain.investment.TradeSettlementClassification;
import com.financeos.domain.investment.TradeSettlementClassificationRepository;
import com.financeos.domain.user.User;
import com.financeos.domain.user.UserRepository;
import com.financeos.core.tx.SectionRunner;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.stream.Collectors;

@Service
@Transactional
public class DividendService {

    private final DividendRepository dividendRepository;
    private final HoldingRepository holdingRepository;
    private final UserRepository userRepository;
    private final InvestmentService investmentService;
    private final YahooDividendEventsClient yahooClient;
    private final InvestmentTransactionRepository transactionRepository;
    private final DividendReceiptStatusResolver receiptResolver;
    private final CorporateActionRepository corporateActionRepository;
    private final TradeSettlementClassificationRepository classificationRepository;
    private final SectionRunner sectionRunner;
    private final Map<ScanKey, CachedFetch> fetchCache = new LinkedHashMap<>(16, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<ScanKey, CachedFetch> eldest) {
            return size() > FETCH_CACHE_MAX;
        }
    };

    /** Yahoo results of a scan are reused for a user's repeat scan within this window. */
    static final java.time.Duration FETCH_CACHE_TTL = java.time.Duration.ofMinutes(10);
    private static final int FETCH_CACHE_MAX = 500;

    private record ScanKey(UUID userId, String symbol, Instant period1) {}

    private record CachedFetch(YahooDividendEventsClient.FetchResult result, Instant at) {}

    /** Oracle caps an IN list at 1000 entries; batch lookups go in chunks of this size. */
    private static final int IN_LIST_CHUNK = 900;

    public DividendService(DividendRepository dividendRepository,
                           HoldingRepository holdingRepository,
                           UserRepository userRepository,
                           InvestmentService investmentService,
                           YahooDividendEventsClient yahooClient,
                           InvestmentTransactionRepository transactionRepository,
                           DividendReceiptStatusResolver receiptResolver,
                           CorporateActionRepository corporateActionRepository,
                           TradeSettlementClassificationRepository classificationRepository) {
        this(dividendRepository, holdingRepository, userRepository, investmentService, yahooClient, transactionRepository,
                receiptResolver, corporateActionRepository, classificationRepository, SectionRunner.DIRECT);
    }

    @Autowired
    public DividendService(DividendRepository dividendRepository,
                           HoldingRepository holdingRepository,
                           UserRepository userRepository,
                           InvestmentService investmentService,
                           YahooDividendEventsClient yahooClient,
                           InvestmentTransactionRepository transactionRepository,
                           DividendReceiptStatusResolver receiptResolver,
                           CorporateActionRepository corporateActionRepository,
                           TradeSettlementClassificationRepository classificationRepository,
                           SectionRunner sectionRunner) {
        this.sectionRunner = sectionRunner;
        this.dividendRepository = dividendRepository;
        this.holdingRepository = holdingRepository;
        this.userRepository = userRepository;
        this.investmentService = investmentService;
        this.yahooClient = yahooClient;
        this.transactionRepository = transactionRepository;
        this.receiptResolver = receiptResolver;
        this.corporateActionRepository = corporateActionRepository;
        this.classificationRepository = classificationRepository;
    }

    public DividendResponse createDividend(CreateDividendRequest request) {
        Holding holding = holdingRepository.findByBrokerAccountIdAndInstrumentId(request.brokerAccountId(), request.instrumentId())
                .orElseThrow(() -> new ValidationException("No holding found for broker account " + request.brokerAccountId() + " and instrument " + request.instrumentId()));

        UUID userId = UserContext.getCurrentUserId();
        User user = userId != null ? userRepository.getReferenceById(userId) : null;

        Dividend dividend = new Dividend();
        dividend.setUser(user);
        dividend.setHolding(holding);
        dividend.setType(request.type());
        dividend.setAmount(request.amount());
        dividend.setPerUnit(request.perUnit());
        dividend.setTds(request.tds());
        dividend.setExDate(request.exDate());
        dividend.setPayDate(request.payDate());
        dividend.setNotes(request.notes());

        Dividend saved = dividendRepository.save(dividend);
        return receiptResolver.toResponse(saved);
    }

    public DividendResponse updateDividend(UUID id, UpdateDividendRequest request) {
        Dividend dividend = dividendRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Dividend", id));

        UUID currentUserId = UserContext.getCurrentUserId();
        if (dividend.getUser() == null || !dividend.getUser().getId().equals(currentUserId)) {
            throw new ResourceNotFoundException("Dividend", id);
        }

        dividend.setType(request.type());
        dividend.setAmount(request.amount());
        dividend.setPerUnit(request.perUnit());
        dividend.setTds(request.tds());
        dividend.setExDate(request.exDate());
        dividend.setPayDate(request.payDate());
        dividend.setNotes(request.notes());

        Dividend saved = dividendRepository.save(dividend);
        return receiptResolver.toResponse(saved);
    }

    public void deleteDividend(UUID id) {
        Dividend dividend = dividendRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Dividend", id));

        UUID currentUserId = UserContext.getCurrentUserId();
        if (dividend.getUser() == null || !dividend.getUser().getId().equals(currentUserId)) {
            throw new ResourceNotFoundException("Dividend", id);
        }

        dividendRepository.delete(dividend);
    }

    @Transactional(readOnly = true)
    public Page<DividendResponse> getDividends(UUID holdingId, UUID brokerAccountId, UUID instrumentId,
                                                DividendType type, LocalDate from, LocalDate to,
                                                DividendReceiptStatus receipt, Pageable pageable) {
        DividendReceiptStatusResolver.Context ctx = receiptResolver.context();
        DividendReceiptWindows.Thresholds th = DividendReceiptWindows.thresholds(ctx.today(), ctx.coverageEnd());
        Page<Dividend> page = dividendRepository.findFilteredDividends(holdingId, brokerAccountId, instrumentId, type, from, to,
                receipt != null ? receipt.name() : null,
                th.suggestedAwaitFrom(), th.importAwaitFrom(), th.manualAwaitFrom(),
                th.suggestedOverdueBefore(), th.importOverdueBefore(), th.manualOverdueBefore(),
                pageable);
        return page.map(d -> receiptResolver.toResponse(d, ctx));
    }

    @Transactional(readOnly = true)
    public DividendSummaryResponse getSummary(UUID holdingId, UUID brokerAccountId, UUID instrumentId, DividendType type) {
        return getSummary(holdingId, brokerAccountId, instrumentId, type, null, null);
    }

    /** {@link #getSummary(UUID, UUID, UUID, DividendType)} for dividends paid within {@code from}..{@code to} (either end open). */
    @Transactional(readOnly = true)
    public DividendSummaryResponse getSummary(UUID holdingId, UUID brokerAccountId, UUID instrumentId, DividendType type,
                                              LocalDate from, LocalDate to) {
        List<Object[]> rows = dividendRepository.findDividendRowsForSummary(holdingId, brokerAccountId, instrumentId, type).stream()
                .filter(r -> inRange((LocalDate) r[0], from, to))
                .toList();

        Map<Integer, FyBucketAccumulator> bucketMap = new HashMap<>();
        BigDecimal grandAmount = BigDecimal.ZERO;
        BigDecimal grandTds = BigDecimal.ZERO;
        BigDecimal grandNet = BigDecimal.ZERO;
        long grandCount = 0;

        for (Object[] row : rows) {
            LocalDate payDate = (LocalDate) row[0];
            BigDecimal amount = row[1] != null ? (BigDecimal) row[1] : BigDecimal.ZERO;
            BigDecimal tds = row[2] != null ? (BigDecimal) row[2] : BigDecimal.ZERO;
            BigDecimal net = amount.subtract(tds);

            int startYear = payDate.getMonthValue() >= 4 ? payDate.getYear() : payDate.getYear() - 1;
            FyBucketAccumulator acc = bucketMap.computeIfAbsent(startYear, FyBucketAccumulator::new);
            acc.amount = acc.amount.add(amount);
            acc.tds = acc.tds.add(tds);
            acc.net = acc.net.add(net);
            acc.count++;

            grandAmount = grandAmount.add(amount);
            grandTds = grandTds.add(tds);
            grandNet = grandNet.add(net);
            grandCount++;
        }

        List<DividendSummaryResponse.FyBucket> buckets = bucketMap.keySet().stream()
                .sorted(Comparator.reverseOrder())
                .map(startYear -> {
                    FyBucketAccumulator acc = bucketMap.get(startYear);
                    String label = String.format("FY %d-%02d", startYear, (startYear + 1) % 100);
                    LocalDate fromDate = LocalDate.of(startYear, 4, 1);
                    LocalDate toDate = LocalDate.of(startYear + 1, 3, 31);
                    return new DividendSummaryResponse.FyBucket(
                            label, fromDate, toDate, acc.amount, acc.tds, acc.net, acc.count
                    );
                })
                .toList();

        return new DividendSummaryResponse(buckets, grandAmount, grandTds, grandNet, grandCount);
    }

    static boolean inRange(LocalDate date, LocalDate from, LocalDate to) {
        return (from == null || !date.isBefore(from)) && (to == null || !date.isAfter(to));
    }

    private static class FyBucketAccumulator {
        final int startYear;
        BigDecimal amount = BigDecimal.ZERO;
        BigDecimal tds = BigDecimal.ZERO;
        BigDecimal net = BigDecimal.ZERO;
        long count = 0;

        FyBucketAccumulator(int startYear) {
            this.startYear = startYear;
        }
    }

    /**
     * Scans in three steps so no database connection is held across the slow Yahoo calls: a short read
     * transaction finds the symbols and their first trade dates, the fetches run with no transaction, and a
     * second short read transaction builds the suggestions from fresh data.
     */
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public DividendSuggestionsResponse scanSuggestions(UUID brokerAccountId) {
        Map<String, Instant> period1BySymbol = sectionRunner.run(() -> period1BySymbol(brokerAccountId));
        Map<String, YahooDividendEventsClient.FetchResult> fetched = fetchAll(period1BySymbol);
        return sectionRunner.run(() -> buildSuggestions(brokerAccountId, fetched));
    }

    private List<Holding> scannableHoldings(UUID brokerAccountId) {
        return holdingRepository.findAllWithDetails().stream()
                .filter(h -> brokerAccountId == null || h.getBrokerAccount().getId().equals(brokerAccountId))
                .filter(h -> {
                    Instrument inst = h.getInstrument();
                    return inst != null && (inst.getType() == InstrumentType.stock || inst.getType() == InstrumentType.etf)
                            && inst.getYahooSymbol() != null && !inst.getYahooSymbol().isBlank();
                })
                .toList();
    }

    /** Each scanned Yahoo symbol with the earliest trade date over its holdings (null when none has a trade). */
    private Map<String, Instant> period1BySymbol(UUID brokerAccountId) {
        List<Holding> holdings = scannableHoldings(brokerAccountId);
        List<UUID> holdingIds = holdings.stream().map(Holding::getId).distinct().toList();
        Map<UUID, Instant> firstTradeByHolding = new HashMap<>();
        for (int from = 0; from < holdingIds.size(); from += IN_LIST_CHUNK) {
            List<UUID> chunk = holdingIds.subList(from, Math.min(holdingIds.size(), from + IN_LIST_CHUNK));
            for (InvestmentTransaction t : transactionRepository.findByHoldingIdInOrderByTradeDateAscCreatedAtAsc(chunk)) {
                firstTradeByHolding.putIfAbsent(t.getHolding().getId(), t.getTradeDate().atStartOfDay(AppTime.zone()).toInstant());
            }
        }
        Map<String, Instant> result = new LinkedHashMap<>();
        for (Holding h : holdings) {
            Instant first = firstTradeByHolding.get(h.getId());
            result.merge(h.getInstrument().getYahooSymbol().trim(), first == null ? Instant.EPOCH : first,
                    (x, y) -> x.isBefore(y) ? x : y);
        }
        result.replaceAll((k, v) -> v.equals(Instant.EPOCH) ? null : v);
        return result;
    }

    /** Yahoo fetch per symbol, no transaction held; a user's repeat fetch within the TTL is served from memory. */
    private Map<String, YahooDividendEventsClient.FetchResult> fetchAll(Map<String, Instant> period1BySymbol) {
        UUID userId = UserContext.getCurrentUserId();
        Map<String, YahooDividendEventsClient.FetchResult> fetched = new HashMap<>();
        boolean calledYahoo = false;
        for (Map.Entry<String, Instant> entry : period1BySymbol.entrySet()) {
            ScanKey key = new ScanKey(userId, entry.getKey(), entry.getValue());
            YahooDividendEventsClient.FetchResult cached = cachedFetch(key);
            if (cached != null) {
                fetched.put(entry.getKey(), cached);
                continue;
            }
            if (calledYahoo) {
                try {
                    Thread.sleep(100);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
            calledYahoo = true;
            YahooDividendEventsClient.FetchResult result =
                    yahooClient.fetchDividendEventsWithStatus(entry.getKey(), entry.getValue(), Instant.now());
            fetched.put(entry.getKey(), result);
            if (result.success()) {
                synchronized (fetchCache) {
                    fetchCache.put(key, new CachedFetch(result, Instant.now()));
                }
            }
        }
        return fetched;
    }

    private YahooDividendEventsClient.FetchResult cachedFetch(ScanKey key) {
        synchronized (fetchCache) {
            CachedFetch c = fetchCache.get(key);
            if (c == null) {
                return null;
            }
            if (c.at().plus(FETCH_CACHE_TTL).isBefore(Instant.now())) {
                fetchCache.remove(key);
                return null;
            }
            return c.result();
        }
    }

    private DividendSuggestionsResponse buildSuggestions(UUID brokerAccountId,
                                                         Map<String, YahooDividendEventsClient.FetchResult> fetched) {
        List<Holding> holdings = scannableHoldings(brokerAccountId);

        Map<String, List<Holding>> holdingsBySymbol = holdings.stream()
                .collect(Collectors.groupingBy(h -> h.getInstrument().getYahooSymbol().trim()));

        List<DividendSuggestionsResponse.Suggestion> suggestions = new ArrayList<>();
        List<String> skippedSymbols = new ArrayList<>();
        InstrumentOverrides overrides = holdings.isEmpty()
                ? InstrumentOverrides.NONE : InstrumentOverrides.orNone(investmentService.instrumentOverrides());
        int scannedCount = holdingsBySymbol.size();

        // One batch per table instead of per holding / per event: transactions, existing dividends and corporate
        // actions of every scanned holding (the lot engine then reads them from memory).
        List<UUID> holdingIds = holdings.stream().map(Holding::getId).distinct().toList();
        Map<UUID, List<InvestmentTransaction>> txnsByHolding = new HashMap<>();
        Map<UUID, List<Dividend>> dividendsByHolding = new HashMap<>();
        for (int from = 0; from < holdingIds.size(); from += IN_LIST_CHUNK) {
            List<UUID> chunk = holdingIds.subList(from, Math.min(holdingIds.size(), from + IN_LIST_CHUNK));
            for (InvestmentTransaction t : transactionRepository.findByHoldingIdInOrderByTradeDateAscCreatedAtAsc(chunk)) {
                txnsByHolding.computeIfAbsent(t.getHolding().getId(), k -> new ArrayList<>()).add(t);
            }
            for (Dividend d : dividendRepository.findByHoldingIdInOrderByPayDateDescCreatedAtDesc(chunk)) {
                dividendsByHolding.computeIfAbsent(d.getHolding().getId(), k -> new ArrayList<>()).add(d);
            }
        }
        Map<UUID, Map<UUID, List<CorporateAction>>> actionsByOwner = new HashMap<>();
        Map<UUID, Set<UUID>> instrumentsByOwner = new HashMap<>();
        for (Holding h : holdings) {
            if (h.getUser() != null) {
                instrumentsByOwner.computeIfAbsent(h.getUser().getId(), k -> new LinkedHashSet<>()).add(h.getInstrument().getId());
            }
        }
        instrumentsByOwner.forEach((ownerId, instrumentIds) -> {
            List<UUID> ids = List.copyOf(instrumentIds);
            Map<UUID, List<CorporateAction>> byInstrument = new HashMap<>();
            for (int from = 0; from < ids.size(); from += IN_LIST_CHUNK) {
                for (CorporateAction ca : corporateActionRepository.findOwnedByInstrumentIds(ownerId,
                        ids.subList(from, Math.min(ids.size(), from + IN_LIST_CHUNK)))) {
                    byInstrument.computeIfAbsent(ca.getInstrument().getId(), k -> new ArrayList<>()).add(ca);
                }
            }
            actionsByOwner.put(ownerId, byInstrument);
        });

        // Intraday classifications of the scanned holdings, read once per owner.
        Set<UUID> holdingIdSet = new HashSet<>(holdingIds);
        Map<UUID, List<TradeSettlementClassification>> classificationsByHolding = new HashMap<>();
        for (UUID ownerId : instrumentsByOwner.keySet()) {
            for (TradeSettlementClassification c : classificationRepository.findByUser_Id(ownerId)) {
                if (c.getHolding() != null && holdingIdSet.contains(c.getHolding().getId())) {
                    classificationsByHolding.computeIfAbsent(c.getHolding().getId(), k -> new ArrayList<>()).add(c);
                }
            }
        }

        for (Map.Entry<String, List<Holding>> entry : holdingsBySymbol.entrySet()) {
            String symbol = entry.getKey();
            List<Holding> symbolHoldings = entry.getValue();

            YahooDividendEventsClient.FetchResult fetchResult = fetched.get(symbol);
            if (fetchResult == null || !fetchResult.success()) {
                skippedSymbols.add(symbol);
                continue;
            }

            List<YahooDividendEventsClient.DividendEvent> events = fetchResult.events();
            for (Holding holding : symbolHoldings) {
                List<Dividend> existingDividends = dividendsByHolding.getOrDefault(holding.getId(), List.of());
                List<InvestmentTransaction> holdingTxns = txnsByHolding.getOrDefault(holding.getId(), List.of());
                List<CorporateAction> holdingActions = holding.getUser() == null ? List.of()
                        : actionsByOwner.getOrDefault(holding.getUser().getId(), Map.of())
                                .getOrDefault(holding.getInstrument().getId(), List.of());

                List<TradeSettlementClassification> holdingClassifications =
                        classificationsByHolding.getOrDefault(holding.getId(), List.of());

                for (YahooDividendEventsClient.DividendEvent event : events) {
                    BigDecimal qtyHeld = openQtyAsOf(holding, event.exDate(), holdingTxns, holdingActions, holdingClassifications);
                    if (qtyHeld.compareTo(BigDecimal.ZERO) <= 0) {
                        continue;
                    }

                    boolean duplicate = existingDividends.stream().anyMatch(d -> {
                        LocalDate dDate = d.getExDate() != null ? d.getExDate() : d.getPayDate();
                        return Math.abs(ChronoUnit.DAYS.between(dDate, event.exDate())) <= 7;
                    });
                    if (duplicate) {
                        continue;
                    }

                    BigDecimal perUnit = event.amount();
                    BigDecimal estimatedAmount = perUnit.multiply(qtyHeld).setScale(2, RoundingMode.HALF_UP);

                    suggestions.add(new DividendSuggestionsResponse.Suggestion(
                            holding.getId(),
                            holding.getBrokerAccount().getId(),
                            holding.getBrokerAccount().getName(),
                            holding.getInstrument().getId(),
                            overrides.name(holding.getInstrument()),
                            overrides.symbol(holding.getInstrument()) != null ? overrides.symbol(holding.getInstrument()) : symbol,
                            event.exDate(),
                            perUnit,
                            qtyHeld,
                            estimatedAmount
                    ));
                }
            }
        }

        suggestions.sort((s1, s2) -> s2.exDate().compareTo(s1.exDate()));
        return new DividendSuggestionsResponse(suggestions, scannedCount, skippedSymbols);
    }

    /** {@link InvestmentService#openQtyAsOf} over already-loaded transactions, corporate actions and classifications. */
    private BigDecimal openQtyAsOf(Holding holding, LocalDate date, List<InvestmentTransaction> txns, List<CorporateAction> actions,
                                   List<TradeSettlementClassification> classifications) {
        BigDecimal total = BigDecimal.ZERO;
        for (InvestmentService.Lot lot : investmentService.buildOpenLotsBeforeDate(holding, date, true, null, txns, actions, null, classifications)) {
            total = total.add(lot.remainingQty);
        }
        return total.compareTo(BigDecimal.ZERO) > 0 ? total : BigDecimal.ZERO;
    }

    public AcceptSuggestionsResponse acceptSuggestions(AcceptSuggestionsRequest request) {
        UUID currentUserId = UserContext.getCurrentUserId();
        User user = currentUserId != null ? userRepository.getReferenceById(currentUserId) : null;

        List<DividendResponse> createdResponses = new ArrayList<>();
        int skippedCount = 0;
        DividendReceiptStatusResolver.Context ctx = receiptResolver.context();

        Set<UUID> holdingIds = request.items().stream().map(AcceptSuggestionsRequest.Item::holdingId).collect(Collectors.toCollection(LinkedHashSet::new));
        List<UUID> holdingIdList = List.copyOf(holdingIds);
        Map<UUID, Holding> holdingsById = new HashMap<>();
        Map<UUID, List<Dividend>> existingByHolding = new HashMap<>();
        for (int from = 0; from < holdingIdList.size(); from += IN_LIST_CHUNK) {
            List<UUID> chunk = holdingIdList.subList(from, Math.min(holdingIdList.size(), from + IN_LIST_CHUNK));
            for (Holding h : holdingRepository.findAllById(chunk)) {
                holdingsById.put(h.getId(), h);
            }
            for (Dividend d : dividendRepository.findByHoldingIdInOrderByPayDateDescCreatedAtDesc(chunk)) {
                existingByHolding.computeIfAbsent(d.getHolding().getId(), k -> new ArrayList<>()).add(d);
            }
        }

        for (AcceptSuggestionsRequest.Item item : request.items()) {
            Holding holding = holdingsById.get(item.holdingId());
            if (holding == null) {
                // The batch is a query, so the user filter hides owner-less legacy holdings that a by-id read still gives.
                holding = holdingRepository.findById(item.holdingId())
                        .orElseThrow(() -> new ResourceNotFoundException("Holding", item.holdingId()));
                holdingsById.put(holding.getId(), holding);
                existingByHolding.put(holding.getId(),
                        new ArrayList<>(dividendRepository.findByHoldingIdOrderByPayDateDescCreatedAtDesc(holding.getId())));
            }

            if (user != null && holding.getUser() != null && !holding.getUser().getId().equals(currentUserId)) {
                throw new ResourceNotFoundException("Holding", item.holdingId());
            }

            // Includes dividends accepted earlier in this same request, as the per-item query did.
            List<Dividend> existing = existingByHolding.computeIfAbsent(holding.getId(), k -> new ArrayList<>());
            boolean duplicate = existing.stream().anyMatch(d -> {
                LocalDate dDate = d.getExDate() != null ? d.getExDate() : d.getPayDate();
                return Math.abs(ChronoUnit.DAYS.between(dDate, item.exDate())) <= 7;
            });
            if (duplicate) {
                skippedCount++;
                continue;
            }

            Dividend dividend = new Dividend();
            dividend.setUser(user);
            dividend.setHolding(holding);
            dividend.setType(DividendType.dividend);
            dividend.setAmount(item.amount());
            dividend.setPerUnit(item.perUnit());
            dividend.setTds(null);
            dividend.setExDate(item.exDate());
            dividend.setPayDate(item.payDate());
            dividend.setSource("suggested");
            dividend.setNotes(item.notes());

            Dividend saved = dividendRepository.save(dividend);
            existing.add(saved);
            createdResponses.add(receiptResolver.toResponse(saved, ctx));
        }

        return new AcceptSuggestionsResponse(createdResponses, skippedCount);
    }
}
