package com.financeos.domain.job.handlers;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.financeos.domain.ingestion.FileIngestionResult;
import com.financeos.domain.ingestion.FileIngestionService;
import com.financeos.domain.job.JobArtifact;
import com.financeos.domain.job.JobExecutionContext;
import com.financeos.domain.job.JobService;
import com.financeos.domain.job.JobType;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

class StatementIngestJobHandlerTest {

    @Test
    void execute_mapsArtifactsToUploadedFilesAndInvokesIngestionService() throws Exception {
        FileIngestionService ingestionService = mock(FileIngestionService.class);
        JobService jobService = mock(JobService.class);
        ObjectMapper objectMapper = new ObjectMapper();
        StatementIngestJobHandler handler = new StatementIngestJobHandler(ingestionService);

        UUID jobId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        UUID accountId = UUID.randomUUID();

        StatementIngestPayload payload = new StatementIngestPayload(accountId);
        String payloadJson = objectMapper.writeValueAsString(payload);

        JobArtifact artifact = new JobArtifact();
        artifact.setJobId(jobId);
        artifact.setFilename("statement.pdf");
        artifact.setContentType("application/pdf");
        artifact.setData("PDF content".getBytes());
        artifact.setSizeBytes((long) "PDF content".getBytes().length);

        when(jobService.getArtifacts(jobId)).thenReturn(List.of(artifact));
        FileIngestionResult expectedResult = new FileIngestionResult(1, 10, 0, List.of());
        when(ingestionService.ingest(eq(accountId), anyList(), any(JobExecutionContext.class))).thenReturn(expectedResult);

        JobExecutionContext ctx = new JobExecutionContext(jobId, userId, payloadJson, jobService, objectMapper);
        Object result = handler.execute(ctx);

        assertThat(result).isEqualTo(expectedResult);
        assertThat(handler.type()).isEqualTo(JobType.STATEMENT_INGEST);

        verify(ingestionService, times(1)).ingest(eq(accountId), anyList(), any(JobExecutionContext.class));
    }
}
