package com.financeos.domain.instrument.price;

import com.financeos.core.observability.Events;
import com.financeos.core.time.AppTime;
import com.financeos.domain.holding.HoldingRepository;
import com.financeos.domain.instrument.Instrument;
import com.financeos.domain.instrument.InstrumentPriceRepository;
import com.financeos.domain.instrument.InstrumentRepository;
import com.financeos.domain.instrument.InstrumentType;
import com.financeos.domain.instrument.corporateaction.CorporateActionRepository;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import net.logstash.logback.argument.StructuredArguments;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Self-detection for price coverage: the {@code financeos.price.held_unpriced} gauge counts held instruments (open
 * through trades, or a demerger/merger target) that have a price symbol yet were never priced or whose latest price is
 * more than {@value #STALE_DAYS} day older than the market date (the newest price among held instruments of the same
 * kind: stocks and ETFs, or mutual funds, or NPS schemes; a kind with no price at all counts none). Instruments the feeds stopped quoting ({@code not_trading}, {@code delisted}) are skipped.
 * {@link #check()} runs after the daily refresh, sets the gauge and logs up to {@value #LOG_LIMIT} symbols at WARN.
 */
@Component
public class PriceCoverageMonitor {

    private static final Logger log = LoggerFactory.getLogger(PriceCoverageMonitor.class);
    static final int STALE_DAYS = 1;
    static final int LOG_LIMIT = 10;
    static final int LISTING_GRACE_DAYS = 60;
    private static final int CHUNK = 500;

    private final HoldingRepository holdingRepository;
    private final CorporateActionRepository corporateActionRepository;
    private final InstrumentRepository instrumentRepository;
    private final InstrumentPriceRepository priceRepository;
    private final AtomicLong unpriced = new AtomicLong();

    public PriceCoverageMonitor(MeterRegistry registry, HoldingRepository holdingRepository,
                                CorporateActionRepository corporateActionRepository,
                                InstrumentRepository instrumentRepository, InstrumentPriceRepository priceRepository) {
        this.holdingRepository = holdingRepository;
        this.corporateActionRepository = corporateActionRepository;
        this.instrumentRepository = instrumentRepository;
        this.priceRepository = priceRepository;
        Gauge.builder("financeos.price.held_unpriced", unpriced, AtomicLong::get).register(registry);
    }

    /** Recomputes the gauge and logs the offenders; returns their symbols (all of them; tests read it). Never throws. */
    public List<String> check() {
        try {
            Set<UUID> held = new LinkedHashSet<>(holdingRepository.findDistinctActiveInstrumentIdsHeld());
            List<UUID> targetIds = corporateActionRepository.findDistinctMergerTargetInstrumentIds();
            held.addAll(targetIds);
            List<Instrument> candidates = held.isEmpty() ? List.of() : instrumentRepository.findAllById(held).stream()
                    .filter(PriceCoverageMonitor::hasPriceSymbol)
                    .filter(i -> !"not_trading".equals(i.getPriceStatus()) && !"delisted".equals(i.getPriceStatus()))
                    .toList();
            List<UUID> ids = candidates.stream().map(Instrument::getId).toList();
            Map<UUID, LocalDate> latest = new HashMap<>();
            for (int i = 0; i < ids.size(); i += CHUNK) {
                for (Object[] row : priceRepository.findLatestPriceDates(ids.subList(i, Math.min(i + CHUNK, ids.size())))) {
                    latest.put(UUID.fromString(String.valueOf(row[0])), toLocalDate(row[1]));
                }
            }
            // The market date per kind: the newest price among held instruments of that kind. Weekends and holidays
            // write no rows, so staleness is measured against it, not against today.
            Map<String, LocalDate> marketDate = new HashMap<>();
            for (Instrument inst : candidates) {
                LocalDate d = latest.get(inst.getId());
                if (d != null) {
                    marketDate.merge(kind(inst), d, (x, y) -> x.isAfter(y) ? x : y);
                }
            }
            Map<UUID, LocalDate> targetSince = new HashMap<>();
            List<UUID> unpricedTargets = candidates.stream().map(Instrument::getId)
                    .filter(id -> targetIds.contains(id) && !latest.containsKey(id)).toList();
            if (!unpricedTargets.isEmpty()) {
                for (Object[] row : corporateActionRepository.findEarliestExDatesByTarget(unpricedTargets)) {
                    targetSince.put(UUID.fromString(String.valueOf(row[0])), toLocalDate(row[1]));
                }
            }
            List<String> symbols = new ArrayList<>();
            for (Instrument inst : candidates) {
                LocalDate market = marketDate.get(kind(inst));
                if (market == null) {
                    continue; // nothing of this kind has a price: a feed outage, not a coverage gap
                }
                LocalDate d = latest.get(inst.getId());
                if (d == null && !listingOverdue(inst, targetIds, targetSince)) {
                    continue; // a merger/demerger target not listed yet: given time before it counts
                }
                if (d == null || d.isBefore(market.minusDays(STALE_DAYS))) {
                    symbols.add(inst.getSymbol() != null ? inst.getSymbol() : inst.getId().toString());
                }
            }
            unpriced.set(symbols.size());
            if (!symbols.isEmpty()) {
                List<String> shown = symbols.subList(0, Math.min(LOG_LIMIT, symbols.size()));
                log.warn("{} held instruments have no price within {} day of the market date: {}", symbols.size(), STALE_DAYS, shown,
                        StructuredArguments.keyValue("event", Events.PRICE_HELD_UNPRICED),
                        StructuredArguments.keyValue("count", symbols.size()));
            }
            return symbols;
        } catch (Exception e) {
            log.warn("price.held_unpriced check failed: {}", e.getMessage());
            return List.of();
        }
    }

    /** False for a never-priced demerger/merger target whose earliest ex-date is not yet {@value #LISTING_GRACE_DAYS} days old. */
    private static boolean listingOverdue(Instrument inst, List<UUID> targetIds, Map<UUID, LocalDate> targetSince) {
        if (!targetIds.contains(inst.getId())) {
            return true;
        }
        LocalDate since = targetSince.get(inst.getId());
        return since == null || since.isBefore(AppTime.today().minusDays(LISTING_GRACE_DAYS));
    }

    private static String kind(Instrument i) {
        InstrumentType t = i.getType();
        return t == InstrumentType.mutual_fund || t == InstrumentType.nps_scheme ? t.name() : "listed";
    }

    private static LocalDate toLocalDate(Object o) {
        if (o instanceof LocalDate d) {
            return d;
        }
        if (o instanceof java.sql.Date d) {
            return d.toLocalDate();
        }
        return LocalDate.parse(String.valueOf(o).substring(0, 10));
    }

    private static boolean hasPriceSymbol(Instrument i) {
        return (i.getYahooSymbol() != null && !i.getYahooSymbol().isBlank())
                || (i.getAmfiCode() != null && !i.getAmfiCode().isBlank());
    }
}
