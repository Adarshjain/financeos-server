package com.financeos.domain.job.handlers;

import com.financeos.domain.instrument.price.AmfiFeedClient;
import com.financeos.domain.instrument.price.PriceCoverageMonitor;
import com.financeos.domain.instrument.price.PriceRefreshService;
import com.financeos.domain.job.JobExecutionContext;
import com.financeos.domain.job.JobHandler;
import com.financeos.domain.job.JobType;
import org.springframework.stereotype.Component;

import java.util.Optional;
import java.util.UUID;

@Component
public class PriceRefreshJobHandler implements JobHandler {

    private final PriceRefreshService priceRefreshService;
    private final AmfiFeedClient amfiFeedClient;

    /** Null in plain construction (older tests): no coverage check after the refresh. */
    private final PriceCoverageMonitor coverageMonitor;

    public PriceRefreshJobHandler(PriceRefreshService priceRefreshService, AmfiFeedClient amfiFeedClient) {
        this(priceRefreshService, amfiFeedClient, null);
    }

    @org.springframework.beans.factory.annotation.Autowired
    public PriceRefreshJobHandler(PriceRefreshService priceRefreshService, AmfiFeedClient amfiFeedClient,
                                  PriceCoverageMonitor coverageMonitor) {
        this.priceRefreshService = priceRefreshService;
        this.amfiFeedClient = amfiFeedClient;
        this.coverageMonitor = coverageMonitor;
    }

    @Override
    public JobType type() {
        return JobType.PRICE_REFRESH;
    }

    @Override
    public Object execute(JobExecutionContext ctx) throws Exception {
        ctx.checkCancelled();
        // AmfiFeedClient.ensureLoaded() has no TTL — this explicit load() is what refreshes
        // the NAV cache each run (the old cron did the same before calling refresh()).
        amfiFeedClient.load();
        PriceRefreshPayload payload = ctx.payload(PriceRefreshPayload.class);
        UUID instrumentId = payload != null ? payload.instrumentId() : null;
        var result = priceRefreshService.refresh(Optional.ofNullable(instrumentId));
        if (instrumentId == null && coverageMonitor != null) {
            coverageMonitor.check();
        }
        return result;
    }
}
