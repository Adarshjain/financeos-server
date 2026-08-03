package com.financeos.domain.investment;

import com.financeos.api.investment.dto.*;
import com.financeos.core.exception.ResourceNotFoundException;
import com.financeos.core.exception.ValidationException;
import com.financeos.core.security.UserContext;
import com.financeos.domain.account.Account;
import com.financeos.domain.account.AccountRepository;
import com.financeos.domain.account.AccountType;
import com.financeos.domain.holding.Holding;
import com.financeos.domain.holding.HoldingRepository;
import com.financeos.domain.instrument.*;
import com.financeos.domain.instrument.corporateaction.CorporateAction;
import com.financeos.domain.instrument.corporateaction.CorporateActionRepository;
import com.financeos.domain.instrument.corporateaction.CorporateActionType;
import com.financeos.domain.instrument.price.PriceRefreshEvent;
import com.financeos.domain.investment.dividend.Dividend;
import com.financeos.domain.investment.dividend.DividendRepository;
import com.financeos.domain.investment.returncalc.XirrCalculator;
import com.financeos.domain.user.User;
import com.financeos.domain.user.UserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.*;

@Service
@Transactional
public class InvestmentService {

    private static final Logger log = LoggerFactory.getLogger(InvestmentService.class);

    private final InvestmentTransactionRepository transactionRepository;
    private final HoldingRepository holdingRepository;
    private final AccountRepository accountRepository;
    private final InstrumentRepository instrumentRepository;
    private final InstrumentPriceRepository priceRepository;
    private final UserRepository userRepository;
    private final CorporateActionRepository corporateActionRepository;
    private final DividendRepository dividendRepository;
    private final TradeSettlementClassificationRepository classificationRepository;
    private final ApplicationEventPublisher eventPublisher;

    public InvestmentService(InvestmentTransactionRepository transactionRepository,
                              HoldingRepository holdingRepository,
                              AccountRepository accountRepository,
                              InstrumentRepository instrumentRepository,
                              InstrumentPriceRepository priceRepository,
                              UserRepository userRepository,
                              CorporateActionRepository corporateActionRepository,
                              DividendRepository dividendRepository,
                              TradeSettlementClassificationRepository classificationRepository,
                              ApplicationEventPublisher eventPublisher) {
        this.transactionRepository = transactionRepository;
        this.holdingRepository = holdingRepository;
        this.accountRepository = accountRepository;
        this.instrumentRepository = instrumentRepository;
        this.priceRepository = priceRepository;
        this.userRepository = userRepository;
        this.corporateActionRepository = corporateActionRepository;
        this.dividendRepository = dividendRepository;
        this.classificationRepository = classificationRepository;
        this.eventPublisher = eventPublisher;
    }

    public InvestmentTransactionResponse createTransaction(CreateInvestmentTransactionRequest request) {
        UUID userId = UserContext.getCurrentUserId();
        User user = userRepository.getReferenceById(userId);

        Account brokerAccount = accountRepository.findById(request.brokerAccountId())
                .orElseThrow(() -> new ResourceNotFoundException("Account", request.brokerAccountId()));

        if (brokerAccount.getType() != AccountType.broker) {
            throw new ValidationException("Account must be a broker account");
        }

        Instrument instrument = instrumentRepository.findById(request.instrumentId())
                .orElseThrow(() -> new ResourceNotFoundException("Instrument", request.instrumentId()));

        Holding holding = holdingRepository.findByBrokerAccountIdAndInstrumentId(brokerAccount.getId(), instrument.getId())
                .orElseGet(() -> {
                    Holding h = new Holding(brokerAccount, instrument, null);
                    h.setUser(user);
                    return holdingRepository.save(h);
                });

        if (request.type() == InvestmentTransactionType.sell) {
            HoldingPosition currentPos = calculateHoldingPosition(holding);
            if (request.quantity().compareTo(currentPos.openQty()) > 0) {
                throw new ValidationException("Cannot sell more than current open quantity: " + currentPos.openQty());
            }
        }

        InvestmentTransaction txn = new InvestmentTransaction();
        txn.setUser(user);
        txn.setHolding(holding);
        txn.setType(request.type());
        txn.setSettlementType(request.settlementType() != null ? request.settlementType() : SettlementType.delivery);
        txn.setQuantity(request.quantity());
        txn.setPrice(request.price());
        txn.setTradeDate(request.tradeDate());
        txn.setNotes(request.notes());

        applyItemizedCharges(txn, request.charges());

        InvestmentTransaction saved = transactionRepository.save(txn);

        // Keep the day's intraday classification (which drives the FIFO split) in sync with
        // the per-transaction settlement_type tags.
        recomputeClassificationForDay(holding, saved.getTradeDate());

        // Auto-fetch the latest price for this instrument once the trade commits, so the UI
        // reflects it without a manual price refresh (handled by PriceRefreshEventListener).
        eventPublisher.publishEvent(new PriceRefreshEvent(Set.of(instrument.getId())));

        return InvestmentTransactionResponse.from(saved);
    }

    public InvestmentTransactionResponse updateTransaction(UUID id, UpdateInvestmentTransactionRequest request) {
        InvestmentTransaction txn = transactionRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("InvestmentTransaction", id));

        UUID currentUserId = UserContext.getCurrentUserId();
        if (txn.getUser() == null || !txn.getUser().getId().equals(currentUserId)) {
            throw new ResourceNotFoundException("InvestmentTransaction", id);
        }

        LocalDate oldDate = txn.getTradeDate();

        txn.setType(request.type());
        if (request.settlementType() != null) {
            txn.setSettlementType(request.settlementType());
        }
        txn.setQuantity(request.quantity());
        txn.setPrice(request.price());
        txn.setTradeDate(request.tradeDate());
        txn.setNotes(request.notes());

        applyItemizedCharges(txn, request.charges());

        InvestmentTransaction saved = transactionRepository.save(txn);

        // Re-derive the intraday classification for the affected day(s) from the current
        // settlement_type tags, then validate FIFO consistency.
        recomputeClassificationForDay(saved.getHolding(), saved.getTradeDate());
        if (oldDate != null && !oldDate.equals(saved.getTradeDate())) {
            recomputeClassificationForDay(saved.getHolding(), oldDate);
        }
        validateHoldingFifo(saved.getHolding());

        return InvestmentTransactionResponse.from(saved);
    }

    public void deleteTransaction(UUID id) {
        InvestmentTransaction txn = transactionRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("InvestmentTransaction", id));

        UUID currentUserId = UserContext.getCurrentUserId();
        if (txn.getUser() == null || !txn.getUser().getId().equals(currentUserId)) {
            throw new ResourceNotFoundException("InvestmentTransaction", id);
        }

        Holding holding = txn.getHolding();
        LocalDate date = txn.getTradeDate();
        transactionRepository.delete(txn);
        transactionRepository.flush();

        // Re-derive the day's intraday classification without the deleted txn, then
        // validate FIFO consistency.
        recomputeClassificationForDay(holding, date);
        validateHoldingFifo(holding);
    }

    @Transactional(readOnly = true)
    public Page<InvestmentTransactionResponse> getTransactions(UUID brokerAccountId, UUID instrumentId, UUID holdingId, String search, Pageable pageable) {
        String normalizedSearch = (search != null && !search.isBlank()) ? search.trim() : null;
        Page<InvestmentTransaction> page = transactionRepository.findFilteredTransactions(
                brokerAccountId, instrumentId, holdingId, normalizedSearch, withStableSort(pageable));
        return page.map(InvestmentTransactionResponse::from);
    }

    /**
     * tradeDate is a day-granularity LocalDate, so many trades tie on it. Sorting by tradeDate
     * alone gives the DB freedom to order tied rows differently per page query, which makes a
     * transaction straddle a page boundary (missing on one page size, visible on another). Append
     * createdAt and the unique id as tiebreakers so pagination has a stable total ordering.
     */
    private Pageable withStableSort(Pageable pageable) {
        Sort sort = pageable.getSort();
        boolean hasUniqueKey = sort.stream().anyMatch(o -> o.getProperty().equals("id"));
        if (hasUniqueKey) {
            return pageable;
        }
        Sort.Direction direction = sort.stream().findFirst().map(Sort.Order::getDirection).orElse(Sort.Direction.ASC);
        Sort stableSort = sort
                .and(Sort.by(direction, "createdAt"))
                .and(Sort.by(direction, "id"));
        return PageRequest.of(pageable.getPageNumber(), pageable.getPageSize(), stableSort);
    }

    @Transactional(readOnly = true)
    public PositionsResponse getPositions() {
        List<Holding> holdings = holdingRepository.findAll();
        List<PositionDto> positions = new ArrayList<>();

        for (Holding holding : holdings) {
            HoldingPosition pos;
            try {
                pos = calculateHoldingPosition(holding);
            } catch (Exception e) {
                // One holding with inconsistent history (e.g. a tradebook whose earlier buys weren't
                // imported, so a sell can't be FIFO-matched) must not blank out every other holding.
                log.warn("Skipping holding {} ({}) in positions: {}",
                        holding.getId(), holding.getInstrument().getName(), e.getMessage());
                continue;
            }
            if (pos.openQty().compareTo(BigDecimal.ZERO) > 0) {
                positions.add(pos.toPositionDto());
            }
        }

        return new PositionsResponse(positions);
    }

    @Transactional(readOnly = true)
    public SummaryResponse getSummary() {
        List<Holding> holdings = holdingRepository.findAll();

        BigDecimal totalInvested = BigDecimal.ZERO;
        BigDecimal totalCurrentValue = BigDecimal.ZERO;
        BigDecimal totalRealized = BigDecimal.ZERO;
        BigDecimal totalIntradayRealized = BigDecimal.ZERO;
        BigDecimal totalCharges = BigDecimal.ZERO;

        Map<UUID, BrokerSummaryAccumulator> brokerMap = new LinkedHashMap<>();
        Map<InstrumentType, InstrumentTypeAccumulator> typeMap = new EnumMap<>(InstrumentType.class);

        List<XirrCalculator.Cashflow> portfolioCashflows = new ArrayList<>();

        for (Holding holding : holdings) {
            HoldingPosition pos;
            try {
                pos = calculateHoldingPosition(holding);
            } catch (Exception e) {
                // Skip a holding with inconsistent history rather than failing the whole summary.
                log.warn("Skipping holding {} ({}) in summary: {}",
                        holding.getId(), holding.getInstrument().getName(), e.getMessage());
                continue;
            }
            totalRealized = totalRealized.add(pos.realized());
            totalIntradayRealized = totalIntradayRealized.add(pos.intradayRealized());
            totalCharges = totalCharges.add(pos.totalCharges());

            if (pos.openQty().compareTo(BigDecimal.ZERO) > 0) {
                totalInvested = totalInvested.add(pos.openCost());
                if (pos.currentValue() != null) {
                    totalCurrentValue = totalCurrentValue.add(pos.currentValue());
                }
            }

            // Accumulate transaction cashflows
            List<InvestmentTransaction> txns = transactionRepository.findByHoldingIdOrderByTradeDateAscCreatedAtAsc(holding.getId());
            for (InvestmentTransaction txn : txns) {
                BigDecimal charges = txn.getTotalCharges() != null ? txn.getTotalCharges() : BigDecimal.ZERO;
                if (txn.getType() == InvestmentTransactionType.buy) {
                    BigDecimal outflow = txn.getQuantity().multiply(txn.getPrice()).add(charges);
                    portfolioCashflows.add(new XirrCalculator.Cashflow(txn.getTradeDate(), outflow.negate()));
                } else if (txn.getType() == InvestmentTransactionType.sell) {
                    BigDecimal inflow = txn.getQuantity().multiply(txn.getPrice()).subtract(charges);
                    portfolioCashflows.add(new XirrCalculator.Cashflow(txn.getTradeDate(), inflow));
                }
            }

            // Accumulate dividend cashflows
            List<Dividend> dividends = dividendRepository.findByHoldingIdOrderByPayDateDescCreatedAtDesc(holding.getId());
            for (Dividend div : dividends) {
                portfolioCashflows.add(new XirrCalculator.Cashflow(div.getPayDate(), div.getAmount()));
            }

            // Broker accumulation
            Account broker = holding.getBrokerAccount();
            String provider = broker.getBrokerDetails() != null ? broker.getBrokerDetails().getProvider() : null;
            BigDecimal cash = broker.getBrokerDetails() != null && broker.getBrokerDetails().getCashBalance() != null
                    ? broker.getBrokerDetails().getCashBalance()
                    : BigDecimal.ZERO;

            BrokerSummaryAccumulator brokerAcc = brokerMap.computeIfAbsent(broker.getId(),
                    k -> new BrokerSummaryAccumulator(broker.getId(), broker.getName(), provider, cash));

            if (pos.openQty().compareTo(BigDecimal.ZERO) > 0) {
                brokerAcc.invested = brokerAcc.invested.add(pos.openCost());
                if (pos.currentValue() != null) {
                    brokerAcc.currentValue = brokerAcc.currentValue.add(pos.currentValue());
                }
            }
            brokerAcc.realized = brokerAcc.realized.add(pos.realized());
            brokerAcc.intradayRealized = brokerAcc.intradayRealized.add(pos.intradayRealized());
            brokerAcc.totalCharges = brokerAcc.totalCharges.add(pos.totalCharges());

            // Instrument Type accumulation
            InstrumentType instType = holding.getInstrument().getType();
            InstrumentTypeAccumulator typeAcc = typeMap.computeIfAbsent(instType, k -> new InstrumentTypeAccumulator(instType));
            if (pos.openQty().compareTo(BigDecimal.ZERO) > 0) {
                typeAcc.invested = typeAcc.invested.add(pos.openCost());
                if (pos.currentValue() != null) {
                    typeAcc.currentValue = typeAcc.currentValue.add(pos.currentValue());
                }
            }
        }

        BigDecimal totalDividends = dividendRepository.sumTotalUserDividends();
        if (totalDividends == null) {
            totalDividends = BigDecimal.ZERO;
        }

        BigDecimal totalUnrealized = totalCurrentValue.subtract(totalInvested);
        BigDecimal totalUnrealizedPercent = totalInvested.compareTo(BigDecimal.ZERO) > 0
                ? totalUnrealized.divide(totalInvested, 4, RoundingMode.HALF_UP).multiply(new BigDecimal("100")).setScale(2, RoundingMode.HALF_UP)
                : BigDecimal.ZERO;
        BigDecimal totalPnl = totalRealized.add(totalIntradayRealized).add(totalUnrealized).add(totalDividends);

        // Portfolio terminal cashflow
        if (totalCurrentValue.compareTo(BigDecimal.ZERO) > 0) {
            portfolioCashflows.add(new XirrCalculator.Cashflow(LocalDate.now(), totalCurrentValue));
        }

        Double portfolioXirr = calculateXirrPercentage(portfolioCashflows);
        BigDecimal absoluteReturnPercent = totalInvested.compareTo(BigDecimal.ZERO) > 0
                ? totalPnl.divide(totalInvested, 4, RoundingMode.HALF_UP).multiply(new BigDecimal("100")).setScale(2, RoundingMode.HALF_UP)
                : BigDecimal.ZERO;

        List<SummaryResponse.BrokerSummaryDto> byBroker = brokerMap.values().stream()
                .map(b -> {
                    BigDecimal unrell = b.currentValue.subtract(b.invested);
                    return new SummaryResponse.BrokerSummaryDto(
                            b.brokerAccountId,
                            b.brokerName,
                            b.provider,
                            b.cashBalance,
                            b.invested.setScale(2, RoundingMode.HALF_UP),
                            b.currentValue.setScale(2, RoundingMode.HALF_UP),
                            b.realized.setScale(2, RoundingMode.HALF_UP),
                            b.intradayRealized.setScale(2, RoundingMode.HALF_UP),
                            unrell.setScale(2, RoundingMode.HALF_UP),
                            b.totalCharges.setScale(2, RoundingMode.HALF_UP)
                    );
                })
                .toList();

        final BigDecimal finalTotalCurrentValue = totalCurrentValue;
        List<SummaryResponse.InstrumentTypeSummaryDto> byInstrumentType = typeMap.values().stream()
                .map(t -> {
                    BigDecimal pct = finalTotalCurrentValue.compareTo(BigDecimal.ZERO) > 0
                            ? t.currentValue.divide(finalTotalCurrentValue, 4, RoundingMode.HALF_UP).multiply(new BigDecimal("100")).setScale(2, RoundingMode.HALF_UP)
                            : BigDecimal.ZERO;
                    return new SummaryResponse.InstrumentTypeSummaryDto(
                            t.type,
                            t.invested.setScale(2, RoundingMode.HALF_UP),
                            t.currentValue.setScale(2, RoundingMode.HALF_UP),
                            pct
                    );
                })
                .toList();

        return new SummaryResponse(
                totalInvested.setScale(2, RoundingMode.HALF_UP),
                totalCurrentValue.setScale(2, RoundingMode.HALF_UP),
                totalUnrealized.setScale(2, RoundingMode.HALF_UP),
                totalUnrealizedPercent,
                totalRealized.setScale(2, RoundingMode.HALF_UP),
                totalIntradayRealized.setScale(2, RoundingMode.HALF_UP),
                totalCharges.setScale(2, RoundingMode.HALF_UP),
                totalDividends.setScale(2, RoundingMode.HALF_UP),
                totalPnl.setScale(2, RoundingMode.HALF_UP),
                portfolioXirr,
                absoluteReturnPercent,
                byBroker,
                byInstrumentType
        );
    }

    private void applyItemizedCharges(InvestmentTransaction txn, ItemizedChargesDto charges) {
        if (charges != null) {
            txn.setBrokerage(charges.brokerage());
            txn.setStt(charges.stt());
            txn.setExchangeTxnCharges(charges.exchangeTxnCharges());
            txn.setSebiCharges(charges.sebiCharges());
            txn.setStampDuty(charges.stampDuty());
            txn.setGst(charges.gst());
            txn.setDpCharges(charges.dpCharges());
            txn.setOtherCharges(charges.otherCharges());
        }
    }

    private void validateHoldingFifo(Holding holding) {
        try {
            calculateHoldingPosition(holding);
        } catch (Exception e) {
            throw new ValidationException("Transaction modification violates FIFO lot availability: " + e.getMessage());
        }
    }

    private HoldingPosition calculateHoldingPosition(Holding holding) {
        List<InvestmentTransaction> txns = transactionRepository.findByHoldingIdOrderByTradeDateAscCreatedAtAsc(holding.getId());
        List<CorporateAction> corpActions = corporateActionRepository.findByInstrumentIdOrderByExDateAsc(holding.getInstrument().getId());

        // Check if this holding's instrument is the target of any demerger corporate actions
        List<CorporateAction> demergerCAs = corporateActionRepository.findByTargetInstrumentIdOrderByExDateAsc(holding.getInstrument().getId());
        List<DemergerSeedEvent> demergerSeedEvents = new ArrayList<>();
        for (CorporateAction ca : demergerCAs) {
            Optional<Holding> parentHoldingOpt = holdingRepository.findByBrokerAccountIdAndInstrumentId(
                    holding.getBrokerAccount().getId(),
                    ca.getInstrument().getId()
            );
            if (parentHoldingOpt.isPresent() && ca.getCostAllocationPct() != null && ca.getRatioFrom() != null && ca.getRatioFrom() > 0 && ca.getRatioTo() != null && ca.getRatioTo() > 0) {
                List<Lot> parentOpenLots = buildParentOpenLotsBeforeCa(parentHoldingOpt.get(), ca);
                for (Lot parentLot : parentOpenLots) {
                    BigDecimal childQty = parentLot.remainingQty
                            .multiply(BigDecimal.valueOf(ca.getRatioTo()))
                            .divide(BigDecimal.valueOf(ca.getRatioFrom()), 10, RoundingMode.HALF_UP)
                            .setScale(8, RoundingMode.HALF_UP);
                    BigDecimal childCost = parentLot.remainingQty
                            .multiply(parentLot.costPerUnit)
                            .multiply(ca.getCostAllocationPct())
                            .divide(BigDecimal.valueOf(100), 10, RoundingMode.HALF_UP);
                    if (childQty.compareTo(BigDecimal.ZERO) > 0) {
                        BigDecimal costPerUnit = childCost.divide(childQty, 8, RoundingMode.HALF_UP);
                        demergerSeedEvents.add(new DemergerSeedEvent(ca.getExDate(), childQty, costPerUnit));
                    }
                }
            }
        }

        List<TradeSettlementClassification> classifications = classificationRepository.findByHoldingId(holding.getId());
        Map<LocalDate, TradeSettlementClassification> classMap = new HashMap<>();
        for (TradeSettlementClassification c : classifications) {
            classMap.put(c.getTradeDate(), c);
        }

        BigDecimal intradayRealized = BigDecimal.ZERO;
        BigDecimal totalHoldingCharges = BigDecimal.ZERO;
        List<XirrCalculator.Cashflow> cashflows = new ArrayList<>();
        List<TimelineEvent> timeline = new ArrayList<>();

        // Group raw transactions by trade date
        Map<LocalDate, List<InvestmentTransaction>> txnsByDate = new LinkedHashMap<>();
        for (InvestmentTransaction txn : txns) {
            txnsByDate.computeIfAbsent(txn.getTradeDate(), k -> new ArrayList<>()).add(txn);
        }

        for (Map.Entry<LocalDate, List<InvestmentTransaction>> entry : txnsByDate.entrySet()) {
            LocalDate date = entry.getKey();
            List<InvestmentTransaction> dayTxns = entry.getValue();

            BigDecimal dayCharges = BigDecimal.ZERO;
            for (InvestmentTransaction t : dayTxns) {
                if (t.getTotalCharges() != null) {
                    dayCharges = dayCharges.add(t.getTotalCharges());
                }
            }
            totalHoldingCharges = totalHoldingCharges.add(dayCharges);

            if (classMap.containsKey(date)) {
                TradeSettlementClassification c = classMap.get(date);
                BigDecimal intradayQty = c.getIntradayQty();
                BigDecimal intradayBuyVal = c.getIntradayBuyValue();
                BigDecimal intradaySellVal = c.getIntradaySellValue();

                BigDecimal dayIntradayRealized = intradaySellVal.subtract(intradayBuyVal);
                intradayRealized = intradayRealized.add(dayIntradayRealized);

                BigDecimal dayBuyQty = BigDecimal.ZERO;
                BigDecimal dayBuyVal = BigDecimal.ZERO;
                BigDecimal daySellQty = BigDecimal.ZERO;
                BigDecimal daySellVal = BigDecimal.ZERO;

                for (InvestmentTransaction t : dayTxns) {
                    BigDecimal val = t.getQuantity().multiply(t.getPrice());
                    if (t.getType() == InvestmentTransactionType.buy) {
                        dayBuyQty = dayBuyQty.add(t.getQuantity());
                        dayBuyVal = dayBuyVal.add(val);
                    } else {
                        daySellQty = daySellQty.add(t.getQuantity());
                        daySellVal = daySellVal.add(val);
                    }
                }

                BigDecimal delivBuyQty = dayBuyQty.subtract(intradayQty).max(BigDecimal.ZERO);
                BigDecimal delivBuyVal = dayBuyVal.subtract(intradayBuyVal).max(BigDecimal.ZERO);
                BigDecimal delivSellQty = daySellQty.subtract(intradayQty).max(BigDecimal.ZERO);
                BigDecimal delivSellVal = daySellVal.subtract(intradaySellVal).max(BigDecimal.ZERO);

                if (delivBuyQty.compareTo(BigDecimal.ZERO) > 0) {
                    BigDecimal costPerUnit = delivBuyVal.divide(delivBuyQty, 8, RoundingMode.HALF_UP);
                    InvestmentTransaction delivBuyTxn = new InvestmentTransaction();
                    delivBuyTxn.setTradeDate(date);
                    delivBuyTxn.setType(InvestmentTransactionType.buy);
                    delivBuyTxn.setQuantity(delivBuyQty);
                    delivBuyTxn.setPrice(costPerUnit);
                    delivBuyTxn.setTotalCharges(BigDecimal.ZERO);
                    timeline.add(new TxnEvent(delivBuyTxn));
                }

                if (delivSellQty.compareTo(BigDecimal.ZERO) > 0) {
                    BigDecimal sellPrice = delivSellVal.divide(delivSellQty, 8, RoundingMode.HALF_UP);
                    InvestmentTransaction delivSellTxn = new InvestmentTransaction();
                    delivSellTxn.setTradeDate(date);
                    delivSellTxn.setType(InvestmentTransactionType.sell);
                    delivSellTxn.setQuantity(delivSellQty);
                    delivSellTxn.setPrice(sellPrice);
                    delivSellTxn.setTotalCharges(BigDecimal.ZERO);
                    timeline.add(new TxnEvent(delivSellTxn));
                }

                // XIRR: the delivery leg's cashflows are emitted by the synthetic
                // delivery txns below (in the FIFO loop). Here we add ONLY the intraday
                // net flow (plus the day's charges) so the delivery leg is not counted twice.
                BigDecimal intradayDayCashflow = intradaySellVal
                        .subtract(intradayBuyVal)
                        .subtract(dayCharges);
                cashflows.add(new XirrCalculator.Cashflow(date, intradayDayCashflow));
            } else {
                for (InvestmentTransaction t : dayTxns) {
                    timeline.add(new TxnEvent(t));
                }
            }
        }

        for (CorporateAction ca : corpActions) {
            timeline.add(new CorpActionEvent(ca));
        }
        for (DemergerSeedEvent seed : demergerSeedEvents) {
            timeline.add(seed);
        }

        timeline.sort((e1, e2) -> {
            int dateCompare = e1.date().compareTo(e2.date());
            if (dateCompare != 0) {
                return dateCompare;
            }
            int order1 = getEventOrder(e1);
            int order2 = getEventOrder(e2);
            return Integer.compare(order1, order2);
        });

        LinkedList<Lot> openLots = new LinkedList<>();
        BigDecimal cumulativeRealized = BigDecimal.ZERO;

        for (TimelineEvent event : timeline) {
            if (event instanceof DemergerSeedEvent seed) {
                openLots.add(new Lot(seed.qty(), seed.costPerUnit()));
            } else if (event instanceof CorpActionEvent caEvent) {
                CorporateAction ca = caEvent.action();
                if (ca.getType() == CorporateActionType.demerger) {
                    if (ca.getCostAllocationPct() != null && ca.getCostAllocationPct().compareTo(BigDecimal.ZERO) > 0) {
                        BigDecimal factor = BigDecimal.ONE.subtract(
                                ca.getCostAllocationPct().divide(BigDecimal.valueOf(100), 10, RoundingMode.HALF_UP)
                        );
                        for (Lot lot : openLots) {
                            lot.costPerUnit = lot.costPerUnit.multiply(factor).setScale(8, RoundingMode.HALF_UP);
                        }
                    }
                } else if (ca.getRatioFrom() != null && ca.getRatioFrom() > 0 && ca.getRatioTo() != null && ca.getRatioTo() > 0) {
                    BigDecimal multiplier = BigDecimal.valueOf(ca.getRatioTo())
                            .divide(BigDecimal.valueOf(ca.getRatioFrom()), 10, RoundingMode.HALF_UP);
                    for (Lot lot : openLots) {
                        lot.remainingQty = lot.remainingQty.multiply(multiplier).setScale(8, RoundingMode.HALF_UP);
                        lot.costPerUnit = lot.costPerUnit.divide(multiplier, 8, RoundingMode.HALF_UP);
                    }
                }
            } else if (event instanceof TxnEvent txnEvent) {
                InvestmentTransaction txn = txnEvent.txn();
                // NOTE: charges are already accumulated once per day (dayCharges) when
                // building the timeline above. Do NOT re-add them here — delivery-only
                // days would otherwise double-count charges. txnCharges is still used
                // locally for the XIRR net-proceeds / outflow figures.
                BigDecimal txnCharges = txn.getTotalCharges() != null ? txn.getTotalCharges() : BigDecimal.ZERO;

                if (txn.getType() == InvestmentTransactionType.buy) {
                    // Clean cost basis: costPerUnit uses traded price ONLY (no + txnCharges)
                    BigDecimal costPerUnit = txn.getPrice();
                    openLots.add(new Lot(txn.getQuantity(), costPerUnit));

                    BigDecimal totalOutflow = txn.getQuantity().multiply(txn.getPrice()).add(txnCharges);
                    cashflows.add(new XirrCalculator.Cashflow(txn.getTradeDate(), totalOutflow.negate()));
                } else if (txn.getType() == InvestmentTransactionType.sell) {
                    BigDecimal sellQty = txn.getQuantity();
                    BigDecimal grossProceeds = sellQty.multiply(txn.getPrice());
                    BigDecimal netProceeds = grossProceeds.subtract(txnCharges);

                    BigDecimal matchedCost = BigDecimal.ZERO;
                    BigDecimal qtyToMatch = sellQty;

                    while (qtyToMatch.compareTo(BigDecimal.ZERO) > 0) {
                        if (openLots.isEmpty()) {
                            // Incomplete tradebook history (or an off-market removal):
                            // sold more than the imported buys. Match what we can and
                            // stop, rather than failing the whole portfolio calculation.
                            log.warn("Holding {}: sell of {} exceeds available buy lots ({} unmatched) — incomplete history",
                                    holding.getId(), sellQty, qtyToMatch);
                            break;
                        }
                        Lot oldestLot = openLots.peek();
                        BigDecimal takeQty = qtyToMatch.min(oldestLot.remainingQty);
                        matchedCost = matchedCost.add(takeQty.multiply(oldestLot.costPerUnit));
                        oldestLot.remainingQty = oldestLot.remainingQty.subtract(takeQty);
                        qtyToMatch = qtyToMatch.subtract(takeQty);

                        if (oldestLot.remainingQty.compareTo(BigDecimal.ZERO) == 0) {
                            openLots.poll();
                        }
                    }

                    // Gross realized P&L (gross proceeds - matched clean cost)
                    BigDecimal txnRealized = grossProceeds.subtract(matchedCost);
                    cumulativeRealized = cumulativeRealized.add(txnRealized);

                    cashflows.add(new XirrCalculator.Cashflow(txn.getTradeDate(), netProceeds));
                }
            }
        }

        BigDecimal openQty = BigDecimal.ZERO;
        BigDecimal openCost = BigDecimal.ZERO;
        for (Lot lot : openLots) {
            openQty = openQty.add(lot.remainingQty);
            openCost = openCost.add(lot.remainingQty.multiply(lot.costPerUnit));
        }

        BigDecimal avgCost = openQty.compareTo(BigDecimal.ZERO) > 0
                ? openCost.divide(openQty, 4, RoundingMode.HALF_UP)
                : BigDecimal.ZERO;

        Optional<InstrumentPrice> latestPrice = priceRepository.findTopByInstrumentIdOrderByAsOfDesc(holding.getInstrument().getId());
        BigDecimal priceClose = latestPrice.map(InstrumentPrice::getClose).orElse(null);
        LocalDate priceAsOf = latestPrice.map(InstrumentPrice::getAsOf).orElse(null);
        PriceSource priceSource = latestPrice.map(InstrumentPrice::getSource).orElse(null);

        BigDecimal currentValue = null;
        BigDecimal unrealized = null;
        BigDecimal unrealizedPercent = null;

        if (priceClose != null && openQty.compareTo(BigDecimal.ZERO) > 0) {
            currentValue = openQty.multiply(priceClose).setScale(4, RoundingMode.HALF_UP);
            unrealized = currentValue.subtract(openCost).setScale(4, RoundingMode.HALF_UP);
            unrealizedPercent = openCost.compareTo(BigDecimal.ZERO) > 0
                    ? unrealized.divide(openCost, 4, RoundingMode.HALF_UP).multiply(new BigDecimal("100")).setScale(2, RoundingMode.HALF_UP)
                    : BigDecimal.ZERO;
        }

        // Add dividends to cashflows
        BigDecimal holdingDividends = dividendRepository.sumAmountByHoldingId(holding.getId());
        if (holdingDividends == null) {
            holdingDividends = BigDecimal.ZERO;
        }

        List<Dividend> dividendsList = dividendRepository.findByHoldingIdOrderByPayDateDescCreatedAtDesc(holding.getId());
        for (Dividend div : dividendsList) {
            cashflows.add(new XirrCalculator.Cashflow(div.getPayDate(), div.getAmount()));
        }

        // Terminal cashflow for XIRR
        if (currentValue != null && openQty.compareTo(BigDecimal.ZERO) > 0) {
            cashflows.add(new XirrCalculator.Cashflow(LocalDate.now(), currentValue));
        }

        Double xirr = calculateXirrPercentage(cashflows);

        BigDecimal absoluteReturnPercent = null;
        if (currentValue != null && openCost.compareTo(BigDecimal.ZERO) > 0) {
            BigDecimal totalHoldingGain = currentValue
                    .subtract(openCost)
                    .add(cumulativeRealized)
                    .add(intradayRealized)
                    .add(holdingDividends);
            absoluteReturnPercent = totalHoldingGain.divide(openCost, 4, RoundingMode.HALF_UP)
                    .multiply(new BigDecimal("100"))
                    .setScale(2, RoundingMode.HALF_UP);
        }

        return new HoldingPosition(
                holding,
                openQty.setScale(8, RoundingMode.HALF_UP),
                avgCost.setScale(4, RoundingMode.HALF_UP),
                openCost.setScale(4, RoundingMode.HALF_UP),
                priceClose,
                priceAsOf,
                priceSource,
                currentValue,
                unrealized,
                unrealizedPercent,
                cumulativeRealized.setScale(4, RoundingMode.HALF_UP),
                intradayRealized.setScale(4, RoundingMode.HALF_UP),
                totalHoldingCharges.setScale(4, RoundingMode.HALF_UP),
                holdingDividends.setScale(2, RoundingMode.HALF_UP),
                xirr,
                absoluteReturnPercent
        );
    }

    private Double calculateXirrPercentage(List<XirrCalculator.Cashflow> cashflows) {
        Double rawXirr = XirrCalculator.calculateXirr(cashflows);
        if (rawXirr == null || Double.isNaN(rawXirr) || Double.isInfinite(rawXirr)) {
            return null;
        }
        return BigDecimal.valueOf(rawXirr)
                .multiply(new BigDecimal("100"))
                .setScale(2, RoundingMode.HALF_UP)
                .doubleValue();
    }

    private interface TimelineEvent {
        LocalDate date();
    }

    private record TxnEvent(InvestmentTransaction txn) implements TimelineEvent {
        @Override
        public LocalDate date() {
            return txn.getTradeDate();
        }
    }

    private record CorpActionEvent(CorporateAction action) implements TimelineEvent {
        @Override
        public LocalDate date() {
            return action.getExDate();
        }
    }

    private record DemergerSeedEvent(LocalDate date, BigDecimal qty, BigDecimal costPerUnit) implements TimelineEvent {}

    private int getEventOrder(TimelineEvent e) {
        if (e instanceof DemergerSeedEvent) return 0;
        if (e instanceof CorpActionEvent) return 1;
        return 2;
    }

    /**
     * Re-derive the intraday classification row for (holding, date) from the current
     * per-transaction settlement_type tags. This makes settlement_type the editable source of
     * truth — the FIFO split in calculateHoldingPosition keys off this classification.
     * intradayQty = min(intraday buys, intraday sells), so imperfect tagging degrades to
     * delivery rather than producing wrong holdings.
     */
    private void recomputeClassificationForDay(Holding holding, LocalDate date) {
        List<InvestmentTransaction> dayTxns = transactionRepository
                .findByHoldingIdOrderByTradeDateAscCreatedAtAsc(holding.getId())
                .stream().filter(t -> date.equals(t.getTradeDate())).toList();

        BigDecimal iBuyQty = BigDecimal.ZERO, iBuyVal = BigDecimal.ZERO;
        BigDecimal iSellQty = BigDecimal.ZERO, iSellVal = BigDecimal.ZERO;
        for (InvestmentTransaction t : dayTxns) {
            if (t.getSettlementType() != SettlementType.intraday) continue;
            BigDecimal val = t.getQuantity().multiply(t.getPrice());
            if (t.getType() == InvestmentTransactionType.buy) {
                iBuyQty = iBuyQty.add(t.getQuantity());
                iBuyVal = iBuyVal.add(val);
            } else {
                iSellQty = iSellQty.add(t.getQuantity());
                iSellVal = iSellVal.add(val);
            }
        }

        Optional<TradeSettlementClassification> existing = classificationRepository
                .findByBrokerAccountIdAndInstrumentIdAndTradeDate(
                        holding.getBrokerAccount().getId(), holding.getInstrument().getId(), date);

        BigDecimal intradayQty = iBuyQty.min(iSellQty);
        if (intradayQty.compareTo(BigDecimal.ZERO) <= 0) {
            existing.ifPresent(classificationRepository::delete);
            return;
        }

        BigDecimal intradayBuyValue = iBuyQty.compareTo(BigDecimal.ZERO) > 0
                ? iBuyVal.multiply(intradayQty).divide(iBuyQty, 4, RoundingMode.HALF_UP) : BigDecimal.ZERO;
        BigDecimal intradaySellValue = iSellQty.compareTo(BigDecimal.ZERO) > 0
                ? iSellVal.multiply(intradayQty).divide(iSellQty, 4, RoundingMode.HALF_UP) : BigDecimal.ZERO;

        TradeSettlementClassification c = existing.orElseGet(() -> new TradeSettlementClassification(
                holding.getUser(), holding.getBrokerAccount(), holding, holding.getInstrument(), date,
                intradayQty, intradayBuyValue, intradaySellValue));
        c.setIntradayQty(intradayQty);
        c.setIntradayBuyValue(intradayBuyValue);
        c.setIntradaySellValue(intradaySellValue);
        classificationRepository.save(c);
    }

    private List<Lot> buildParentOpenLotsBeforeCa(Holding parentHolding, CorporateAction demergerCa) {
        List<InvestmentTransaction> txns = transactionRepository.findByHoldingIdOrderByTradeDateAscCreatedAtAsc(parentHolding.getId());
        List<CorporateAction> corpActions = corporateActionRepository.findByInstrumentIdOrderByExDateAsc(parentHolding.getInstrument().getId());

        List<TradeSettlementClassification> classifications = classificationRepository.findByHoldingId(parentHolding.getId());
        Map<LocalDate, TradeSettlementClassification> classMap = new HashMap<>();
        for (TradeSettlementClassification c : classifications) {
            if (c.getTradeDate().compareTo(demergerCa.getExDate()) <= 0) {
                classMap.put(c.getTradeDate(), c);
            }
        }

        List<TimelineEvent> timeline = new ArrayList<>();
        Map<LocalDate, List<InvestmentTransaction>> txnsByDate = new LinkedHashMap<>();
        for (InvestmentTransaction txn : txns) {
            if (txn.getTradeDate().compareTo(demergerCa.getExDate()) <= 0) {
                txnsByDate.computeIfAbsent(txn.getTradeDate(), k -> new ArrayList<>()).add(txn);
            }
        }

        for (Map.Entry<LocalDate, List<InvestmentTransaction>> entry : txnsByDate.entrySet()) {
            LocalDate date = entry.getKey();
            List<InvestmentTransaction> dayTxns = entry.getValue();

            if (classMap.containsKey(date)) {
                TradeSettlementClassification c = classMap.get(date);
                BigDecimal intradayQty = c.getIntradayQty();
                BigDecimal intradayBuyVal = c.getIntradayBuyValue();
                BigDecimal intradaySellVal = c.getIntradaySellValue();

                BigDecimal dayBuyQty = BigDecimal.ZERO;
                BigDecimal dayBuyVal = BigDecimal.ZERO;
                BigDecimal daySellQty = BigDecimal.ZERO;
                BigDecimal daySellVal = BigDecimal.ZERO;

                for (InvestmentTransaction t : dayTxns) {
                    BigDecimal val = t.getQuantity().multiply(t.getPrice());
                    if (t.getType() == InvestmentTransactionType.buy) {
                        dayBuyQty = dayBuyQty.add(t.getQuantity());
                        dayBuyVal = dayBuyVal.add(val);
                    } else {
                        daySellQty = daySellQty.add(t.getQuantity());
                        daySellVal = daySellVal.add(val);
                    }
                }

                BigDecimal delivBuyQty = dayBuyQty.subtract(intradayQty).max(BigDecimal.ZERO);
                BigDecimal delivBuyVal = dayBuyVal.subtract(intradayBuyVal).max(BigDecimal.ZERO);
                BigDecimal delivSellQty = daySellQty.subtract(intradayQty).max(BigDecimal.ZERO);
                BigDecimal delivSellVal = daySellVal.subtract(intradaySellVal).max(BigDecimal.ZERO);

                if (delivBuyQty.compareTo(BigDecimal.ZERO) > 0) {
                    BigDecimal costPerUnit = delivBuyVal.divide(delivBuyQty, 8, RoundingMode.HALF_UP);
                    InvestmentTransaction delivBuyTxn = new InvestmentTransaction();
                    delivBuyTxn.setTradeDate(date);
                    delivBuyTxn.setType(InvestmentTransactionType.buy);
                    delivBuyTxn.setQuantity(delivBuyQty);
                    delivBuyTxn.setPrice(costPerUnit);
                    delivBuyTxn.setTotalCharges(BigDecimal.ZERO);
                    timeline.add(new TxnEvent(delivBuyTxn));
                }

                if (delivSellQty.compareTo(BigDecimal.ZERO) > 0) {
                    BigDecimal sellPrice = delivSellVal.divide(delivSellQty, 8, RoundingMode.HALF_UP);
                    InvestmentTransaction delivSellTxn = new InvestmentTransaction();
                    delivSellTxn.setTradeDate(date);
                    delivSellTxn.setType(InvestmentTransactionType.sell);
                    delivSellTxn.setQuantity(delivSellQty);
                    delivSellTxn.setPrice(sellPrice);
                    delivSellTxn.setTotalCharges(BigDecimal.ZERO);
                    timeline.add(new TxnEvent(delivSellTxn));
                }
            } else {
                for (InvestmentTransaction t : dayTxns) {
                    timeline.add(new TxnEvent(t));
                }
            }
        }
        for (CorporateAction ca : corpActions) {
            if (!ca.getId().equals(demergerCa.getId()) && ca.getExDate().compareTo(demergerCa.getExDate()) <= 0) {
                timeline.add(new CorpActionEvent(ca));
            }
        }

        timeline.sort((e1, e2) -> {
            int dateCompare = e1.date().compareTo(e2.date());
            if (dateCompare != 0) {
                return dateCompare;
            }
            if (e1 instanceof CorpActionEvent && e2 instanceof TxnEvent) {
                return -1;
            }
            if (e1 instanceof TxnEvent && e2 instanceof CorpActionEvent) {
                return 1;
            }
            return 0;
        });

        LinkedList<Lot> openLots = new LinkedList<>();

        for (TimelineEvent event : timeline) {
            if (event instanceof CorpActionEvent caEvent) {
                CorporateAction ca = caEvent.action();
                if (ca.getType() == CorporateActionType.demerger) {
                    if (ca.getCostAllocationPct() != null && ca.getCostAllocationPct().compareTo(BigDecimal.ZERO) > 0) {
                        BigDecimal factor = BigDecimal.ONE.subtract(
                                ca.getCostAllocationPct().divide(BigDecimal.valueOf(100), 10, RoundingMode.HALF_UP)
                        );
                        for (Lot lot : openLots) {
                            lot.costPerUnit = lot.costPerUnit.multiply(factor).setScale(8, RoundingMode.HALF_UP);
                        }
                    }
                } else if (ca.getRatioFrom() != null && ca.getRatioFrom() > 0 && ca.getRatioTo() != null && ca.getRatioTo() > 0) {
                    BigDecimal multiplier = BigDecimal.valueOf(ca.getRatioTo())
                            .divide(BigDecimal.valueOf(ca.getRatioFrom()), 10, RoundingMode.HALF_UP);
                    for (Lot lot : openLots) {
                        lot.remainingQty = lot.remainingQty.multiply(multiplier).setScale(8, RoundingMode.HALF_UP);
                        lot.costPerUnit = lot.costPerUnit.divide(multiplier, 8, RoundingMode.HALF_UP);
                    }
                }
            } else if (event instanceof TxnEvent txnEvent) {
                InvestmentTransaction txn = txnEvent.txn();

                if (txn.getType() == InvestmentTransactionType.buy) {
                    BigDecimal costPerUnit = txn.getPrice();
                    openLots.add(new Lot(txn.getQuantity(), costPerUnit));
                } else if (txn.getType() == InvestmentTransactionType.sell) {
                    BigDecimal qtyToMatch = txn.getQuantity();
                    while (qtyToMatch.compareTo(BigDecimal.ZERO) > 0) {
                        if (openLots.isEmpty()) {
                            break;
                        }
                        Lot oldestLot = openLots.peek();
                        BigDecimal takeQty = qtyToMatch.min(oldestLot.remainingQty);
                        oldestLot.remainingQty = oldestLot.remainingQty.subtract(takeQty);
                        qtyToMatch = qtyToMatch.subtract(takeQty);

                        if (oldestLot.remainingQty.compareTo(BigDecimal.ZERO) == 0) {
                            openLots.poll();
                        }
                    }
                }
            }
        }

        return openLots;
    }

    private static class Lot {
        BigDecimal remainingQty;
        BigDecimal costPerUnit;

        Lot(BigDecimal remainingQty, BigDecimal costPerUnit) {
            this.remainingQty = remainingQty;
            this.costPerUnit = costPerUnit;
        }
    }

    private record HoldingPosition(
            Holding holding,
            BigDecimal openQty,
            BigDecimal avgCost,
            BigDecimal openCost,
            BigDecimal latestPrice,
            LocalDate priceAsOf,
            PriceSource priceSource,
            BigDecimal currentValue,
            BigDecimal unrealized,
            BigDecimal unrealizedPercent,
            BigDecimal realized,
            BigDecimal intradayRealized,
            BigDecimal totalCharges,
            BigDecimal dividends,
            Double xirr,
            BigDecimal absoluteReturnPercent
    ) {
        PositionDto toPositionDto() {
            Account b = holding.getBrokerAccount();
            String provider = b.getBrokerDetails() != null ? b.getBrokerDetails().getProvider() : null;

            PositionDto.InstrumentInfoDto instInfo = new PositionDto.InstrumentInfoDto(
                    holding.getInstrument().getId(),
                    holding.getInstrument().getType(),
                    holding.getInstrument().getName(),
                    holding.getInstrument().getSymbol(),
                    holding.getInstrument().getIsin(),
                    holding.getInstrument().getAmfiCode(),
                    holding.getInstrument().getYahooSymbol(),
                    priceSource
            );

            return new PositionDto(
                    holding.getId(),
                    b.getId(),
                    b.getName(),
                    provider,
                    instInfo,
                    openQty,
                    avgCost,
                    openCost,
                    latestPrice,
                    priceAsOf,
                    priceSource,
                    currentValue,
                    unrealized,
                    unrealizedPercent,
                    realized,
                    intradayRealized,
                    dividends,
                    xirr,
                    absoluteReturnPercent,
                    totalCharges,
                    holding.getNotes()
            );
        }
    }

    private static class BrokerSummaryAccumulator {
        UUID brokerAccountId;
        String brokerName;
        String provider;
        BigDecimal cashBalance;
        BigDecimal invested = BigDecimal.ZERO;
        BigDecimal currentValue = BigDecimal.ZERO;
        BigDecimal realized = BigDecimal.ZERO;
        BigDecimal intradayRealized = BigDecimal.ZERO;
        BigDecimal totalCharges = BigDecimal.ZERO;

        BrokerSummaryAccumulator(UUID brokerAccountId, String brokerName, String provider, BigDecimal cashBalance) {
            this.brokerAccountId = brokerAccountId;
            this.brokerName = brokerName;
            this.provider = provider;
            this.cashBalance = cashBalance;
        }
    }

    private static class InstrumentTypeAccumulator {
        InstrumentType type;
        BigDecimal invested = BigDecimal.ZERO;
        BigDecimal currentValue = BigDecimal.ZERO;

        InstrumentTypeAccumulator(InstrumentType type) {
            this.type = type;
        }
    }
}
