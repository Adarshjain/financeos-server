package com.financeos.domain.job.handlers;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.financeos.domain.holding.HoldingRepository;
import com.financeos.domain.instrument.corporateaction.detect.CorporateActionDetectionService;
import com.financeos.domain.instrument.corporateaction.detect.CorporateActionsProperties;
import com.financeos.domain.instrument.corporateaction.detect.MarketEventStore;
import com.financeos.domain.instrument.corporateaction.detect.SchemeAutoApply;
import com.financeos.domain.instrument.corporateaction.detect.SchemeEvidenceEnrichment;
import com.financeos.domain.job.JobExecutionContext;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** Verified demergers and mergers are applied after the notices are read (nightly, every holder) or for one user after an import. */
class CorporateActionDetectJobHandlerAutoApplyTest {

    private final CorporateActionDetectionService detection = mock(CorporateActionDetectionService.class);
    private final HoldingRepository holdings = mock(HoldingRepository.class);
    private final SchemeEvidenceEnrichment enrichment = mock(SchemeEvidenceEnrichment.class);
    private final SchemeAutoApply autoApply = mock(SchemeAutoApply.class);
    private final CorporateActionDetectJobHandler handler = new CorporateActionDetectJobHandler(detection, mock(MarketEventStore.class),
            holdings, new CorporateActionsProperties(), enrichment, autoApply);
    private final JobExecutionContext ctx = mock(JobExecutionContext.class);

    @Test
    void nightlyAppliesForEveryHolderAfterTheNoticesAreRead() {
        UUID a = UUID.randomUUID();
        UUID b = UUID.randomUUID();
        when(ctx.payload(CorporateActionDetectPayload.class)).thenReturn(new CorporateActionDetectPayload(null));
        when(holdings.findUserIdsHoldingStocks()).thenReturn(List.of(a, b));
        when(detection.detectForUser(any())).thenReturn(new CorporateActionDetectionService.Summary(0, 0, 0));
        when(autoApply.runForUser(a)).thenThrow(new IllegalStateException("boom"));

        handler.execute(ctx);

        var order = inOrder(enrichment, autoApply);
        order.verify(enrichment).run(any());
        order.verify(autoApply).runForUser(a);
        order.verify(autoApply).runForUser(b); // one user failing does not stop the rest
    }

    @Test
    void anImportRunReadsThatUsersOwnNoticesThenAppliesForThemOnly() {
        UUID user = UUID.randomUUID();
        when(ctx.payload(CorporateActionDetectPayload.class)).thenReturn(new CorporateActionDetectPayload(user));
        when(detection.detectForUser(user)).thenReturn(new CorporateActionDetectionService.Summary(0, 0, 0));

        handler.execute(ctx);

        var order = inOrder(enrichment, autoApply);
        order.verify(enrichment).runForUser(org.mockito.ArgumentMatchers.eq(user), any()); // its own pending events first
        order.verify(autoApply).runForUser(user);
        verify(enrichment, never()).run(any()); // the market-wide reading stays nightly
    }
}
