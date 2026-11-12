package com.financeos.domain.instrument.corporateaction.detect;

import com.financeos.core.time.AppTime;
import com.financeos.domain.instrument.Instrument;
import com.financeos.domain.instrument.corporateaction.CorporateAction;
import com.financeos.domain.instrument.corporateaction.CorporateActionRepository;
import com.financeos.domain.instrument.corporateaction.CorporateActionService;
import com.financeos.domain.instrument.corporateaction.CorporateActionType;
import com.financeos.domain.instrument.price.PriceHistoryClient;
import com.financeos.domain.investment.InvestmentTransaction;
import com.financeos.domain.investment.InvestmentTransactionRepository;
import com.financeos.domain.investment.InvestmentTransactionType;
import com.financeos.domain.user.UserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.math.MathContext;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;

/**
 * Finds the splits and bonuses a user's stocks went through. An event from the exchange feed is applied on its own
 * only when it is a single clean bonus or split, the user held shares the day before, and the price feed shows the
 * same split on the same day; everything else is held as a pending suggestion for the user. Runs for one user at a
 * time (all reads name the owner); one INFO line per run.
 */
@Service
public class CorporateActionDetectionService {

    private static final Logger log = LoggerFactory.getLogger(CorporateActionDetectionService.class);
    private static final String SOURCE = MarketCorporateEvent.SOURCE_NSE;
    private static final double TOLERANCE = 1e-6;

    /** What one run did. */
    public record Summary(int applied, int pending, int skipped) {
    }

    private record Trade(LocalDate date, BigDecimal signedQty) {
    }

    private final InvestmentTransactionRepository transactions;
    private final MarketEventStore eventStore;
    private final MarketCorporateEventRepository events;
    private final CorporateActionRepository actions;
    private final CorporateActionSuggestionRepository suggestions;
    private final PriceHistoryClient priceHistory;
    private final CorporateActionService actionService;
    private final UserRepository users;
    private final TransactionTemplate tx;
    /** Null when constructed without it: the oversold check is then skipped. */
    private final OversoldSuggestionService oversold;

    public CorporateActionDetectionService(InvestmentTransactionRepository transactions, MarketEventStore eventStore,
                                           MarketCorporateEventRepository events, CorporateActionRepository actions,
                                           CorporateActionSuggestionRepository suggestions,
                                           PriceHistoryClient priceHistory, CorporateActionService actionService,
                                           UserRepository users, TransactionTemplate tx) {
        this(transactions, eventStore, events, actions, suggestions, priceHistory, actionService, users, tx, null);
    }

    @Autowired
    public CorporateActionDetectionService(InvestmentTransactionRepository transactions, MarketEventStore eventStore,
                                           MarketCorporateEventRepository events, CorporateActionRepository actions,
                                           CorporateActionSuggestionRepository suggestions,
                                           PriceHistoryClient priceHistory, CorporateActionService actionService,
                                           UserRepository users, TransactionTemplate tx,
                                           OversoldSuggestionService oversold) {
        this.oversold = oversold;
        this.transactions = transactions;
        this.eventStore = eventStore;
        this.events = events;
        this.actions = actions;
        this.suggestions = suggestions;
        this.priceHistory = priceHistory;
        this.actionService = actionService;
        this.users = users;
        this.tx = tx;
    }

    /** Splits and bonuses first, then the oversold check (its failure never fails the run). */
    public Summary detectForUser(UUID userId) {
        Summary summary = detectEvents(userId);
        if (oversold != null) {
            try {
                oversold.sync(userId);
            } catch (Exception e) {
                log.warn("Oversold check failed for user {}: {}", userId, e.toString());
            }
        }
        return summary;
    }

    private Summary detectEvents(UUID userId) {
        List<InvestmentTransaction> trades = transactions.findStockTradesOwned(userId);
        if (trades.isEmpty()) {
            return new Summary(0, 0, 0);
        }
        Map<UUID, Instrument> instruments = new LinkedHashMap<>();
        Map<UUID, List<Trade>> tradesByInstrument = new HashMap<>();
        LocalDate firstTradeDate = null;
        for (InvestmentTransaction t : trades) {
            Instrument instrument = t.getHolding().getInstrument();
            instruments.putIfAbsent(instrument.getId(), instrument);
            BigDecimal signed = t.getType() == InvestmentTransactionType.sell ? t.getQuantity().negate() : t.getQuantity();
            tradesByInstrument.computeIfAbsent(instrument.getId(), k -> new ArrayList<>()).add(new Trade(t.getTradeDate(), signed));
            if (firstTradeDate == null || t.getTradeDate().isBefore(firstTradeDate)) {
                firstTradeDate = t.getTradeDate();
            }
        }
        eventStore.ensureCoverage(firstTradeDate);

        List<MarketCorporateEvent> candidates = events.findFrom(SOURCE, firstTradeDate);
        Map<String, List<MarketCorporateEvent>> bySymbol = new HashMap<>();
        Map<String, Set<String>> symbolsByIsinPrefix = new HashMap<>();
        for (MarketCorporateEvent e : candidates) {
            bySymbol.computeIfAbsent(norm(e.getSymbol()), k -> new ArrayList<>()).add(e);
            String prefix = isinPrefix(e.getIsin());
            if (prefix != null) {
                symbolsByIsinPrefix.computeIfAbsent(prefix, k -> new HashSet<>()).add(norm(e.getSymbol()));
            }
        }

        LocalDate today = AppTime.today();
        Map<UUID, Set<LocalDate>> actionDates = new HashMap<>();
        Map<UUID, List<CorporateAction>> ratioActions = new HashMap<>();
        for (CorporateAction ca : actions.findOwnedByInstrumentIds(userId, instruments.keySet())) {
            actionDates.computeIfAbsent(ca.getInstrument().getId(), k -> new HashSet<>()).add(ca.getExDate());
            if (ca.getType() == CorporateActionType.split || ca.getType() == CorporateActionType.bonus) {
                ratioActions.computeIfAbsent(ca.getInstrument().getId(), k -> new ArrayList<>()).add(ca);
            }
        }
        Set<String> suggestionKeys = new HashSet<>();
        for (CorporateActionSuggestion s : suggestions.findAllOwned(userId)) {
            suggestionKeys.add(suggestionKey(s.getInstrument().getId(), s.getKind(), s.getExDate()));
        }

        int applied = 0;
        int pending = 0;
        int skipped = 0;
        List<CorporateActionSuggestion> toSave = new ArrayList<>();
        for (Instrument instrument : instruments.values()) {
            TreeMap<LocalDate, List<MarketCorporateEvent>> byDay = new TreeMap<>();
            for (MarketCorporateEvent e : matchEvents(instrument, bySymbol, symbolsByIsinPrefix)) {
                byDay.computeIfAbsent(e.getExDate(), k -> new ArrayList<>()).add(e);
            }
            List<Trade> own = tradesByInstrument.get(instrument.getId());
            Set<LocalDate> existingActionDates = actionDates.getOrDefault(instrument.getId(), Set.of());
            PriceHistoryClient.SplitResult yahoo = null;
            boolean yahooUnavailable = false;
            for (Map.Entry<LocalDate, List<MarketCorporateEvent>> day : byDay.entrySet()) {
                LocalDate exDate = day.getKey();
                List<MarketCorporateEvent> group = day.getValue();
                if (exDate.isAfter(today)) {
                    continue; // not happened yet: picked up on a later run
                }
                if (held(own, exDate).signum() <= 0 || existingActionDates.contains(exDate)) {
                    skipped++;
                    continue;
                }
                if (group.size() > 1 || !group.get(0).isClean()) {
                    for (MarketCorporateEvent e : group) {
                        SuggestionKind kind = SuggestionKind.valueOf(e.getParsedType());
                        if (!suggestionKeys.add(suggestionKey(instrument.getId(), kind, exDate))) {
                            skipped++;
                            continue;
                        }
                        SuggestionReason reason = group.size() > 1 ? SuggestionReason.COMBINED_SAME_DAY
                                : kind == SuggestionKind.demerger || kind == SuggestionKind.merger
                                        ? SuggestionReason.NEEDS_DETAILS : SuggestionReason.UNCLEAR_TEXT;
                        toSave.add(pending(userId, instrument, e, kind, reason, null));
                        pending++;
                    }
                    continue;
                }
                MarketCorporateEvent e = group.get(0);
                SuggestionKind kind = SuggestionKind.valueOf(e.getParsedType());
                if (suggestionKeys.contains(suggestionKey(instrument.getId(), kind, exDate))) {
                    skipped++;
                    continue;
                }
                if (yahooUnavailable) {
                    continue;
                }
                SuggestionReason hold = null;
                BigDecimal feedFactor = null;
                if (nearbyHandEntered(ratioActions.get(instrument.getId()), e)) {
                    hold = SuggestionReason.POSSIBLE_DUPLICATE;
                } else if (instrument.getYahooSymbol() == null || instrument.getYahooSymbol().isBlank()) {
                    hold = SuggestionReason.NO_PRICE_SYMBOL;
                } else {
                    if (yahoo == null) {
                        yahoo = priceHistory.fetchYahooSplits(instrument.getYahooSymbol(), firstTradeDate, today);
                    }
                    switch (yahoo.status()) {
                        case RATE_LIMITED, FAILED -> yahooUnavailable = true;
                        case NOT_FOUND -> hold = SuggestionReason.NO_PRICE_SYMBOL;
                        case OK -> {
                            feedFactor = yahoo.splits().get(exDate);
                            hold = compare(feedFactor, e);
                        }
                    }
                    if (yahooUnavailable) {
                        continue;
                    }
                    if (yahoo.status() == PriceHistoryClient.SplitStatus.OK && !yahoo.splits().containsKey(exDate)
                            && !exDate.isBefore(today.minusDays(2))) {
                        continue; // the price feed may not have it yet: retry on the next run
                    }
                }
                suggestionKeys.add(suggestionKey(instrument.getId(), kind, exDate));
                if (hold != null) {
                    toSave.add(pending(userId, instrument, e, kind, hold, feedFactor));
                    pending++;
                } else if (apply(userId, instrument, e, kind, feedFactor)) {
                    applied++;
                } else {
                    skipped++;
                }
            }
        }
        if (!toSave.isEmpty()) {
            try {
                suggestions.saveAll(toSave);
            } catch (DataIntegrityViolationException dup) {
                for (CorporateActionSuggestion s : toSave) {
                    try {
                        suggestions.save(s);
                    } catch (DataIntegrityViolationException ignored) {
                        // saved meanwhile by a concurrent run
                    }
                }
            }
        }
        log.info("Corporate action detection for user {}: applied={} pending={} skipped={}", userId, applied, pending, skipped);
        return new Summary(applied, pending, skipped);
    }

    /** Null when the price feed shows the same split on the day; otherwise why it does not. */
    private static SuggestionReason compare(BigDecimal yahooFactor, MarketCorporateEvent e) {
        if (yahooFactor == null) {
            return SuggestionReason.YAHOO_MISSING;
        }
        BigDecimal expected = BigDecimal.valueOf(e.getRatioTo()).divide(BigDecimal.valueOf(e.getRatioFrom()), MathContext.DECIMAL64);
        return Math.abs(yahooFactor.doubleValue() - expected.doubleValue()) <= TOLERANCE ? null : SuggestionReason.YAHOO_MISMATCH;
    }

    /** True once applied; false when a concurrent run recorded the same suggestion first (this attempt rolls back). */
    private boolean apply(UUID userId, Instrument instrument, MarketCorporateEvent e, SuggestionKind kind,
                          BigDecimal feedFactor) {
        try {
            applyInTx(userId, instrument, e, kind, feedFactor);
            return true;
        } catch (DataIntegrityViolationException dup) {
            log.info("Corporate action for user {} already recorded by a concurrent run", userId);
            return false;
        }
    }

    /** A split or bonus of the same ratio the user already has within 5 days of the event's ex-date. */
    private static boolean nearbyHandEntered(List<CorporateAction> existing, MarketCorporateEvent e) {
        if (existing == null) {
            return false;
        }
        for (CorporateAction ca : existing) {
            if (Objects.equals(ca.getRatioFrom(), e.getRatioFrom()) && Objects.equals(ca.getRatioTo(), e.getRatioTo())
                    && Math.abs(ChronoUnit.DAYS.between(ca.getExDate(), e.getExDate())) <= 5) {
                return true;
            }
        }
        return false;
    }

    private void applyInTx(UUID userId, Instrument instrument, MarketCorporateEvent e, SuggestionKind kind,
                           BigDecimal feedFactor) {
        tx.executeWithoutResult(status -> {
            CorporateAction created = actionService.createDetected(userId, instrument.getId(),
                    kind == SuggestionKind.bonus ? CorporateActionType.bonus : CorporateActionType.split,
                    e.getRatioFrom(), e.getRatioTo(), e.getExDate(), "Detected automatically: " + e.getSubject());
            if (created == null) {
                return; // the user already has this action that day
            }
            CorporateActionSuggestion s = base(userId, instrument, e, kind, SuggestionStatus.auto_applied, null);
            s.setCorporateAction(created);
            s.setFeedFactor(feedFactor);
            suggestions.save(s);
        });
    }

    private CorporateActionSuggestion pending(UUID userId, Instrument instrument, MarketCorporateEvent e,
                                              SuggestionKind kind, SuggestionReason reason, BigDecimal feedFactor) {
        CorporateActionSuggestion s = base(userId, instrument, e, kind, SuggestionStatus.pending, reason);
        s.setFeedFactor(feedFactor);
        return s;
    }

    private CorporateActionSuggestion base(UUID userId, Instrument instrument, MarketCorporateEvent e, SuggestionKind kind,
                                           SuggestionStatus status, SuggestionReason reason) {
        CorporateActionSuggestion s = new CorporateActionSuggestion();
        s.setUser(users.getReferenceById(userId));
        s.setInstrument(instrument);
        s.setKind(kind);
        s.setExDate(e.getExDate());
        s.setRatioFrom(e.getRatioFrom());
        s.setRatioTo(e.getRatioTo());
        s.setSubject(e.getSubject());
        s.setStatus(status);
        s.setReason(reason);
        s.setCreatedAt(Instant.now());
        return s;
    }

    /**
     * Events for the instrument: same symbol, or (renamed symbol) the same ISIN prefix when that prefix belongs to one
     * symbol in the feed. The feed's ISIN goes stale after a face-value change, so a full ISIN match is never required.
     */
    private static List<MarketCorporateEvent> matchEvents(Instrument instrument, Map<String, List<MarketCorporateEvent>> bySymbol,
                                                          Map<String, Set<String>> symbolsByIsinPrefix) {
        Set<String> symbols = new HashSet<>();
        if (instrument.getSymbol() != null && !instrument.getSymbol().isBlank()) {
            symbols.add(norm(instrument.getSymbol()));
        }
        String prefix = isinPrefix(instrument.getIsin());
        Set<String> byPrefix = prefix == null ? null : symbolsByIsinPrefix.get(prefix);
        if (byPrefix != null && byPrefix.size() == 1) {
            symbols.addAll(byPrefix);
        }
        List<MarketCorporateEvent> out = new ArrayList<>();
        for (String symbol : symbols) {
            out.addAll(bySymbol.getOrDefault(symbol, List.of()));
        }
        return out;
    }

    private static BigDecimal held(List<Trade> trades, LocalDate before) {
        BigDecimal sum = BigDecimal.ZERO;
        for (Trade t : trades) {
            if (t.date().isBefore(before)) {
                sum = sum.add(t.signedQty());
            }
        }
        return sum;
    }

    private static String norm(String symbol) {
        return symbol.trim().toUpperCase(Locale.ROOT);
    }

    private static String isinPrefix(String isin) {
        return isin == null || isin.trim().length() < 7 ? null : isin.trim().toUpperCase(Locale.ROOT).substring(0, 7);
    }

    private static String suggestionKey(UUID instrumentId, SuggestionKind kind, LocalDate exDate) {
        return instrumentId + "|" + kind + "|" + exDate;
    }
}
