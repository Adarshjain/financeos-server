package com.financeos.domain.instrument.price;

import com.financeos.core.observability.Ops;
import com.financeos.core.observability.Timing;
import com.financeos.core.exception.ResourceNotFoundException;
import com.financeos.domain.holding.HoldingRepository;
import com.financeos.domain.instrument.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import net.logstash.logback.argument.StructuredArguments;
import com.financeos.core.observability.Events;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.financeos.core.time.AppTime;

import java.time.DayOfWeek;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.*;

@Service
@Transactional
public class PriceRefreshService {

    private static final Logger log = LoggerFactory.getLogger(PriceRefreshService.class);

    private final InstrumentRepository instrumentRepository;
    private final InstrumentPriceRepository priceRepository;
    private final HoldingRepository holdingRepository;
    private final List<PriceProvider> priceProviders;
    private final PriceProperties priceProperties;
    private final InstrumentClassificationService classificationService;

    /** Runs the feed writes in one short transaction; null (plain construction) runs them in the caller's context. */
    private org.springframework.transaction.support.TransactionTemplate writeTx;
    /** Short transaction in which the tenant filter is enabled for the caller's own work (target lookup, classification). */
    private org.springframework.transaction.support.TransactionTemplate scopedTx;
    private com.financeos.core.security.UserFilterEnabler userFilterEnabler;

    @org.springframework.beans.factory.annotation.Autowired
    public PriceRefreshService(InstrumentRepository instrumentRepository,
                               InstrumentPriceRepository priceRepository,
                               HoldingRepository holdingRepository,
                               List<PriceProvider> priceProviders,
                               PriceProperties priceProperties,
                               InstrumentClassificationService classificationService,
                               org.springframework.transaction.PlatformTransactionManager transactionManager,
                               com.financeos.core.security.UserFilterEnabler userFilterEnabler) {
        this(instrumentRepository, priceRepository, holdingRepository, priceProviders, priceProperties, classificationService, transactionManager);
        this.userFilterEnabler = userFilterEnabler;
    }

    public PriceRefreshService(InstrumentRepository instrumentRepository,
                               InstrumentPriceRepository priceRepository,
                               HoldingRepository holdingRepository,
                               List<PriceProvider> priceProviders,
                               PriceProperties priceProperties,
                               InstrumentClassificationService classificationService,
                               org.springframework.transaction.PlatformTransactionManager transactionManager) {
        this(instrumentRepository, priceRepository, holdingRepository, priceProviders, priceProperties, classificationService);
        this.writeTx = new org.springframework.transaction.support.TransactionTemplate(transactionManager);
        this.scopedTx = new org.springframework.transaction.support.TransactionTemplate(transactionManager);
    }

    public PriceRefreshService(InstrumentRepository instrumentRepository,
                               InstrumentPriceRepository priceRepository,
                               HoldingRepository holdingRepository,
                               List<PriceProvider> priceProviders,
                               PriceProperties priceProperties,
                               InstrumentClassificationService classificationService) {
        this.instrumentRepository = instrumentRepository;
        this.priceRepository = priceRepository;
        this.holdingRepository = holdingRepository;
        this.priceProviders = priceProviders;
        this.priceProperties = priceProperties;
        this.classificationService = classificationService;
    }

    /**
     * Refreshes instrument prices.
     * Note: When no instrumentId is given, only actively held instruments (net open quantity &gt; 0)
     * are refreshed — fully sold-out positions are skipped. When called via an HTTP request, userFilter
     * is active so only the authenticated user's active instruments are returned. When called via the
     * scheduled background job, UserContext is clear so userFilter is inactive and active instruments
     * across all users are refreshed. The method itself runs with no transaction (the network fetches must not
     * hold a connection), so the target lookup and the classification run in their own short transactions in
     * which the tenant filter is enabled ({@link #scoped}); without that the filter would never be on.
     */
    @Transactional(propagation = org.springframework.transaction.annotation.Propagation.NOT_SUPPORTED)
    public PriceRefreshResult refresh(Optional<UUID> instrumentId) {
        List<Instrument> targets = Timing.call(Ops.PRICES_LOAD_TARGETS, () -> scoped(() -> {
            if (instrumentId.isPresent()) {
                UUID id = instrumentId.get();
                Instrument inst = instrumentRepository.findById(id)
                        .orElseThrow(() -> new ResourceNotFoundException("Instrument", id));
                return List.of(inst);
            }
            List<UUID> activeIds = holdingRepository.findDistinctActiveInstrumentIdsHeld();
            return activeIds.isEmpty() ? List.<Instrument>of()
                    : instrumentRepository.findAllById(activeIds).stream().filter(PriceRefreshService::dueForCheck).toList();
        }));

        int refreshedCount = 0;
        int skippedCount = 0;
        List<PriceRefreshResult.FailedItem> failedList = new ArrayList<>();
        List<PriceRefreshResult.RefreshedItem> refreshedItems = new ArrayList<>();
        List<PriceRefreshResult.SkippedItem> skippedItems = new ArrayList<>();
        ZoneId zoneId = ZoneId.of(priceProperties.getTimezone() != null ? priceProperties.getTimezone() : "Asia/Kolkata");

        if (targets.isEmpty()) {
            return new PriceRefreshResult(0, 0, failedList, LocalDate.now(zoneId), refreshedItems, skippedItems);
        }

        Map<PriceProvider, List<Instrument>> grouped = new LinkedHashMap<>();
        for (Instrument inst : targets) {
            Optional<PriceProvider> matchedProvider = priceProviders.stream()
                    .filter(p -> p.supports(inst))
                    .findFirst();

            if (matchedProvider.isPresent()) {
                grouped.computeIfAbsent(matchedProvider.get(), k -> new ArrayList<>()).add(inst);
            } else {
                failedList.add(new PriceRefreshResult.FailedItem(
                        inst.getId(),
                        label(inst),
                        "No price provider is available for " + inst.getType() + " instruments."
                ));
            }
        }

        Timing.run(Ops.PRICES_CLASSIFY, () -> scoped(() -> {
            classify(targets);
            return null;
        }));

        for (Map.Entry<PriceProvider, List<Instrument>> entry : grouped.entrySet()) {
            PriceProvider provider = entry.getKey();
            List<Instrument> providerTargets = entry.getValue();

            Map<UUID, PriceQuote> quotes = Collections.emptyMap();
            try {
                quotes = Timing.call(Ops.PRICES_FETCH, () -> provider.fetch(providerTargets));
            } catch (Exception e) {
                log.error("Provider {} fetch thrown an unexpected error", provider.source(), e);
            }

            for (Instrument inst : providerTargets) {
                try {
                    PriceQuote quote = quotes.get(inst.getId());
                    if (quote != null && quote.close() != null && quote.asOf() != null) {
                        // Each instrument's DB write is its own transaction: one failing (even at commit)
                        // cannot mark a shared transaction rollback-only and lose the other prices.
                        Object outcome = Timing.call(Ops.PRICES_STORE, () -> writeTx == null ? storePrice(inst, provider, quote)
                                : writeTx.execute(status -> storePrice(inst, provider, quote)));
                        if (outcome instanceof PriceRefreshResult.SkippedItem skippedItem) {
                            skippedCount++;
                            skippedItems.add(skippedItem);
                        } else if (outcome instanceof PriceRefreshResult.RefreshedItem refreshedItem) {
                            refreshedCount++;
                            refreshedItems.add(refreshedItem);
                        }
                    } else {
                        failedList.add(new PriceRefreshResult.FailedItem(
                                inst.getId(),
                                label(inst),
                                describeFetchFailure(inst, provider)
                        ));
                    }
                } catch (Exception e) {
                    Throwable root = rootCause(e);
                    String rootMessage = root.getMessage() != null ? root.getMessage() : root.toString();
                    logItemFailed(inst, root, rootMessage);
                    failedList.add(new PriceRefreshResult.FailedItem(
                            inst.getId(),
                            label(inst),
                            "Error saving the fetched price: " + rootMessage
                    ));
                }
            }
            applyPriceStatus(providerTargets, quotes, provider.takeNotFound());
        }

        return new PriceRefreshResult(refreshedCount, skippedCount, failedList, LocalDate.now(zoneId), refreshedItems, skippedItems);
    }

    /**
     * Runs {@code work} in a short transaction with the tenant filter enabled for the current user (no filter
     * when there is no user, i.e. the scheduled job), so repository reads see only the caller's rows. Plain
     * construction (no transaction manager) runs it as is.
     */
    private <T> T scoped(java.util.function.Supplier<T> work) {
        if (scopedTx == null) {
            return work.get();
        }
        return scopedTx.execute(status -> {
            if (userFilterEnabler != null) {
                userFilterEnabler.enable();
            }
            return work.get();
        });
    }

    /** Writes one instrument's quote (runs inside its own transaction); returns the refreshed or skipped item. */
    private Object storePrice(Instrument inst, PriceProvider provider, PriceQuote quote) {
        // Only the shared (ownerless) row of the date: a user's own MANUAL price is a
        // separate row the refresh never reads, overwrites or deletes.
        Optional<InstrumentPrice> existingPrice = priceRepository.findByInstrumentIdAndAsOfAndUserIdIsNull(inst.getId(), quote.asOf());
        if (existingPrice.isPresent()) {
            InstrumentPrice ep = existingPrice.get();
            if (ep.getSource() == PriceSource.MANUAL) {
                log.info("Skipping price refresh for instrument {} on {} as a legacy shared MANUAL price exists.", inst.getName(), quote.asOf());
                return new PriceRefreshResult.SkippedItem(
                        inst.getId(),
                        label(inst),
                        "A manually entered price already exists for " + quote.asOf() + ", so it was kept."
                );
            }
            ep.setClose(quote.close());
            ep.setSource(provider.source());
            ep.setPriceTime(quote.at());
            if (quote.previousClose() != null) {
                ep.setPreviousClose(quote.previousClose());
            }
            priceRepository.save(ep);
        } else {
            InstrumentPrice newPrice = new InstrumentPrice(inst, quote.asOf(), quote.close(), provider.source());
            newPrice.setPriceTime(quote.at());
            newPrice.setPreviousClose(quote.previousClose());
            priceRepository.save(newPrice);
        }
        return new PriceRefreshResult.RefreshedItem(inst.getId(), label(inst), quote.close(), quote.asOf(), provider.source());
    }

    /** How long a {@code not_trading} instrument is left alone before it is looked up once more. */
    static final Duration NOT_TRADING_RECHECK = Duration.ofDays(7);

    /** Whether a bulk refresh should look the instrument up: anything but a {@code not_trading} one marked under 7 days ago. */
    static boolean dueForCheck(Instrument inst) {
        if (!"not_trading".equals(inst.getPriceStatus()) || inst.getPriceStatusAt() == null) {
            return true;
        }
        return !inst.getPriceStatusAt().isAfter(Instant.now().minus(NOT_TRADING_RECHECK));
    }

    /**
     * After a provider's fetch: instruments the feed answered "not found" for on every host (and gave no quote) are
     * marked {@code not_trading} as of now (a repeat bumps the time); instruments that were marked and got a quote
     * are cleared. One update statement each, never inside the per-quote loop; a failure only logs.
     */
    private void applyPriceStatus(List<Instrument> fetched, Map<UUID, PriceQuote> quotes, Set<UUID> notFound) {
        List<UUID> mark = new ArrayList<>();
        List<UUID> clear = new ArrayList<>();
        for (Instrument inst : fetched) {
            PriceQuote quote = quotes.get(inst.getId());
            boolean priced = quote != null && quote.close() != null && quote.asOf() != null;
            if (priced) {
                if (inst.getPriceStatus() != null) {
                    clear.add(inst.getId());
                }
            } else if (notFound != null && notFound.contains(inst.getId())) {
                mark.add(inst.getId());
            }
        }
        if (mark.isEmpty() && clear.isEmpty()) {
            return;
        }
        Instant now = Instant.now();
        Runnable write = () -> {
            if (!mark.isEmpty()) {
                instrumentRepository.markNotTrading(mark, now);
            }
            if (!clear.isEmpty()) {
                instrumentRepository.clearPriceStatus(clear);
            }
        };
        try {
            if (writeTx == null) {
                write.run();
            } else {
                writeTx.executeWithoutResult(status -> write.run());
            }
            for (Instrument inst : fetched) {
                if (mark.contains(inst.getId())) {
                    inst.setPriceStatus("not_trading");
                    inst.setPriceStatusAt(now);
                } else if (clear.contains(inst.getId())) {
                    inst.setPriceStatus(null);
                    inst.setPriceStatusAt(null);
                }
            }
        } catch (Exception e) {
            log.warn("Could not update the price status of {} instruments: {}", mark.size() + clear.size(), e.getMessage());
        }
    }

    private static Throwable rootCause(Throwable t) {
        Throwable root = t;
        for (int i = 0; i < 20 && root.getCause() != null && root.getCause() != root; i++) {
            root = root.getCause();
        }
        return root;
    }

    /** One structured WARN naming the instrument that failed (event=ingest.item_failed), for Loki and the alert rule. */
    private void logItemFailed(Instrument inst, Throwable root, String rootMessage) {
        String oneLine = rootMessage.replaceAll("\\s+", " ").trim();
        if (oneLine.length() > 300) {
            oneLine = oneLine.substring(0, 300);
        }
        String jobName = MDC.get("jobName");
        log.warn("Price refresh failed for instrument {}",
                inst.getId(),
                StructuredArguments.keyValue("event", Events.INGEST_ITEM_FAILED),
                StructuredArguments.keyValue("jobName", jobName != null ? jobName : "price-refresh"),
                StructuredArguments.keyValue("jobId", MDC.get("jobId")),
                StructuredArguments.keyValue("itemType", "instrument"),
                StructuredArguments.keyValue("itemId", inst.getId()),
                StructuredArguments.keyValue("symbol", inst.getSymbol()),
                StructuredArguments.keyValue("errorClass", root.getClass().getSimpleName()),
                StructuredArguments.keyValue("error", oneLine),
                StructuredArguments.keyValue("attempt", 1),
                StructuredArguments.keyValue("status", "FAILED"));
    }

    private static final LocalTime INTRADAY_OPEN = LocalTime.of(9, 15);
    private static final LocalTime INTRADAY_CLOSE = LocalTime.of(15, 35);

    /** Whether {@code istNow} is inside the NSE session the intraday job runs in: Mon-Fri, 09:15 up to 15:35. */
    static boolean inIntradayWindow(LocalDateTime istNow) {
        DayOfWeek day = istNow.getDayOfWeek();
        if (day == DayOfWeek.SATURDAY || day == DayOfWeek.SUNDAY) {
            return false;
        }
        LocalTime t = istNow.toLocalTime();
        return !t.isBefore(INTRADAY_OPEN) && t.isBefore(INTRADAY_CLOSE);
    }

    /**
     * A quote is live today when Yahoo says the market is REGULAR, or (no market state) its trade time
     * falls on today's IST date; skips holidays and stale quotes.
     */
    static boolean isLiveToday(PriceQuote quote, LocalDate today, ZoneId zone) {
        LocalDate tradeDate = quote.at() == null ? null : quote.at().atZone(zone).toLocalDate();
        if (quote.marketState() != null) {
            return "REGULAR".equals(quote.marketState()) && (tradeDate == null || !tradeDate.isBefore(today));
        }
        return tradeDate != null && tradeDate.equals(today);
    }

    /**
     * The intraday Yahoo refresh: inside the session window only, fetches every actively held
     * Yahoo-priced instrument in one batched provider call and upserts the shared feed row of today
     * (IST) for each live quote. A user's or legacy MANUAL price of today is kept. Returns the number
     * of rows written (0 outside the window).
     */
    @Transactional(propagation = org.springframework.transaction.annotation.Propagation.NOT_SUPPORTED)
    public int refreshIntraday() {
        if (!inIntradayWindow(AppTime.now())) {
            return 0;
        }
        return refreshFeed(PriceSource.YAHOO, true);
    }

    /** Refreshes only the actively held instruments priced by {@code source} (the AMFI crons). */
    @Transactional(propagation = org.springframework.transaction.annotation.Propagation.NOT_SUPPORTED)
    public int refreshSource(PriceSource source) {
        return refreshFeed(source, false);
    }

    private int refreshFeed(PriceSource source, boolean liveOnly) {
        PriceProvider provider = priceProviders.stream().filter(p -> p.source() == source).findFirst().orElse(null);
        if (provider == null) {
            return 0;
        }
        List<Instrument> targets = Timing.call(Ops.PRICES_LOAD_TARGETS, () -> {
            List<UUID> activeIds = holdingRepository.findDistinctActiveInstrumentIdsHeld();
            return activeIds.isEmpty() ? List.<Instrument>of()
                    : instrumentRepository.findAllById(activeIds).stream().filter(provider::supports)
                            .filter(PriceRefreshService::dueForCheck).toList();
        });
        if (targets.isEmpty()) {
            return 0;
        }
        ZoneId zone = AppTime.zone();
        LocalDate today = AppTime.today();
        // Fetched with no transaction open (the entry points are NOT_SUPPORTED); only the writes run in one.
        Map<UUID, PriceQuote> quotes = Timing.call(Ops.PRICES_FETCH, () -> liveOnly ? provider.fetchLive(targets) : provider.fetch(targets));
        Set<UUID> notFound = provider.takeNotFound();
        int written;
        if (writeTx == null) {
            written = Timing.call(Ops.PRICES_STORE, () -> storeFeed(source, liveOnly, targets, quotes, today, zone));
        } else {
            Integer w = Timing.call(Ops.PRICES_STORE, () -> writeTx.execute(status -> storeFeed(source, liveOnly, targets, quotes, today, zone)));
            written = w == null ? 0 : w;
        }
        applyPriceStatus(targets, quotes, notFound);
        return written;
    }

    private int storeFeed(PriceSource source, boolean liveOnly, List<Instrument> targets, Map<UUID, PriceQuote> quotes,
                          LocalDate today, ZoneId zone) {
        int written = 0;
        for (Instrument inst : targets) {
            PriceQuote quote = quotes.get(inst.getId());
            if (quote == null || quote.close() == null || quote.asOf() == null) {
                continue;
            }
            LocalDate asOf = quote.asOf();
            if (liveOnly) {
                if (!isLiveToday(quote, today, zone)) {
                    continue;
                }
                asOf = today;
            }
            try {
                Optional<InstrumentPrice> existing = priceRepository.findByInstrumentIdAndAsOfAndUserIdIsNull(inst.getId(), asOf);
                InstrumentPrice row = existing.orElseGet(() -> new InstrumentPrice(inst, quote.asOf(), quote.close(), source));
                if (existing.isPresent() && row.getSource() == PriceSource.MANUAL) {
                    continue;
                }
                row.setAsOf(asOf);
                row.setClose(quote.close());
                row.setSource(source);
                row.setPriceTime(quote.at());
                row.setPreviousClose(quote.previousClose());
                priceRepository.save(row);
                written++;
            } catch (Exception e) {
                log.error("Error storing {} price for instrument {}", source, inst.getName(), e);
            }
        }
        return written;
    }

    /**
     * Keeps the refreshed instruments' asset classes current (AMFI header for funds, rules for the
     * rest; MANUAL untouched). Best effort: a classification problem never fails the price refresh.
     */
    private void classify(List<Instrument> targets) {
        for (Instrument inst : targets) {
            try {
                if (classificationService.classify(inst)) {
                    instrumentRepository.save(inst);
                }
            } catch (Exception e) {
                log.warn("Could not classify instrument {}: {}", inst.getId(), e.getMessage());
            }
        }
    }

    /** Human-readable label for error messages: "Name (SYMBOL)" when the symbol adds information. */
    private String label(Instrument inst) {
        String name = inst.getName();
        String symbol = inst.getSymbol();
        if (symbol != null && !symbol.isBlank() && !symbol.equalsIgnoreCase(name)) {
            return name + " (" + symbol + ")";
        }
        return name;
    }

    /**
     * Builds an actionable reason for why a quote could not be fetched, based on what the instrument
     * is missing vs. what the provider actually returned. Replaces the old generic "Failed to fetch".
     */
    private String describeFetchFailure(Instrument inst, PriceProvider provider) {
        InstrumentType type = inst.getType();

        if (type == InstrumentType.stock || type == InstrumentType.etf) {
            if (inst.getYahooSymbol() == null || inst.getYahooSymbol().isBlank()) {
                String guess = (inst.getSymbol() != null && !inst.getSymbol().isBlank())
                        ? inst.getSymbol().toUpperCase() + ".NS"
                        : "e.g. RELIANCE.NS";
                return "No Yahoo symbol is set, so its price can't be fetched. Edit the instrument and add one (" + guess + ").";
            }
            return "Yahoo has no data for \"" + inst.getYahooSymbol() + "\". The ticker may have been renamed or delisted, "
                    + "or Yahoo is temporarily unreachable — check and update the Yahoo symbol.";
        }

        if (type == InstrumentType.mutual_fund) {
            boolean noAmfi = inst.getAmfiCode() == null || inst.getAmfiCode().isBlank();
            boolean noIsin = inst.getIsin() == null || inst.getIsin().isBlank();
            if (noAmfi && noIsin) {
                return "No AMFI code or ISIN is set, so its NAV can't be fetched. Edit the instrument and add one.";
            }
            String ref = !noAmfi ? "AMFI scheme code " + inst.getAmfiCode() : "ISIN " + inst.getIsin();
            return "AMFI has no current NAV for " + ref + " — verify it is correct.";
        }

        return "Could not fetch a price from provider " + provider.source() + ".";
    }
}
