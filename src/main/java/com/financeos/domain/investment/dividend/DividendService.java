package com.financeos.domain.investment.dividend;

import com.financeos.api.investment.dto.*;
import com.financeos.core.exception.ResourceNotFoundException;
import com.financeos.core.exception.ValidationException;
import com.financeos.core.security.UserContext;
import com.financeos.domain.holding.Holding;
import com.financeos.domain.holding.HoldingRepository;
import com.financeos.domain.instrument.Instrument;
import com.financeos.domain.instrument.InstrumentType;
import com.financeos.domain.instrument.price.YahooDividendEventsClient;
import com.financeos.domain.investment.InvestmentService;
import com.financeos.domain.investment.InvestmentTransaction;
import com.financeos.domain.investment.InvestmentTransactionRepository;
import com.financeos.domain.user.User;
import com.financeos.domain.user.UserRepository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
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

    public DividendService(DividendRepository dividendRepository,
                           HoldingRepository holdingRepository,
                           UserRepository userRepository,
                           InvestmentService investmentService,
                           YahooDividendEventsClient yahooClient,
                           InvestmentTransactionRepository transactionRepository) {
        this.dividendRepository = dividendRepository;
        this.holdingRepository = holdingRepository;
        this.userRepository = userRepository;
        this.investmentService = investmentService;
        this.yahooClient = yahooClient;
        this.transactionRepository = transactionRepository;
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
        return DividendResponse.from(saved);
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
        return DividendResponse.from(saved);
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
                                                DividendType type, LocalDate from, LocalDate to, Pageable pageable) {
        Page<Dividend> page = dividendRepository.findFilteredDividends(holdingId, brokerAccountId, instrumentId, type, from, to, pageable);
        return page.map(DividendResponse::from);
    }

    @Transactional(readOnly = true)
    public DividendSummaryResponse getSummary(UUID holdingId, UUID brokerAccountId, UUID instrumentId, DividendType type) {
        List<Object[]> rows = dividendRepository.findDividendRowsForSummary(holdingId, brokerAccountId, instrumentId, type);

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

    @Transactional(readOnly = true)
    public DividendSuggestionsResponse scanSuggestions(UUID brokerAccountId) {
        List<Holding> holdings = holdingRepository.findAllWithDetails().stream()
                .filter(h -> brokerAccountId == null || h.getBrokerAccount().getId().equals(brokerAccountId))
                .filter(h -> {
                    Instrument inst = h.getInstrument();
                    return inst != null && (inst.getType() == InstrumentType.stock || inst.getType() == InstrumentType.etf)
                            && inst.getYahooSymbol() != null && !inst.getYahooSymbol().isBlank();
                })
                .toList();

        Map<String, List<Holding>> holdingsBySymbol = holdings.stream()
                .collect(Collectors.groupingBy(h -> h.getInstrument().getYahooSymbol().trim()));

        List<DividendSuggestionsResponse.Suggestion> suggestions = new ArrayList<>();
        List<String> skippedSymbols = new ArrayList<>();
        int scannedCount = holdingsBySymbol.size();

        int index = 0;
        for (Map.Entry<String, List<Holding>> entry : holdingsBySymbol.entrySet()) {
            if (index > 0) {
                try {
                    Thread.sleep(100);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
            index++;

            String symbol = entry.getKey();
            List<Holding> symbolHoldings = entry.getValue();

            Instant period1 = null;
            for (Holding h : symbolHoldings) {
                List<InvestmentTransaction> txns = transactionRepository.findByHoldingIdOrderByTradeDateAscCreatedAtAsc(h.getId());
                if (!txns.isEmpty()) {
                    Instant txnDate = txns.get(0).getTradeDate().atStartOfDay(ZoneId.of("Asia/Kolkata")).toInstant();
                    if (period1 == null || txnDate.isBefore(period1)) {
                        period1 = txnDate;
                    }
                }
            }

            YahooDividendEventsClient.FetchResult fetchResult = yahooClient.fetchDividendEventsWithStatus(symbol, period1, Instant.now());
            if (!fetchResult.success()) {
                skippedSymbols.add(symbol);
                continue;
            }

            List<YahooDividendEventsClient.DividendEvent> events = fetchResult.events();
            for (Holding holding : symbolHoldings) {
                List<Dividend> existingDividends = dividendRepository.findByHoldingIdOrderByPayDateDescCreatedAtDesc(holding.getId());

                for (YahooDividendEventsClient.DividendEvent event : events) {
                    BigDecimal qtyHeld = investmentService.openQtyAsOf(holding, event.exDate());
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
                            holding.getInstrument().getName(),
                            holding.getInstrument().getSymbol() != null ? holding.getInstrument().getSymbol() : symbol,
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

    public AcceptSuggestionsResponse acceptSuggestions(AcceptSuggestionsRequest request) {
        UUID currentUserId = UserContext.getCurrentUserId();
        User user = currentUserId != null ? userRepository.getReferenceById(currentUserId) : null;

        List<DividendResponse> createdResponses = new ArrayList<>();
        int skippedCount = 0;

        for (AcceptSuggestionsRequest.Item item : request.items()) {
            Holding holding = holdingRepository.findById(item.holdingId())
                    .orElseThrow(() -> new ResourceNotFoundException("Holding", item.holdingId()));

            if (user != null && holding.getUser() != null && !holding.getUser().getId().equals(currentUserId)) {
                throw new ResourceNotFoundException("Holding", item.holdingId());
            }

            List<Dividend> existing = dividendRepository.findByHoldingIdOrderByPayDateDescCreatedAtDesc(holding.getId());
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
            createdResponses.add(DividendResponse.from(saved));
        }

        return new AcceptSuggestionsResponse(createdResponses, skippedCount);
    }
}
