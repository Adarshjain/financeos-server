package com.financeos.domain.investment;

import com.financeos.api.investment.dto.*;
import com.financeos.core.exception.ResourceNotFoundException;
import com.financeos.core.exception.ValidationException;
import com.financeos.core.security.UserContext;
import com.financeos.core.time.AppTime;
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
import com.financeos.domain.investment.fno.FnoTradeRepository;
import com.financeos.domain.investment.returncalc.XirrCalculator;
import com.financeos.domain.user.User;
import com.financeos.domain.user.UserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.lang.Nullable;
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
    private final FnoTradeRepository fnoTradeRepository;
    /** Per-user asset-class overrides; null in lot-engine unit tests (then nobody has one). */
    @Nullable
    private final AssetClassOverrideService overrideService;

    /** Without per-user asset-class overrides (lot-engine unit tests). */
    public InvestmentService(InvestmentTransactionRepository transactionRepository,
                              HoldingRepository holdingRepository,
                              AccountRepository accountRepository,
                              InstrumentRepository instrumentRepository,
                              InstrumentPriceRepository priceRepository,
                              UserRepository userRepository,
                              CorporateActionRepository corporateActionRepository,
                              DividendRepository dividendRepository,
                              TradeSettlementClassificationRepository classificationRepository,
                              ApplicationEventPublisher eventPublisher,
                              FnoTradeRepository fnoTradeRepository) {
        this(transactionRepository, holdingRepository, accountRepository, instrumentRepository, priceRepository,
                userRepository, corporateActionRepository, dividendRepository, classificationRepository, eventPublisher,
                fnoTradeRepository, null);
    }

    @Autowired
    public InvestmentService(InvestmentTransactionRepository transactionRepository,
                              HoldingRepository holdingRepository,
                              AccountRepository accountRepository,
                              InstrumentRepository instrumentRepository,
                              InstrumentPriceRepository priceRepository,
                              UserRepository userRepository,
                              CorporateActionRepository corporateActionRepository,
                              DividendRepository dividendRepository,
                              TradeSettlementClassificationRepository classificationRepository,
                              ApplicationEventPublisher eventPublisher,
                              FnoTradeRepository fnoTradeRepository,
                              @Nullable AssetClassOverrideService overrideService) {
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
        this.fnoTradeRepository = fnoTradeRepository;
        this.overrideService = overrideService;
    }

    /**
     * The current user's instrument overrides — display fields and asset class — for every service
     * that shows instruments (one query; {@link InstrumentOverrides#NONE} without a user).
     */
    @Transactional(readOnly = true)
    public InstrumentOverrides instrumentOverrides() {
        return overridesOf(UserContext.getCurrentUserId());
    }

    /** {@code userId}'s instrument overrides ({@link InstrumentOverrides#NONE} for none / no user). */
    public InstrumentOverrides instrumentOverridesOf(@Nullable UUID userId) {
        return overridesOf(userId);
    }

    private InstrumentOverrides overridesOf(@Nullable UUID userId) {
        return overrideService == null || userId == null
                ? InstrumentOverrides.NONE
                : InstrumentOverrides.orNone(overrideService.overridesFor(userId));
    }

    /** Whose view a holding is computed in: the current user, else (outside a request) its owner. */
    @Nullable
    private static UUID viewerOf(Holding holding) {
        UUID userId = UserContext.getCurrentUserId();
        if (userId == null && holding.getUser() != null) {
            userId = holding.getUser().getId();
        }
        return userId;
    }

    /**
     * The user a holding belongs to, whose corporate actions apply to it (corporate actions are per
     * user); null for an owner-less holding, which then has none.
     */
    @Nullable
    private static UUID ownerOf(Holding holding) {
        return holding.getUser() != null ? holding.getUser().getId() : null;
    }

    /** A holding's instrument as its owner holds it: the key of per-owner corporate-action lookups. */
    private record OwnedInstrument(@Nullable UUID ownerId, UUID instrumentId) {
        static OwnedInstrument of(Holding holding) {
            return new OwnedInstrument(ownerOf(holding), holding.getInstrument().getId());
        }
    }

    /** The viewer of a batch of one user's holdings (see {@link #viewerOf(Holding)}). */
    @Nullable
    private static UUID viewerOf(List<Holding> holdings) {
        UUID userId = UserContext.getCurrentUserId();
        return userId != null || holdings.isEmpty() ? userId : viewerOf(holdings.get(0));
    }

    /** The holding's effective classification for its viewer (their overrides over the global class). */
    private AssetClassifier.Classification classificationOf(Holding holding) {
        return overridesOf(viewerOf(holding)).classification(holding.getInstrument());
    }

    /** A realised lot with the instrument's name and type as {@code overrides}' user sees them. */
    private static com.financeos.domain.investment.dto.RealizedLot asSeen(
            com.financeos.domain.investment.dto.RealizedLot lot, Instrument instrument, InstrumentOverrides overrides) {
        return lot.withInstrument(overrides.name(instrument), overrides.type(instrument));
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

        return InvestmentTransactionResponse.from(saved, instrumentOverrides());
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

        return InvestmentTransactionResponse.from(saved, instrumentOverrides());
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
        InstrumentOverrides overrides = page.isEmpty() ? InstrumentOverrides.NONE : instrumentOverrides();
        return page.map(t -> InvestmentTransactionResponse.from(t, overrides));
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
        List<PositionDto> all = getAllPositions();
        List<PositionDto> open = all.stream()
                .filter(p -> p.quantity() != null && p.quantity().compareTo(BigDecimal.ZERO) > 0)
                .toList();
        return new PositionsResponse(open);
    }

    @Transactional(readOnly = true)
    public List<PositionDto> getAllPositions() {
        List<Holding> holdings = holdingRepository.findAllWithDetails();
        List<PositionDto> positions = new ArrayList<>();

        LocalDate today = AppTime.today();
        InstrumentOverrides overrides = holdings.isEmpty() ? InstrumentOverrides.NONE : instrumentOverrides();
        Map<UUID, List<DayChange.PricePoint>> closes = latestTwoCloses(holdings);
        Map<OwnedInstrument, List<CorporateAction>> recentActions = recentCorporateActions(holdings, today);
        for (Holding holding : holdings) {
            UUID instrumentId = holding.getInstrument().getId();
            HoldingPosition pos;
            try {
                pos = calculateHoldingPosition(holding);
            } catch (Exception e) {
                log.warn("Skipping holding {} ({}) in positions: {}",
                        holding.getId(), holding.getInstrument().getName(), e.getMessage());
                continue;
            }
            PositionDto dto = pos.toPositionDto(overrides);
            DayChange change = DayChange.forPosition(dto.quantity(), dto.lastPrice(), dto.lastPriceAsOf(),
                    closes.get(instrumentId), today, recentActions.get(OwnedInstrument.of(holding)));
            positions.add(dto.withDayChange(change));
        }

        return positions;
    }

    /** Oracle caps an IN list at 1000 expressions. */
    private static final int IN_LIST_CHUNK = 900;

    /**
     * The latest two closes the holdings' viewer sees for every instrument the holdings reference
     * (their own MANUAL price wins on its date), newest first, from one batch query per
     * {@value #IN_LIST_CHUNK} instruments (never one per holding).
     */
    private Map<UUID, List<DayChange.PricePoint>> latestTwoCloses(List<Holding> holdings) {
        String viewer = PricePrecedence.userParam(viewerOf(holdings));
        List<String> ids = holdings.stream()
                .map(h -> h.getInstrument().getId())
                .filter(Objects::nonNull)
                .distinct()
                .map(UUID::toString)
                .toList();
        Map<UUID, List<DayChange.PricePoint>> result = new HashMap<>();
        for (int from = 0; from < ids.size(); from += IN_LIST_CHUNK) {
            List<Object[]> rows = priceRepository.findLatestTwoCloses(
                    ids.subList(from, Math.min(ids.size(), from + IN_LIST_CHUNK)), viewer);
            if (rows == null) {
                continue;
            }
            for (Object[] row : rows) {
                UUID instrumentId = UUID.fromString(row[0].toString());
                LocalDate asOf = toLocalDate(row[1]);
                BigDecimal close = row[2] instanceof BigDecimal bd ? bd : new BigDecimal(row[2].toString());
                result.computeIfAbsent(instrumentId, k -> new ArrayList<>()).add(new DayChange.PricePoint(asOf, close));
            }
        }
        result.values().forEach(points -> points.sort(Comparator.comparing(DayChange.PricePoint::asOf).reversed()));
        return result;
    }

    /**
     * The holdings' owners' own corporate actions on the instruments they hold, with an ex-date recent
     * enough to fall between a current price and the one before it ({@link DayChange#forPosition}),
     * keyed by (owner, instrument): one batch query per {@value #IN_LIST_CHUNK} instruments. A user's
     * day change never reads another user's actions, even when a job computes several users at once.
     */
    private Map<OwnedInstrument, List<CorporateAction>> recentCorporateActions(List<Holding> holdings, LocalDate today) {
        List<UUID> owners = holdings.stream().map(InvestmentService::ownerOf).filter(Objects::nonNull).distinct().toList();
        List<UUID> ids = holdings.stream()
                .map(h -> h.getInstrument().getId())
                .filter(Objects::nonNull)
                .distinct()
                .toList();
        Map<OwnedInstrument, List<CorporateAction>> result = new HashMap<>();
        if (owners.isEmpty()) {
            return result;
        }
        LocalDate from = today.minusDays(DayChange.CURRENT_WITHIN_DAYS + DayChange.PREVIOUS_WITHIN_DAYS);
        for (int i = 0; i < ids.size(); i += IN_LIST_CHUNK) {
            List<CorporateAction> rows = corporateActionRepository.findOwnedByInstrumentIdsExDateFrom(
                    owners, ids.subList(i, Math.min(ids.size(), i + IN_LIST_CHUNK)), from);
            if (rows == null) {
                continue;
            }
            for (CorporateAction ca : rows) {
                OwnedInstrument key = new OwnedInstrument(ca.getUser() != null ? ca.getUser().getId() : null,
                        ca.getInstrument().getId());
                result.computeIfAbsent(key, k -> new ArrayList<>()).add(ca);
            }
        }
        return result;
    }

    private static LocalDate toLocalDate(Object value) {
        if (value instanceof LocalDate ld) {
            return ld;
        }
        if (value instanceof java.sql.Date d) {
            return d.toLocalDate();
        }
        if (value instanceof java.sql.Timestamp ts) {
            return ts.toLocalDateTime().toLocalDate();
        }
        if (value instanceof java.time.LocalDateTime ldt) {
            return ldt.toLocalDate();
        }
        return LocalDate.parse(value.toString().substring(0, 10));
    }

    @Transactional(readOnly = true)
    public SummaryResponse getSummary() {
        List<Holding> holdings = holdingRepository.findAllWithDetails();

        BigDecimal totalInvested = BigDecimal.ZERO;
        BigDecimal totalCurrentValue = BigDecimal.ZERO;
        BigDecimal totalRealized = BigDecimal.ZERO;
        BigDecimal totalIntradayRealized = BigDecimal.ZERO;
        BigDecimal totalFnoRealized = fnoTradeRepository.sumRealizedPnl();
        if (totalFnoRealized == null) {
            totalFnoRealized = BigDecimal.ZERO;
        }
        BigDecimal totalCharges = BigDecimal.ZERO;

        Map<UUID, BrokerSummaryAccumulator> brokerMap = new LinkedHashMap<>();
        Map<InstrumentType, InstrumentTypeAccumulator> typeMap = new EnumMap<>(InstrumentType.class);

        List<XirrCalculator.Cashflow> portfolioCashflows = new ArrayList<>();

        LocalDate today = AppTime.today();
        InstrumentOverrides overrides = holdings.isEmpty() ? InstrumentOverrides.NONE : instrumentOverrides();
        Map<UUID, List<DayChange.PricePoint>> closes = latestTwoCloses(holdings);
        Map<OwnedInstrument, List<CorporateAction>> recentActions = recentCorporateActions(holdings, today);
        BigDecimal dayChange = null;
        BigDecimal previousValue = BigDecimal.ZERO;
        LocalDate priceAsOf = null;
        LocalDate previousPriceAsOf = null;

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
            if (pos.openQty().signum() > 0 && pos.priceAsOf() != null
                    && (priceAsOf == null || pos.priceAsOf().isAfter(priceAsOf))) {
                priceAsOf = pos.priceAsOf();
            }
            DayChange change = DayChange.forPosition(pos.openQty(), pos.latestPrice(), pos.priceAsOf(),
                    closes.get(holding.getInstrument().getId()), today, recentActions.get(OwnedInstrument.of(holding)));
            if (change.dayChange() != null) {
                dayChange = (dayChange == null ? BigDecimal.ZERO : dayChange).add(change.dayChange());
                previousValue = previousValue.add(change.previousValue(pos.openQty()));
                if (previousPriceAsOf == null || change.previousCloseAsOf().isAfter(previousPriceAsOf)) {
                    previousPriceAsOf = change.previousCloseAsOf();
                }
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

            // Accumulate transaction cashflows for XIRR
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
            InstrumentType instType = overrides.type(holding.getInstrument());
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
            portfolioCashflows.add(new XirrCalculator.Cashflow(AppTime.today(), totalCurrentValue));
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
                byInstrumentType,
                totalFnoRealized.setScale(2, RoundingMode.HALF_UP),
                dayChange == null ? null : dayChange.setScale(2, RoundingMode.HALF_UP),
                dayChange == null || previousValue.signum() == 0
                        ? null
                        : dayChange.multiply(new BigDecimal("100")).divide(previousValue, 2, RoundingMode.HALF_UP),
                priceAsOf,
                previousPriceAsOf
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

    public HoldingPosition calculateHoldingPosition(Holding holding) {
        return calculateHoldingPosition(holding, null);
    }

    public HoldingPosition calculateHoldingPosition(Holding holding, java.util.function.Consumer<com.financeos.domain.investment.dto.RealizedLot> lotCollector) {
        return calculateHoldingPosition(holding, lotCollector, null, null,
                lotCollector != null ? classificationOf(holding) : null);
    }

    /**
     * The position of one of the current user's holdings plus the engine's trace of it (the
     * events it applied and the lots left open), for explaining how the position is made up.
     * {@code findById} bypasses the userFilter, so ownership is checked explicitly; a missing or
     * foreign holding is a 404.
     */
    @Transactional(readOnly = true)
    public HoldingTrace traceHoldingPosition(UUID holdingId) {
        Holding holding = holdingRepository.findById(holdingId)
                .filter(h -> h.getUser() != null && h.getUser().getId().equals(UserContext.getCurrentUserId()))
                .orElseThrow(() -> new ResourceNotFoundException("Holding", holdingId));
        TraceSink trace = new TraceSink(new ArrayList<>(), new ArrayList<>());
        HoldingPosition position = calculateHoldingPosition(holding, null, trace, null, null);
        return new HoldingTrace(position, List.copyOf(trace.openLots()), List.copyOf(trace.events()));
    }

    /** Collects the engine trace when {@link #traceHoldingPosition} asks for one. */
    private record TraceSink(List<HoldingTrace.Event> events, List<HoldingTrace.OpenLot> openLots) {}

    /**
     * Everything the lot engine reads for the current user's holdings, loaded up front in a fixed
     * number of batch queries (never one per holding). Built by {@link #loadEngineInputs}; a null
     * inputs argument makes the engine read per holding from the repositories instead.
     */
    private record EngineInputs(
            List<Holding> holdings,
            Map<UUID, List<InvestmentTransaction>> txnsByHolding,
            Map<UUID, List<CorporateAction>> actionsByInstrument,
            Map<UUID, List<CorporateAction>> actionsByTarget,
            Map<UUID, List<TradeSettlementClassification>> classificationsByHolding,
            Map<UUID, InstrumentPrice> latestPriceByInstrument,
            Map<UUID, List<Dividend>> dividendsByHolding,
            InstrumentOverrides overrides) {}

    /**
     * Batch-loads the engine inputs for {@code userId}'s {@code holdings}: transactions, intraday
     * classifications, dividends and overrides by user (one query each), the user's own corporate
     * actions on and into the held instruments and their latest prices (one query each per
     * {@value #IN_LIST_CHUNK} instruments).
     */
    private EngineInputs loadEngineInputs(UUID userId, List<Holding> holdings) {
        Set<UUID> holdingIds = new HashSet<>();
        Set<UUID> instrumentIdSet = new LinkedHashSet<>();
        for (Holding h : holdings) {
            holdingIds.add(h.getId());
            instrumentIdSet.add(h.getInstrument().getId());
        }
        List<UUID> instrumentIds = List.copyOf(instrumentIdSet);

        Map<UUID, List<InvestmentTransaction>> txns = new HashMap<>();
        for (InvestmentTransaction t : transactionRepository.findByUser_IdOrderByTradeDateAscCreatedAtAsc(userId)) {
            if (t.getHolding() != null && holdingIds.contains(t.getHolding().getId())) {
                txns.computeIfAbsent(t.getHolding().getId(), k -> new ArrayList<>()).add(t);
            }
        }
        Map<UUID, List<TradeSettlementClassification>> classifications = new HashMap<>();
        for (TradeSettlementClassification c : classificationRepository.findByUser_Id(userId)) {
            if (c.getHolding() != null && holdingIds.contains(c.getHolding().getId())) {
                classifications.computeIfAbsent(c.getHolding().getId(), k -> new ArrayList<>()).add(c);
            }
        }
        Map<UUID, List<Dividend>> dividends = new HashMap<>();
        for (Dividend d : dividendRepository.findByUser_Id(userId)) {
            if (d.getHolding() != null && holdingIds.contains(d.getHolding().getId())) {
                dividends.computeIfAbsent(d.getHolding().getId(), k -> new ArrayList<>()).add(d);
            }
        }
        Map<UUID, List<CorporateAction>> actions = new HashMap<>();
        Map<UUID, List<CorporateAction>> actionsByTarget = new HashMap<>();
        Map<UUID, InstrumentPrice> latestPrices = new HashMap<>();
        for (int from = 0; from < instrumentIds.size(); from += IN_LIST_CHUNK) {
            List<UUID> chunk = instrumentIds.subList(from, Math.min(instrumentIds.size(), from + IN_LIST_CHUNK));
            for (CorporateAction ca : corporateActionRepository.findOwnedByInstrumentIds(userId, chunk)) {
                actions.computeIfAbsent(ca.getInstrument().getId(), k -> new ArrayList<>()).add(ca);
            }
            for (CorporateAction ca : corporateActionRepository.findOwnedByTargetInstrumentIds(userId, chunk)) {
                actionsByTarget.computeIfAbsent(ca.getTargetInstrument().getId(), k -> new ArrayList<>()).add(ca);
            }
            latestPrices.putAll(PricePrecedence.byInstrument(priceRepository.findLatestByInstrumentIds(chunk, userId)));
        }
        InstrumentOverrides overrides = overridesOf(userId);
        return new EngineInputs(holdings, txns, actions, actionsByTarget, classifications, latestPrices, dividends, overrides);
    }

    private List<InvestmentTransaction> txnsOf(Holding holding, @Nullable EngineInputs in) {
        return in != null ? in.txnsByHolding().getOrDefault(holding.getId(), List.of())
                : transactionRepository.findByHoldingIdOrderByTradeDateAscCreatedAtAsc(holding.getId());
    }

    /** The holding owner's corporate actions on its instrument (never another user's). */
    private List<CorporateAction> actionsOf(Holding holding, @Nullable EngineInputs in) {
        UUID instrumentId = holding.getInstrument().getId();
        return in != null ? in.actionsByInstrument().getOrDefault(instrumentId, List.of())
                : corporateActionRepository.findByUser_IdAndInstrument_IdOrderByExDateAsc(ownerOf(holding), instrumentId);
    }

    private List<TradeSettlementClassification> classificationsOf(Holding holding, @Nullable EngineInputs in) {
        return in != null ? in.classificationsByHolding().getOrDefault(holding.getId(), List.of())
                : classificationRepository.findByHoldingId(holding.getId());
    }

    /**
     * The FIFO / corporate-action / intraday engine. {@code lotCollector} receives every matched
     * sell lot (classified by {@code classification}, required with a collector) and {@code trace}
     * (when non-null) every applied event and the final open lots; neither changes the result.
     * {@code inputs} (nullable) supplies the batch-loaded reads; without it they run per holding.
     */
    private HoldingPosition calculateHoldingPosition(Holding holding,
                                                     java.util.function.Consumer<com.financeos.domain.investment.dto.RealizedLot> lotCollector,
                                                     TraceSink trace,
                                                     @Nullable EngineInputs inputs,
                                                     @Nullable AssetClassifier.Classification classification) {
        if (lotCollector != null && classification == null) {
            classification = classificationOf(holding);
        }
        List<InvestmentTransaction> txns = txnsOf(holding, inputs);
        List<CorporateAction> corpActions = actionsOf(holding, inputs);

        SeedDerivation seedDerivation = deriveSeeds(holding, inputs);
        List<DemergerSeedEvent> demergerSeedEvents = new ArrayList<>();
        for (SeedLot s : seedDerivation.seedLots()) {
            demergerSeedEvents.add(new DemergerSeedEvent(s));
        }
        List<XirrCalculator.Cashflow> mergerBridgeOutflows = seedDerivation.mergerBridgeOutflows();
        BigDecimal fractionalRealized = seedDerivation.fractionalRealized();
        List<XirrCalculator.Cashflow> fractionalCashflows = seedDerivation.fractionalCashflows();

        List<TradeSettlementClassification> classifications = classificationsOf(holding, inputs);
        Map<LocalDate, TradeSettlementClassification> classMap = new HashMap<>();
        for (TradeSettlementClassification c : classifications) {
            classMap.put(c.getTradeDate(), c);
        }

        BigDecimal intradayRealized = BigDecimal.ZERO;
        BigDecimal totalHoldingCharges = BigDecimal.ZERO;
        List<XirrCalculator.Cashflow> cashflows = new ArrayList<>();
        cashflows.addAll(mergerBridgeOutflows);
        cashflows.addAll(fractionalCashflows);
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
                timeline.add(new IntradayNettingEvent(date, intradayQty));

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
                    timeline.add(new TxnEvent(delivBuyTxn, true));
                }

                if (delivSellQty.compareTo(BigDecimal.ZERO) > 0) {
                    BigDecimal sellPrice = delivSellVal.divide(delivSellQty, 8, RoundingMode.HALF_UP);
                    InvestmentTransaction delivSellTxn = new InvestmentTransaction();
                    delivSellTxn.setTradeDate(date);
                    delivSellTxn.setType(InvestmentTransactionType.sell);
                    delivSellTxn.setQuantity(delivSellQty);
                    delivSellTxn.setPrice(sellPrice);
                    delivSellTxn.setTotalCharges(BigDecimal.ZERO);
                    timeline.add(new TxnEvent(delivSellTxn, true));
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
                    timeline.add(new TxnEvent(t, false));
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
        BigDecimal cumulativeRealized = fractionalRealized;

        for (TimelineEvent event : timeline) {
            BigDecimal qtyBefore = trace != null ? openQuantity(openLots) : null;
            if (event instanceof DemergerSeedEvent seedEvent) {
                SeedLot seed = seedEvent.seed();
                openLots.add(new Lot(seed.qty(), seed.costPerUnit(), seed.buyDate(),
                        HoldingTrace.LotSource.corporateAction(seed.source())));
            } else if (event instanceof CorpActionEvent caEvent) {
                CorporateAction ca = caEvent.action();
                if (ca.getType() == CorporateActionType.bonus) {
                    addBonusLot(openLots, ca, true);
                } else if (ca.getType() == CorporateActionType.merger) {
                    BigDecimal mergerValue = openCost(openLots);
                    if (mergerValue.compareTo(BigDecimal.ZERO) > 0) {
                        cashflows.add(new XirrCalculator.Cashflow(ca.getExDate(), mergerValue));
                    }
                    openLots.clear();
                } else if (ca.getType() == CorporateActionType.demerger) {
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
                    openLots.add(new Lot(txn.getQuantity(), costPerUnit, txn.getTradeDate(),
                            txnEvent.intradayNetted() ? HoldingTrace.LotSource.INTRADAY_NETTED_DELIVERY : HoldingTrace.LotSource.BUY));

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
                        BigDecimal buyVal = takeQty.multiply(oldestLot.costPerUnit);
                        matchedCost = matchedCost.add(buyVal);
                        oldestLot.remainingQty = oldestLot.remainingQty.subtract(takeQty);
                        qtyToMatch = qtyToMatch.subtract(takeQty);

                        if (lotCollector != null) {
                            BigDecimal sellVal = takeQty.multiply(txn.getPrice());
                            BigDecimal pnl = sellVal.subtract(buyVal);
                            LocalDate bDate = oldestLot.buyDate != null ? oldestLot.buyDate : txn.getTradeDate();
                            long days = java.time.temporal.ChronoUnit.DAYS.between(bDate, txn.getTradeDate());
                            String term = CapitalGainsTerm.term(classification.taxClass(), bDate, txn.getTradeDate());

                            lotCollector.accept(new com.financeos.domain.investment.dto.RealizedLot(
                                    holding.getId(),
                                    holding.getBrokerAccount().getId(),
                                    holding.getBrokerAccount().getName(),
                                    holding.getInstrument().getId(),
                                    holding.getInstrument().getName(),
                                    holding.getInstrument().getType(),
                                    bDate,
                                    txn.getTradeDate(),
                                    takeQty,
                                    buyVal,
                                    sellVal,
                                    pnl,
                                    days,
                                    term,
                                    classification.assetClass(),
                                    classification.taxClass(),
                                    CapitalGainsTerm.grandfathered(classification.taxClass(), bDate)
                            ));
                        }

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
            if (trace != null) {
                trace.events().add(traceEvent(event, qtyBefore, openQuantity(openLots)));
            }
        }

        if (trace != null) {
            for (Lot lot : openLots) {
                trace.openLots().add(new HoldingTrace.OpenLot(lot.buyDate, lot.source, lot.remainingQty, lot.costPerUnit));
            }
        }

        BigDecimal openQty = openQuantity(openLots);
        BigDecimal openCost = openCost(openLots);

        BigDecimal avgCost = openQty.compareTo(BigDecimal.ZERO) > 0
                ? openCost.divide(openQty, 4, RoundingMode.HALF_UP)
                : BigDecimal.ZERO;

        Optional<InstrumentPrice> latestPrice = inputs != null
                ? Optional.ofNullable(inputs.latestPriceByInstrument().get(holding.getInstrument().getId()))
                : PricePrecedence.preferred(priceRepository.findLatestVisible(holding.getInstrument().getId(), viewerOf(holding)));
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
        } else if (openQty.compareTo(BigDecimal.ZERO) > 0) {
            currentValue = openCost.setScale(4, RoundingMode.HALF_UP);
            unrealized = BigDecimal.ZERO.setScale(4, RoundingMode.HALF_UP);
            unrealizedPercent = BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP);
        }

        // Add dividends to cashflows
        BigDecimal holdingDividends;
        List<Dividend> dividendsList;
        if (inputs != null) {
            dividendsList = inputs.dividendsByHolding().getOrDefault(holding.getId(), List.of());
            holdingDividends = BigDecimal.ZERO;
            for (Dividend div : dividendsList) {
                if (div.getAmount() != null) {
                    holdingDividends = holdingDividends.add(div.getAmount());
                }
            }
        } else {
            holdingDividends = dividendRepository.sumAmountByHoldingId(holding.getId());
            if (holdingDividends == null) {
                holdingDividends = BigDecimal.ZERO;
            }
            dividendsList = dividendRepository.findByHoldingIdOrderByPayDateDescCreatedAtDesc(holding.getId());
        }
        for (Dividend div : dividendsList) {
            cashflows.add(new XirrCalculator.Cashflow(div.getPayDate(), div.getAmount()));
        }

        // Terminal cashflow for XIRR
        if (currentValue != null && openQty.compareTo(BigDecimal.ZERO) > 0) {
            cashflows.add(new XirrCalculator.Cashflow(AppTime.today(), currentValue));
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

        String mergedIntoName = null;
        LocalDate mergedIntoDate = null;
        for (CorporateAction ca : corpActions) {
            if (ca.getType() == CorporateActionType.merger && ca.getTargetInstrument() != null && !ca.getExDate().isAfter(AppTime.today())) {
                mergedIntoName = (inputs != null ? inputs.overrides() : overridesOf(viewerOf(holding)))
                        .name(ca.getTargetInstrument());
                mergedIntoDate = ca.getExDate();
                break;
            }
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
                absoluteReturnPercent,
                mergedIntoName,
                mergedIntoDate
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

    /**
     * Applies a bonus issue: the bonus shares are a NEW lot bought on the ex-date at zero cost
     * (s.55(2)(aa)), appended after the existing lots so FIFO sells the original shares first; the
     * existing lots keep their quantity and cost, so the position's total cost (and so its average
     * cost over the larger quantity) is the same as rescaling. Bonus ratios are stored held →
     * held-after (a 1:1 bonus is 1 → 2), so the new shares are {@code open × (to − from) ÷ from}.
     * A bonus without a usable ratio, or on no open shares, adds nothing.
     */
    private static void addBonusLot(LinkedList<Lot> openLots, CorporateAction ca, boolean traced) {
        Integer from = ca.getRatioFrom();
        Integer to = ca.getRatioTo();
        if (from == null || from <= 0 || to == null || to <= from) {
            return;
        }
        BigDecimal bonusQty = openQuantity(openLots)
                .multiply(BigDecimal.valueOf(to - from))
                .divide(BigDecimal.valueOf(from), 8, RoundingMode.HALF_UP);
        if (bonusQty.signum() > 0) {
            openLots.add(new Lot(bonusQty, BigDecimal.ZERO, ca.getExDate(),
                    traced ? HoldingTrace.LotSource.bonus(ca) : null));
        }
    }

    /** The open quantity of a set of lots, unrounded. */
    private static BigDecimal openQuantity(List<Lot> lots) {
        BigDecimal qty = BigDecimal.ZERO;
        for (Lot lot : lots) {
            qty = qty.add(lot.remainingQty);
        }
        return qty;
    }

    /** The clean cost of a set of lots ({@code Σ remainingQty × costPerUnit}), unrounded. */
    private static BigDecimal openCost(List<Lot> lots) {
        BigDecimal cost = BigDecimal.ZERO;
        for (Lot lot : lots) {
            cost = cost.add(lot.remainingQty.multiply(lot.costPerUnit));
        }
        return cost;
    }

    /** The trace entry of an applied event, given the open quantity before and after it. */
    private static HoldingTrace.Event traceEvent(TimelineEvent event, BigDecimal qtyBefore, BigDecimal qtyAfter) {
        BigDecimal change = qtyAfter.subtract(qtyBefore);
        return switch (event) {
            case DemergerSeedEvent s -> new HoldingTrace.Event(s.date(), HoldingTrace.EventKind.RECEIVED_FROM_CORPORATE_ACTION,
                    s.seed().source(), s.seed().qty(), null, change, qtyAfter);
            case CorpActionEvent c -> new HoldingTrace.Event(c.date(), HoldingTrace.EventKind.CORPORATE_ACTION,
                    c.action(), null, null, change, qtyAfter);
            case IntradayNettingEvent n -> new HoldingTrace.Event(n.date(), HoldingTrace.EventKind.INTRADAY_NETTED,
                    null, n.intradayQty(), null, change, qtyAfter);
            case TxnEvent t -> {
                boolean buy = t.txn().getType() == InvestmentTransactionType.buy;
                HoldingTrace.EventKind kind = t.intradayNetted()
                        ? (buy ? HoldingTrace.EventKind.DELIVERY_BUY : HoldingTrace.EventKind.DELIVERY_SELL)
                        : (buy ? HoldingTrace.EventKind.BUY : HoldingTrace.EventKind.SELL);
                yield new HoldingTrace.Event(t.date(), kind, null, t.txn().getQuantity(), t.txn().getPrice(), change, qtyAfter);
            }
        };
    }

    private sealed interface TimelineEvent {
        LocalDate date();
    }

    /**
     * A trade applied to the lots. {@code intradayNetted} marks the synthetic delivery residual of
     * a day whose intraday buys and sells were netted (it stands in for that day's real trades).
     */
    private record TxnEvent(InvestmentTransaction txn, boolean intradayNetted) implements TimelineEvent {
        @Override
        public LocalDate date() {
            return txn.getTradeDate();
        }
    }

    /** Marks a day whose intraday buys and sells were netted; it moves no lots (traced only). */
    private record IntradayNettingEvent(LocalDate date, BigDecimal intradayQty) implements TimelineEvent {}

    private record CorpActionEvent(CorporateAction action) implements TimelineEvent {
        @Override
        public LocalDate date() {
            return action.getExDate();
        }
    }

    private record DemergerSeedEvent(SeedLot seed) implements TimelineEvent {
        @Override
        public LocalDate date() {
            return seed.date();
        }
    }


    /**
     * A CA-seeded lot (demerger child / merger target): shares that arrive without a buy txn.
     *
     * @param date    the ex-date: when the shares enter the holding's timeline
     * @param buyDate the acquisition date for tax: the parent lot's buy date (s.2(42A) — the child /
     *                acquirer shares inherit the parent's holding period; grandfathering follows it)
     * @param source  the demerger/merger of the other instrument the shares came from
     */
    public record SeedLot(LocalDate date, BigDecimal qty, BigDecimal costPerUnit, CorporateAction source, LocalDate buyDate) {
        /** A seed whose holding period starts on its ex-date. */
        public SeedLot(LocalDate date, BigDecimal qty, BigDecimal costPerUnit, CorporateAction source) {
            this(date, qty, costPerUnit, source, date);
        }
    }

    private record SeedDerivation(
            List<SeedLot> seedLots,
            List<XirrCalculator.Cashflow> mergerBridgeOutflows,
            BigDecimal fractionalRealized,
            List<XirrCalculator.Cashflow> fractionalCashflows) {}

    /**
     * Derives the CA-seeded lots for a holding whose instrument is the target of demerger/merger
     * corporate actions, plus the XIRR bridge flows and fractional cash-in-lieu results.
     * Extracted verbatim from calculateHoldingPosition so buildOpenLotsBeforeDate callers
     * (e.g. the portfolio_value datasource) can seed the same lots.
     */
    private SeedDerivation deriveSeeds(Holding holding, @Nullable EngineInputs inputs) {
        List<CorporateAction> targetCAs = inputs != null
                ? inputs.actionsByTarget().getOrDefault(holding.getInstrument().getId(), List.of())
                : corporateActionRepository.findByUser_IdAndTargetInstrument_IdOrderByExDateAsc(ownerOf(holding),
                        holding.getInstrument().getId());
        List<SeedLot> seedLots = new ArrayList<>();
        List<XirrCalculator.Cashflow> mergerBridgeOutflows = new ArrayList<>();
        BigDecimal fractionalRealized = BigDecimal.ZERO;
        List<XirrCalculator.Cashflow> fractionalCashflows = new ArrayList<>();
        for (CorporateAction ca : targetCAs) {
            Optional<Holding> parentHoldingOpt = inputs != null
                    ? inputs.holdings().stream()
                            .filter(h -> h.getBrokerAccount().getId().equals(holding.getBrokerAccount().getId())
                                    && h.getInstrument().getId().equals(ca.getInstrument().getId()))
                            .findFirst()
                    : holdingRepository.findByBrokerAccountIdAndInstrumentId(
                            holding.getBrokerAccount().getId(),
                            ca.getInstrument().getId());
            if (parentHoldingOpt.isPresent() && (ca.getCostAllocationPct() != null || ca.getType() == CorporateActionType.merger) && ca.getRatioFrom() != null && ca.getRatioFrom() > 0 && ca.getRatioTo() != null && ca.getRatioTo() > 0) {
                List<Lot> parentOpenLots = buildParentOpenLotsBeforeCa(parentHoldingOpt.get(), ca, inputs);
                BigDecimal costAllocPct = ca.getType() == CorporateActionType.merger ? new BigDecimal("100") : ca.getCostAllocationPct();
                BigDecimal E = BigDecimal.ZERO;
                BigDecimal Cseed = BigDecimal.ZERO;
                // One seed per parent lot, in the parent's FIFO order, each keeping its buy date and
                // its share of the carved cost.
                List<BigDecimal[]> rawLots = new ArrayList<>();
                List<LocalDate> rawBuyDates = new ArrayList<>();
                for (Lot parentLot : parentOpenLots) {
                    BigDecimal childQty = parentLot.remainingQty
                            .multiply(BigDecimal.valueOf(ca.getRatioTo()))
                            .divide(BigDecimal.valueOf(ca.getRatioFrom()), 10, RoundingMode.HALF_UP)
                            .setScale(8, RoundingMode.HALF_UP);
                    BigDecimal childCost = parentLot.remainingQty
                            .multiply(parentLot.costPerUnit)
                            .multiply(costAllocPct)
                            .divide(BigDecimal.valueOf(100), 10, RoundingMode.HALF_UP);
                    if (childQty.compareTo(BigDecimal.ZERO) > 0) {
                        BigDecimal costPerUnit = childCost.divide(childQty, 8, RoundingMode.HALF_UP);
                        rawLots.add(new BigDecimal[]{childQty, costPerUnit});
                        rawBuyDates.add(parentLot.buyDate != null ? parentLot.buyDate : ca.getExDate());
                        E = E.add(childQty);
                        Cseed = Cseed.add(childCost);
                    }
                }
                if (E.compareTo(BigDecimal.ZERO) > 0) {
                    BigDecimal W = E.setScale(0, RoundingMode.FLOOR);
                    BigDecimal F = E.subtract(W);
                    BigDecimal scale = W.compareTo(BigDecimal.ZERO) > 0 ? W.divide(E, 10, RoundingMode.HALF_UP) : BigDecimal.ZERO;

                    for (int i = 0; i < rawLots.size(); i++) {
                        BigDecimal[] r = rawLots.get(i);
                        BigDecimal seedQty = r[0].multiply(scale).setScale(8, RoundingMode.HALF_UP);
                        if (seedQty.compareTo(BigDecimal.ZERO) > 0) {
                            seedLots.add(new SeedLot(ca.getExDate(), seedQty, r[1], ca, rawBuyDates.get(i)));
                        }
                    }

                    if (ca.getType() == CorporateActionType.merger && Cseed.compareTo(BigDecimal.ZERO) > 0) {
                        mergerBridgeOutflows.add(new XirrCalculator.Cashflow(ca.getExDate(), Cseed.negate()));
                    }

                    if (F.compareTo(BigDecimal.ZERO) > 0) {
                        BigDecimal proceeds = BigDecimal.ZERO;
                        if (ca.getFractionalCashInLieu() != null && ca.getFractionalCashInLieu().compareTo(BigDecimal.ZERO) > 0) {
                            BigDecimal totalFrac = computeTotalFractionForCa(ca, ownerOf(holding), inputs);
                            if (totalFrac.compareTo(BigDecimal.ZERO) > 0) {
                                proceeds = ca.getFractionalCashInLieu().multiply(F).divide(totalFrac, 4, RoundingMode.HALF_UP);
                            }
                        }
                        BigDecimal fractionalCost = F.multiply(Cseed.divide(E, 10, RoundingMode.HALF_UP));
                        fractionalRealized = fractionalRealized.add(proceeds.subtract(fractionalCost));
                        fractionalCashflows.add(new XirrCalculator.Cashflow(ca.getExDate(), proceeds));
                    }
                }
            }
        }

        return new SeedDerivation(seedLots, mergerBridgeOutflows, fractionalRealized, fractionalCashflows);
    }

    /** The seeded lots for a holding, for callers that only need quantities/cost (no XIRR parts). */
    public List<SeedLot> seedLotsFor(Holding holding) {
        return deriveSeeds(holding, null).seedLots();
    }

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

    /**
     * The fractional entitlement of {@code ownerId}'s parent holdings (every broker) for a
     * demerger/merger: the cash-in-lieu on the action is the owner's own amount, shared across their
     * brokers by fraction. Never another user's holdings.
     */
    private BigDecimal computeTotalFractionForCa(CorporateAction ca, @Nullable UUID ownerId, @Nullable EngineInputs inputs) {
        BigDecimal total = BigDecimal.ZERO;
        List<Holding> parents = inputs != null
                ? inputs.holdings().stream().filter(h -> h.getInstrument().getId().equals(ca.getInstrument().getId())).toList()
                : holdingRepository.findByUser_IdAndInstrument_Id(ownerId, ca.getInstrument().getId());
        for (Holding ph : parents) {
            BigDecimal e = BigDecimal.ZERO;
            for (Lot lot : buildParentOpenLotsBeforeCa(ph, ca, inputs)) {
                e = e.add(lot.remainingQty.multiply(BigDecimal.valueOf(ca.getRatioTo()))
                        .divide(BigDecimal.valueOf(ca.getRatioFrom()), 10, RoundingMode.HALF_UP).setScale(8, RoundingMode.HALF_UP));
            }
            total = total.add(e.subtract(e.setScale(0, RoundingMode.FLOOR)));
        }
        return total;
    }

    public BigDecimal openQtyAsOf(Holding holding, LocalDate date) {
        BigDecimal total = openQuantity(buildOpenLotsBeforeDate(holding, date, true, null));
        return total.compareTo(BigDecimal.ZERO) > 0 ? total : BigDecimal.ZERO;
    }

    private List<Lot> buildParentOpenLotsBeforeCa(Holding parentHolding, CorporateAction demergerCa, @Nullable EngineInputs inputs) {
        if (inputs == null) {
            return buildOpenLotsBeforeDate(parentHolding, demergerCa.getExDate(), false, demergerCa.getId());
        }
        return buildOpenLotsBeforeDate(parentHolding, demergerCa.getExDate(), false, demergerCa.getId(),
                txnsOf(parentHolding, inputs), actionsOf(parentHolding, inputs), null,
                classificationsOf(parentHolding, inputs));
    }

    public List<Lot> buildOpenLotsBeforeDate(Holding parentHolding, LocalDate cutoffDate, boolean strictBefore, UUID caToIgnore) {
        return buildOpenLotsBeforeDate(parentHolding, cutoffDate, strictBefore, caToIgnore, null, null, null);
    }

    /**
     * As {@link #buildOpenLotsBeforeDate(Holding, LocalDate, boolean, UUID)}, but with optional
     * prefetched transactions/corporate-actions (avoids per-call repository queries when a caller
     * evaluates many cutoff dates) and optional CA-seeded lots (demerger child / merger target
     * shares from {@link #seedLotsFor}) so sells and later CAs apply to them too. The demerger
     * carve path passes null seeds, preserving its original behavior exactly.
     */
    public List<Lot> buildOpenLotsBeforeDate(Holding parentHolding, LocalDate cutoffDate, boolean strictBefore, UUID caToIgnore,
            List<InvestmentTransaction> prefetchedTxns, List<CorporateAction> prefetchedCorpActions, List<SeedLot> seedLots) {
        return buildOpenLotsBeforeDate(parentHolding, cutoffDate, strictBefore, caToIgnore, prefetchedTxns,
                prefetchedCorpActions, seedLots, null);
    }

    /** As above, with optional prefetched intraday classifications too (null reads them per holding). */
    private List<Lot> buildOpenLotsBeforeDate(Holding parentHolding, LocalDate cutoffDate, boolean strictBefore, UUID caToIgnore,
            List<InvestmentTransaction> prefetchedTxns, List<CorporateAction> prefetchedCorpActions, List<SeedLot> seedLots,
            @Nullable List<TradeSettlementClassification> prefetchedClassifications) {
        List<InvestmentTransaction> txns = prefetchedTxns != null ? prefetchedTxns
                : transactionRepository.findByHoldingIdOrderByTradeDateAscCreatedAtAsc(parentHolding.getId());
        List<CorporateAction> corpActions = prefetchedCorpActions != null ? prefetchedCorpActions
                : corporateActionRepository.findByUser_IdAndInstrument_IdOrderByExDateAsc(ownerOf(parentHolding),
                        parentHolding.getInstrument().getId());

        List<TradeSettlementClassification> classifications = prefetchedClassifications != null ? prefetchedClassifications
                : classificationRepository.findByHoldingId(parentHolding.getId());
        Map<LocalDate, TradeSettlementClassification> classMap = new HashMap<>();
        for (TradeSettlementClassification c : classifications) {
            boolean include = strictBefore ? c.getTradeDate().compareTo(cutoffDate) < 0 : c.getTradeDate().compareTo(cutoffDate) <= 0;
            if (include) {
                classMap.put(c.getTradeDate(), c);
            }
        }

        List<TimelineEvent> timeline = new ArrayList<>();
        Map<LocalDate, List<InvestmentTransaction>> txnsByDate = new LinkedHashMap<>();
        for (InvestmentTransaction txn : txns) {
            boolean include = strictBefore ? txn.getTradeDate().compareTo(cutoffDate) < 0 : txn.getTradeDate().compareTo(cutoffDate) <= 0;
            if (include) {
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
                    timeline.add(new TxnEvent(delivBuyTxn, true));
                }

                if (delivSellQty.compareTo(BigDecimal.ZERO) > 0) {
                    BigDecimal sellPrice = delivSellVal.divide(delivSellQty, 8, RoundingMode.HALF_UP);
                    InvestmentTransaction delivSellTxn = new InvestmentTransaction();
                    delivSellTxn.setTradeDate(date);
                    delivSellTxn.setType(InvestmentTransactionType.sell);
                    delivSellTxn.setQuantity(delivSellQty);
                    delivSellTxn.setPrice(sellPrice);
                    delivSellTxn.setTotalCharges(BigDecimal.ZERO);
                    timeline.add(new TxnEvent(delivSellTxn, true));
                }
            } else {
                for (InvestmentTransaction t : dayTxns) {
                    timeline.add(new TxnEvent(t, false));
                }
            }
        }
        for (CorporateAction ca : corpActions) {
            boolean notIgnored = (caToIgnore == null) || !ca.getId().equals(caToIgnore);
            boolean include = strictBefore ? ca.getExDate().compareTo(cutoffDate) < 0 : ca.getExDate().compareTo(cutoffDate) <= 0;
            if (notIgnored && include) {
                timeline.add(new CorpActionEvent(ca));
            }
        }
        if (seedLots != null) {
            for (SeedLot seed : seedLots) {
                boolean include = strictBefore ? seed.date().compareTo(cutoffDate) < 0 : seed.date().compareTo(cutoffDate) <= 0;
                if (include) {
                    timeline.add(new DemergerSeedEvent(seed));
                }
            }
        }

        timeline.sort((e1, e2) -> {
            int dateCompare = e1.date().compareTo(e2.date());
            if (dateCompare != 0) {
                return dateCompare;
            }
            return Integer.compare(getEventOrder(e1), getEventOrder(e2));
        });

        LinkedList<Lot> openLots = new LinkedList<>();

        for (TimelineEvent event : timeline) {
            if (event instanceof DemergerSeedEvent seedEvent) {
                SeedLot seed = seedEvent.seed();
                openLots.add(new Lot(seed.qty(), seed.costPerUnit(), seed.buyDate()));
            } else if (event instanceof CorpActionEvent caEvent) {
                CorporateAction ca = caEvent.action();
                if (ca.getType() == CorporateActionType.bonus) {
                    addBonusLot(openLots, ca, false);
                } else if (ca.getType() == CorporateActionType.merger) {
                    // The transferor's shares convert in-kind on merger; without this the ratio
                    // branch below would scale them instead of closing the position.
                    openLots.clear();
                } else if (ca.getType() == CorporateActionType.demerger) {
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
                    openLots.add(new Lot(txn.getQuantity(), costPerUnit, txn.getTradeDate()));
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

    /**
     * One holding's engine figures: open quantity and cost, and realised gains (delivery and intraday).
     * {@code error} is the engine's message when it could not compute the holding (the figures are then
     * zero).
     */
    public record HoldingFigures(UUID holdingId, UUID brokerAccountId, String brokerName, UUID instrumentId,
                                 String instrumentName, BigDecimal quantity, BigDecimal openCost,
                                 BigDecimal realized, BigDecimal intradayRealized, @Nullable String error) {
    }

    /**
     * The figures of every holding {@code userId} owns, from the same engine as the positions page
     * (their own corporate actions, intraday netting and seeded lots), with the reads batch-loaded for
     * that user — no signed-in user or request filter needed. For checks that compare a user's figures
     * before and after a change in one transaction; prices play no part.
     */
    public List<HoldingFigures> figuresOf(UUID userId) {
        List<Holding> holdings = holdingRepository.findAllWithDetailsOfUser(userId);
        List<HoldingFigures> out = new ArrayList<>();
        if (holdings.isEmpty()) {
            return out;
        }
        EngineInputs inputs = loadEngineInputs(userId, holdings);
        for (Holding h : holdings) {
            UUID broker = h.getBrokerAccount().getId();
            String brokerName = h.getBrokerAccount().getName();
            Instrument instrument = h.getInstrument();
            try {
                HoldingPosition p = calculateHoldingPosition(h, null, null, inputs, null);
                out.add(new HoldingFigures(h.getId(), broker, brokerName, instrument.getId(), instrument.getName(),
                        p.openQty(), p.openCost(), p.realized(), p.intradayRealized(), null));
            } catch (Exception e) {
                out.add(new HoldingFigures(h.getId(), broker, brokerName, instrument.getId(), instrument.getName(),
                        BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO,
                        String.valueOf(e.getMessage())));
            }
        }
        return out;
    }

    @Transactional(readOnly = true)
    public List<com.financeos.domain.investment.dto.RealizedLot> getAllRealizedLots() {
        List<Holding> holdings = holdingRepository.findAllWithDetails();
        List<com.financeos.domain.investment.dto.RealizedLot> lots = new ArrayList<>();
        InstrumentOverrides overrides = holdings.isEmpty() ? InstrumentOverrides.NONE : instrumentOverrides();
        for (Holding holding : holdings) {
            try {
                calculateHoldingPosition(holding, lot -> lots.add(asSeen(lot, holding.getInstrument(), overrides)), null, null,
                        overrides.classification(holding.getInstrument()));
            } catch (Exception e) {
                log.warn("Skipping holding {} ({}) in realized lots: {}",
                        holding.getId(), holding.getInstrument().getName(), e.getMessage());
            }
        }
        return lots;
    }

    /**
     * One holding's position with the lots left open and the lots it realised, from a single run of
     * the position engine (corporate actions, intraday netting and seeded lots applied as everywhere).
     */
    public record HoldingLots(Holding holding, HoldingPosition position, List<HoldingTrace.OpenLot> openLots,
                              List<com.financeos.domain.investment.dto.RealizedLot> realizedLots,
                              AssetClassifier.Classification classification) {
        /** With the instrument's global classification (no per-user override). */
        public HoldingLots(Holding holding, HoldingPosition position, List<HoldingTrace.OpenLot> openLots,
                           List<com.financeos.domain.investment.dto.RealizedLot> realizedLots) {
            this(holding, position, openLots, realizedLots, AssetClassifier.effective(holding.getInstrument()));
        }
    }

    /**
     * Every holding of the current user with its open and realised lots, classified with the user's
     * own asset-class overrides (one engine pass per holding; a holding the engine cannot compute is
     * skipped, as on the positions page). The engine's reads are batch-loaded per user up front, so
     * the number of queries does not grow with the number of holdings.
     */
    @Transactional(readOnly = true)
    public List<HoldingLots> getAllHoldingLots() {
        List<HoldingLots> out = new ArrayList<>();
        List<Holding> holdings = holdingRepository.findAllWithDetails();
        if (holdings.isEmpty()) {
            return out;
        }
        UUID userId = UserContext.getCurrentUserId();
        EngineInputs inputs = userId != null ? loadEngineInputs(userId, holdings) : null;
        InstrumentOverrides overrides = inputs != null ? inputs.overrides() : instrumentOverrides();
        for (Holding holding : holdings) {
            List<com.financeos.domain.investment.dto.RealizedLot> realized = new ArrayList<>();
            TraceSink trace = new TraceSink(new ArrayList<>(), new ArrayList<>());
            AssetClassifier.Classification classification = overrides.classification(holding.getInstrument());
            try {
                HoldingPosition position = calculateHoldingPosition(holding,
                        lot -> realized.add(asSeen(lot, holding.getInstrument(), overrides)), trace, inputs, classification);
                out.add(new HoldingLots(holding, position, List.copyOf(trace.openLots()), List.copyOf(realized),
                        classification));
            } catch (Exception e) {
                log.warn("Skipping holding {} ({}) in holding lots: {}",
                        holding.getId(), holding.getInstrument().getName(), e.getMessage());
            }
        }
        return out;
    }

    public static class Lot {
        public BigDecimal remainingQty;
        public BigDecimal costPerUnit;
        public LocalDate buyDate;
        /** Where the lot came from; set by the position engine for its trace, else null. */
        public final HoldingTrace.LotSource source;

        public Lot(BigDecimal remainingQty, BigDecimal costPerUnit, LocalDate buyDate, HoldingTrace.LotSource source) {
            this.remainingQty = remainingQty;
            this.costPerUnit = costPerUnit;
            this.buyDate = buyDate;
            this.source = source;
        }

        public Lot(BigDecimal remainingQty, BigDecimal costPerUnit, LocalDate buyDate) {
            this(remainingQty, costPerUnit, buyDate, null);
        }

        public Lot(BigDecimal remainingQty, BigDecimal costPerUnit) {
            this(remainingQty, costPerUnit, null);
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
