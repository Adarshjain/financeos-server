package com.financeos.domain.nps;

import com.financeos.domain.instrument.InstrumentPriceRepository;
import com.financeos.domain.instrument.price.PriceRefreshEvent;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Asks for NAV history of NPS schemes that have none back to the date a holder needs it from. A scheme is
 * covered when the ownerless feed has a NAV on or before that date; the others go out as one
 * {@link PriceRefreshEvent}, which the price history listener turns into a backfill.
 */
@Component
public class NpsHistoryBackfill {

    private final NpsSchemeRepository schemeRepository;
    private final InstrumentPriceRepository priceRepository;
    private final ApplicationEventPublisher eventPublisher;

    public NpsHistoryBackfill(NpsSchemeRepository schemeRepository, InstrumentPriceRepository priceRepository,
                              ApplicationEventPublisher eventPublisher) {
        this.schemeRepository = schemeRepository;
        this.priceRepository = priceRepository;
        this.eventPublisher = eventPublisher;
    }

    /**
     * Publishes one event for the schemes (code to the date it must be covered from) with no feed NAV on or
     * before that date; returns how many it asked for. Note: a Direct scheme whose official history starts after
     * its earliest entry stays "not covered", so it costs one history fetch a day. Acceptable.
     */
    public int requestFor(Map<String, LocalDate> coverFrom) {
        if (coverFrom == null || coverFrom.isEmpty()) {
            return 0;
        }
        Set<UUID> needing = new HashSet<>();
        for (NpsScheme scheme : schemeRepository.findAllById(coverFrom.keySet())) {
            LocalDate from = coverFrom.get(scheme.getSchemeCode());
            if (from == null) {
                continue;
            }
            boolean covered = priceRepository
                    .findFirstByInstrumentIdAndUserIdIsNullAndAsOfLessThanEqualOrderByAsOfDesc(
                            scheme.getInstrumentId(), from).isPresent();
            if (!covered) {
                needing.add(scheme.getInstrumentId());
            }
        }
        if (!needing.isEmpty()) {
            eventPublisher.publishEvent(new PriceRefreshEvent(needing));
        }
        return needing.size();
    }
}
