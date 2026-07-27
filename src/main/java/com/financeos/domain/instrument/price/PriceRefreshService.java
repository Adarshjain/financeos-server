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
                        inst.getSymbol() != null ? inst.getSymbol() : inst.getName(),
                        "No supporting price provider found for instrument type " + inst.getType()
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
                                inst.getSymbol() != null ? inst.getSymbol() : inst.getName(),
                                "Failed to fetch price quote from provider " + provider.source()
                        ));
                    }
                } catch (Exception e) {
                    log.error("Error processing price quote for instrument {}", inst.getName(), e);
                    failedList.add(new PriceRefreshResult.FailedItem(
                            inst.getId(),
                            inst.getSymbol() != null ? inst.getSymbol() : inst.getName(),
                            "Error saving price quote: " + e.getMessage()
                    ));
                }
            }
        }

        return new PriceRefreshResult(refreshedCount, skippedCount, failedList, LocalDate.now(zoneId));
    }
}
