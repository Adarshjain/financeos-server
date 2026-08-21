package com.financeos.domain.job.handlers;

import com.financeos.domain.instrument.price.AmfiFeedClient;
import com.financeos.domain.instrument.price.PriceRefreshService;
import com.financeos.domain.job.JobExecutionContext;
import com.financeos.domain.job.JobHandler;
import com.financeos.domain.job.JobType;
import org.springframework.stereotype.Component;

import java.util.Optional;

@Component
public class PriceRefreshJobHandler implements JobHandler {

    private final PriceRefreshService priceRefreshService;
    private final AmfiFeedClient amfiFeedClient;

    public PriceRefreshJobHandler(PriceRefreshService priceRefreshService, AmfiFeedClient amfiFeedClient) {
        this.priceRefreshService = priceRefreshService;
        this.amfiFeedClient = amfiFeedClient;
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
        return priceRefreshService.refresh(Optional.ofNullable(payload != null ? payload.instrumentId() : null));
    }
}
