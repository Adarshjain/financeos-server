package com.financeos.domain.nps;

import com.financeos.domain.instrument.price.PriceHistoryBackfilledEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/** Re-prices NPS entries after a history backfill; calls {@link NpsRepriceService} through its proxy so the work is transactional. */
@Component
public class NpsRepriceListener {

    private static final Logger log = LoggerFactory.getLogger(NpsRepriceListener.class);

    private final NpsRepriceService repriceService;

    public NpsRepriceListener(NpsRepriceService repriceService) {
        this.repriceService = repriceService;
    }

    @EventListener
    public void onHistoryBackfilled(PriceHistoryBackfilledEvent event) {
        try {
            repriceService.repriceInstruments(event.instrumentIds());
        } catch (Exception e) {
            log.warn("Re-pricing NPS entries after a history backfill failed: {}", e.getMessage());
        }
    }
}
