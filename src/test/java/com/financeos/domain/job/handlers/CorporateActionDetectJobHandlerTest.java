package com.financeos.domain.job.handlers;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.financeos.domain.holding.HoldingRepository;
import com.financeos.domain.instrument.corporateaction.detect.CorporateActionDetectionService;
import com.financeos.domain.instrument.corporateaction.detect.CorporateActionDetectionService.Summary;
import com.financeos.domain.instrument.corporateaction.detect.CorporateActionsProperties;
import com.financeos.domain.instrument.corporateaction.detect.MarketEventStore;
import com.financeos.domain.job.JobExecutionContext;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class CorporateActionDetectJobHandlerTest {

    private final CorporateActionDetectionService detection = mock(CorporateActionDetectionService.class);
    private final MarketEventStore store = mock(MarketEventStore.class);
    private final HoldingRepository holdings = mock(HoldingRepository.class);
    private final CorporateActionsProperties props = new CorporateActionsProperties();
    private final CorporateActionDetectJobHandler handler = new CorporateActionDetectJobHandler(detection, store, holdings, props);
    private final JobExecutionContext ctx = mock(JobExecutionContext.class);

    @Test
    void withAUserChecksOnlyThatUser() {
        UUID user = UUID.randomUUID();
        when(ctx.payload(CorporateActionDetectPayload.class)).thenReturn(new CorporateActionDetectPayload(user));
        when(detection.detectForUser(user)).thenReturn(new Summary(1, 2, 0));

        Object out = handler.execute(ctx);

        assertThat(out).isEqualTo(new CorporateActionDetectJobHandler.Result(1, 0, 1, 2));
        verify(store, never()).refreshRecent();
        verify(holdings, never()).findUserIdsHoldingStocks();
    }

    @Test
    void nightlyRefreshesEventsThenChecksEveryHolderSurvivingAFailure() {
        UUID a = UUID.randomUUID();
        UUID b = UUID.randomUUID();
        when(ctx.payload(CorporateActionDetectPayload.class)).thenReturn(new CorporateActionDetectPayload(null));
        when(holdings.findUserIdsHoldingStocks()).thenReturn(List.of(a, b));
        when(detection.detectForUser(a)).thenThrow(new IllegalStateException("boom"));
        when(detection.detectForUser(b)).thenReturn(new Summary(1, 0, 0));

        Object out = handler.execute(ctx);

        assertThat(out).isEqualTo(new CorporateActionDetectJobHandler.Result(2, 1, 1, 0));
        verify(store).refreshRecent();
        verify(detection).detectForUser(b);
    }

    @Test
    void disabledDoesNothing() {
        props.setEnabled(false);
        handler.execute(ctx);
        verify(detection, never()).detectForUser(any());
        verify(store, never()).refreshRecent();
    }

    @Test
    void nightlyStillChecksUsersWhenTheEventRefreshThrows() {
        UUID a = UUID.randomUUID();
        when(ctx.payload(CorporateActionDetectPayload.class)).thenReturn(new CorporateActionDetectPayload(null));
        when(holdings.findUserIdsHoldingStocks()).thenReturn(List.of(a));
        when(store.refreshRecent()).thenThrow(new IllegalStateException("db down"));
        when(detection.detectForUser(a)).thenReturn(new Summary(1, 0, 0));

        Object out = handler.execute(ctx);

        assertThat(out).isEqualTo(new CorporateActionDetectJobHandler.Result(1, 0, 1, 0));
    }
}
