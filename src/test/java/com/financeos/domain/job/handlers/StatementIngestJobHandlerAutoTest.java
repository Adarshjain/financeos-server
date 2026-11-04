package com.financeos.domain.job.handlers;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.financeos.core.crypto.EncryptionService;
import com.financeos.domain.ingestion.AutoIngestService;
import com.financeos.domain.ingestion.FileIngestionResult;
import com.financeos.domain.ingestion.FileIngestionService;
import com.financeos.domain.job.JobArtifact;
import com.financeos.domain.job.JobExecutionContext;
import com.financeos.domain.job.JobService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

class StatementIngestJobHandlerAutoTest {

    private FileIngestionService ingestion;
    private AutoIngestService auto;
    private EncryptionService encryption;
    private JobService jobService;
    private StatementIngestJobHandler handler;
    private final ObjectMapper mapper = new ObjectMapper();
    private final UUID jobId = UUID.randomUUID();
    private final UUID userId = UUID.randomUUID();
    private JobArtifact artifact;

    @BeforeEach
    void setUp() {
        ingestion = mock(FileIngestionService.class);
        auto = mock(AutoIngestService.class);
        encryption = mock(EncryptionService.class);
        jobService = mock(JobService.class);
        handler = new StatementIngestJobHandler(ingestion);
        handler.setAutoIngestService(auto);
        handler.setEncryptionService(encryption);
        handler.setJobService(jobService);
        artifact = new JobArtifact();
        artifact.setId(UUID.randomUUID());
        artifact.setJobId(jobId);
        artifact.setFilename("s.pdf");
        artifact.setData("x".getBytes());
        when(jobService.getArtifacts(jobId)).thenReturn(List.of(artifact));
    }

    private JobExecutionContext ctx(StatementIngestPayload payload) throws Exception {
        return new JobExecutionContext(jobId, userId, mapper.writeValueAsString(payload), jobService, mapper);
    }

    @Test
    void aPayloadWithoutAnAccountRunsTheAutoDetect() throws Exception {
        FileIngestionResult expected = new FileIngestionResult(0, 0, 0, List.of());
        when(auto.run(any(), anyList(), eq((String) null))).thenReturn(expected);

        Object result = handler.execute(ctx(new StatementIngestPayload(null, null)));

        assertThat(result).isSameAs(expected);
        verifyNoInteractions(ingestion);
    }

    @Test
    void theTypedPasswordIsDecryptedAndUsedForTheAccountIngest() throws Exception {
        UUID accountId = UUID.randomUUID();
        when(encryption.decrypt("enc")).thenReturn("pw");
        FileIngestionResult expected = new FileIngestionResult(1, 0, 0, List.of());
        when(ingestion.ingest(eq(accountId), anyList(), any(JobExecutionContext.class), eq("pw"))).thenReturn(expected);

        Object result = handler.execute(ctx(new StatementIngestPayload(accountId, "enc")));

        assertThat(result).isSameAs(expected);
    }

    @Test
    void filesThatStillNeedAPasswordAreRetainedAndOthersAreNot() throws Exception {
        UUID accountId = UUID.randomUUID();
        JobArtifact ok = new JobArtifact();
        ok.setId(UUID.randomUUID());
        ok.setFilename("ok.pdf");
        ok.setData("y".getBytes());
        when(jobService.getArtifacts(jobId)).thenReturn(List.of(artifact, ok));
        FileIngestionResult result = new FileIngestionResult(1, 3, 0, List.of(
                FileIngestionResult.FileSummary.failed("s.pdf", "locked", FileIngestionResult.ErrorCode.PASSWORD_REQUIRED),
                new FileIngestionResult.FileSummary("ok.pdf", "SUCCESS", 3, null, null, 3, 0)));
        when(ingestion.ingest(eq(accountId), anyList(), any(JobExecutionContext.class))).thenReturn(result);

        handler.execute(ctx(new StatementIngestPayload(accountId)));

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Collection<UUID>> kept = ArgumentCaptor.forClass(Collection.class);
        verify(jobService).retainArtifacts(eq(jobId), kept.capture());
        assertThat(kept.getValue()).containsExactly(artifact.getId());
    }
}
