package com.financeos.domain.job.handlers;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.financeos.domain.holding.HoldingRepository;
import com.financeos.domain.instrument.corporateaction.detect.CorporateActionDetectionService;
import com.financeos.domain.instrument.corporateaction.detect.CorporateActionsProperties;
import com.financeos.domain.instrument.corporateaction.detect.MarketEventStore;
import com.financeos.domain.instrument.corporateaction.detect.SchemeEvidenceEnrichment;
import com.financeos.domain.job.JobExecutionContext;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** The nightly run reads the company notices after the users; a per-user run does not. */
class CorporateActionDetectJobHandlerSchemeTest {

    private final CorporateActionDetectionService detection = mock(CorporateActionDetectionService.class);
    private final MarketEventStore store = mock(MarketEventStore.class);
    private final HoldingRepository holdings = mock(HoldingRepository.class);
    private final SchemeEvidenceEnrichment enrichment = mock(SchemeEvidenceEnrichment.class);
    private final CorporateActionDetectJobHandler handler =
            new CorporateActionDetectJobHandler(detection, store, holdings, new CorporateActionsProperties(), enrichment);
    private final JobExecutionContext ctx = mock(JobExecutionContext.class);

    @Test
    void nightlyReadsTheNoticesAfterTheUsersAndAFailureDoesNotFailTheRun() {
        when(ctx.payload(CorporateActionDetectPayload.class)).thenReturn(new CorporateActionDetectPayload(null));
        when(holdings.findUserIdsHoldingStocks()).thenReturn(List.of(UUID.randomUUID()));
        when(detection.detectForUser(any())).thenReturn(new CorporateActionDetectionService.Summary(0, 1, 0));
        when(enrichment.run(any())).thenThrow(new IllegalStateException("boom"));

        Object out = handler.execute(ctx);

        assertThat(out).isEqualTo(new CorporateActionDetectJobHandler.Result(1, 0, 0, 1));
        verify(enrichment).run(any());
    }

    @Test
    void aPerUserRunDoesNotReadNotices() {
        UUID user = UUID.randomUUID();
        when(ctx.payload(CorporateActionDetectPayload.class)).thenReturn(new CorporateActionDetectPayload(user));
        when(detection.detectForUser(user)).thenReturn(new CorporateActionDetectionService.Summary(0, 0, 0));

        handler.execute(ctx);

        verify(enrichment, never()).run(any());
    }
}
