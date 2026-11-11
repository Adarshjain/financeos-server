package com.financeos.domain.job.handlers;

import com.financeos.core.security.UserContext;
import com.financeos.domain.holding.HoldingRepository;
import com.financeos.domain.instrument.corporateaction.detect.CorporateActionDetectionService;
import com.financeos.domain.instrument.corporateaction.detect.CorporateActionsProperties;
import com.financeos.domain.instrument.corporateaction.detect.MarketEventStore;
import com.financeos.domain.job.JobExecutionContext;
import com.financeos.domain.job.JobHandler;
import com.financeos.domain.job.JobType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.UUID;

/**
 * CORPORATE_ACTION_DETECT: with a user, checks that user's stock holdings for splits and bonuses; without one (the
 * nightly run) refreshes the exchange events first and then checks every user holding stocks, one failure never
 * stopping the rest. Always run as a cron-triggered job, so it never notifies the user.
 */
@Component
public class CorporateActionDetectJobHandler implements JobHandler {

    private static final Logger log = LoggerFactory.getLogger(CorporateActionDetectJobHandler.class);

    /** What a run did, stored as the job result. */
    public record Result(int users, int failed, int applied, int pending) {
    }

    private final CorporateActionDetectionService detection;
    private final MarketEventStore eventStore;
    private final HoldingRepository holdings;
    private final CorporateActionsProperties properties;

    public CorporateActionDetectJobHandler(CorporateActionDetectionService detection, MarketEventStore eventStore,
                                           HoldingRepository holdings, CorporateActionsProperties properties) {
        this.detection = detection;
        this.eventStore = eventStore;
        this.holdings = holdings;
        this.properties = properties;
    }

    @Override
    public JobType type() {
        return JobType.CORPORATE_ACTION_DETECT;
    }

    @Override
    public Object execute(JobExecutionContext ctx) {
        if (!properties.isEnabled()) {
            return new Result(0, 0, 0, 0);
        }
        CorporateActionDetectPayload payload = ctx.payload(CorporateActionDetectPayload.class);
        UUID userId = payload != null && payload.userId() != null ? payload.userId() : ctx.getUserId();
        if (userId != null) {
            var summary = detection.detectForUser(userId);
            return new Result(1, 0, summary.applied(), summary.pending());
        }
        try {
            if (!eventStore.refreshRecent()) {
                log.warn("Corporate event refresh failed; checking users against the events already stored");
            }
        } catch (Exception e) {
            log.warn("Corporate event refresh threw: {}; checking users against the events already stored", e.toString());
        }
        List<UUID> userIds = holdings.findUserIdsHoldingStocks();
        int failed = 0;
        int applied = 0;
        int pending = 0;
        UUID previous = UserContext.getCurrentUserId();
        try {
            for (UUID id : userIds) {
                ctx.checkCancelled();
                try {
                    UserContext.setCurrentUserId(id);
                    var summary = detection.detectForUser(id);
                    applied += summary.applied();
                    pending += summary.pending();
                } catch (Exception e) {
                    failed++;
                    log.warn("Corporate action detection failed for user {}: {}", id, e.toString());
                }
            }
        } finally {
            if (previous == null) {
                UserContext.clear();
            } else {
                UserContext.setCurrentUserId(previous);
            }
        }
        return new Result(userIds.size(), failed, applied, pending);
    }
}
