package com.financeos.domain.job.handlers;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.financeos.domain.instrument.price.AmfiFeedClient;
import com.financeos.domain.instrument.price.PriceRefreshService;
import com.financeos.domain.job.JobExecutionContext;
import com.financeos.domain.job.JobService;
import com.financeos.domain.job.JobType;
import org.junit.jupiter.api.Test;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class PriceRefreshJobHandlerTest {

    @Test
    void execute_passesInstrumentIdToPriceRefreshService() throws Exception {
        PriceRefreshService priceRefreshService = mock(PriceRefreshService.class);
        AmfiFeedClient amfiFeedClient = mock(AmfiFeedClient.class);
        JobService jobService = mock(JobService.class);
        ObjectMapper objectMapper = new ObjectMapper();

        PriceRefreshJobHandler handler = new PriceRefreshJobHandler(priceRefreshService, amfiFeedClient);

        UUID jobId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        UUID instrumentId = UUID.randomUUID();

        PriceRefreshPayload payload = new PriceRefreshPayload(instrumentId);
        String payloadJson = objectMapper.writeValueAsString(payload);

        JobExecutionContext ctx = new JobExecutionContext(jobId, userId, payloadJson, jobService, objectMapper);

        handler.execute(ctx);

        assertThat(handler.type()).isEqualTo(JobType.PRICE_REFRESH);
        verify(amfiFeedClient, times(1)).load();
        verify(priceRefreshService, times(1)).refresh(Optional.of(instrumentId));
    }
}
