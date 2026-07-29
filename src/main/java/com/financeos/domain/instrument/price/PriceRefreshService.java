package com.financeos.domain.instrument.price;

import com.financeos.core.exception.ResourceNotFoundException;
import com.financeos.domain.holding.HoldingRepository;
import com.financeos.domain.instrument.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
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

    public PriceRefreshService(InstrumentRepository instrumentRepository,
                               InstrumentPriceRepository priceRepository,
                               HoldingRepository holdingRepository,
                               List<PriceProvider> priceProviders,
                               PriceProperties priceProperties) {
        this.instrumentRepository = instrumentRepository;
        this.priceRepository = priceRepository;
        this.holdingRepository = holdingRepository;
        this.priceProviders = priceProviders;
        this.priceProperties = priceProperties;
    }

    /**
     * Refreshes instrument prices.
     * Note: When called via an HTTP request, userFilter is active so holdingRepository.findDistinctInstrumentsHeld()
     * returns instruments held by the authenticated user. When called via the scheduled background job, UserContext
     * is clear so userFilter is inactive and instruments across all users are refreshed.
     */
    public PriceRefreshResult refresh(Optional<UUID> instrumentId) {
        List<Instrument> targets;
        if (instrumentId.isPresent()) {
            UUID id = instrumentId.get();
            Instrument inst = instrumentRepository.findById(id)
                    .orElseThrow(() -> new ResourceNotFoundException("Instrument", id));
            targets = List.of(inst);
        } else {
            targets = holdingRepository.findDistinctInstrumentsHeld();
        }

        int refreshedCount = 0;
        int skippedCount = 0;
        List<PriceRefreshResult.FailedItem> failedList = new ArrayList<>();
        ZoneId zoneId = ZoneId.of(priceProperties.getTimezone() != null ? priceProperties.getTimezone() : "Asia/Kolkata");

        if (targets.isEmpty()) {
            return new PriceRefreshResult(0, 0, failedList, LocalDate.now(zoneId));
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

        for (Map.Entry<PriceProvider, List<Instrument>> entry : grouped.entrySet()) {
            PriceProvider provider = entry.getKey();
            List<Instrument> providerTargets = entry.getValue();

            Map<UUID, PriceQuote> quotes = Collections.emptyMap();
            try {
                quotes = provider.fetch(providerTargets);
            } catch (Exception e) {
                log.error("Provider {} fetch thrown an unexpected error", provider.source(), e);
            }

            for (Instrument inst : providerTargets) {
                try {
                    PriceQuote quote = quotes.get(inst.getId());
                    if (quote != null && quote.close() != null && quote.asOf() != null) {
                        Optional<InstrumentPrice> existingPrice = priceRepository.findByInstrumentIdAndAsOf(inst.getId(), quote.asOf());
                        if (existingPrice.isPresent()) {
                            InstrumentPrice ep = existingPrice.get();
                            if (ep.getSource() == PriceSource.MANUAL) {
                                skippedCount++;
                                log.info("Skipping price refresh for instrument {} on {} as MANUAL price override exists.", inst.getName(), quote.asOf());
                                continue;
                            }
                            ep.setClose(quote.close());
                            ep.setSource(provider.source());
                            priceRepository.save(ep);
                            refreshedCount++;
                        } else {
                            InstrumentPrice newPrice = new InstrumentPrice(inst, quote.asOf(), quote.close(), provider.source());
                            priceRepository.save(newPrice);
                            refreshedCount++;
                        }
                    } else {
                        failedList.add(new PriceRefreshResult.FailedItem(
                                inst.getId(),
                                label(inst),
                                describeFetchFailure(inst, provider)
                        ));
                    }
                } catch (Exception e) {
                    log.error("Error processing price quote for instrument {}", inst.getName(), e);
                    failedList.add(new PriceRefreshResult.FailedItem(
                            inst.getId(),
                            label(inst),
                            "Error saving the fetched price: " + e.getMessage()
                    ));
                }
            }
        }

        return new PriceRefreshResult(refreshedCount, skippedCount, failedList, LocalDate.now(zoneId));
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
