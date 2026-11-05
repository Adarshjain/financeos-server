package com.financeos.domain.nps;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/** Fills an empty scheme catalog right after startup (a fresh deploy), so pickers work before the first 23:45 run. */
@Component
public class NpsCatalogBootstrap {

    private static final Logger log = LoggerFactory.getLogger(NpsCatalogBootstrap.class);

    private final NpsCatalogService catalogService;
    private final boolean enabled;

    public NpsCatalogBootstrap(NpsCatalogService catalogService,
                               @Value("${price.enabled:true}") boolean priceEnabled,
                               @Value("${price.providers.nps.enabled:true}") boolean npsEnabled) {
        this.catalogService = catalogService;
        this.enabled = priceEnabled && npsEnabled;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void onReady() {
        if (!enabled) {
            return;
        }
        Thread thread = new Thread(() -> {
            try {
                if (catalogService.isCatalogEmpty()) {
                    catalogService.sync();
                }
            } catch (Exception e) {
                log.error("NPS catalog bootstrap failed", e);
            }
        }, "nps-catalog-bootstrap");
        thread.setDaemon(true);
        thread.start();
    }
}
