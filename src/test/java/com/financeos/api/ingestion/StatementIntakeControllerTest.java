package com.financeos.api.ingestion;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.financeos.api.job.dto.EnqueueResponse;
import com.financeos.core.crypto.EncryptionService;
import com.financeos.core.exception.ValidationException;
import com.financeos.core.security.UserContext;
import com.financeos.domain.ingestion.StatementIntakeService;
import com.financeos.domain.ingestion.held.HeldUploadService;
import com.financeos.domain.job.Job;
import com.financeos.domain.job.JobService;
import com.financeos.domain.job.JobTrigger;
import com.financeos.domain.job.JobType;
import com.financeos.domain.job.handlers.StatementIngestPayload;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.http.ResponseEntity;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

class StatementIntakeControllerTest {

    private JobService jobService;
    private EncryptionService encryption;
    private StatementIntakeService intake;
    private HeldUploadService held;
    private StatementIntakeController controller;
    private final UUID userId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        jobService = mock(JobService.class);
        encryption = mock(EncryptionService.class);
        intake = mock(StatementIntakeService.class);
        held = mock(HeldUploadService.class);
        controller = new StatementIntakeController(jobService, encryption, intake, held, new ObjectMapper());
        UserContext.setCurrentUserId(userId);
    }

    @AfterEach
    void tearDown() {
        UserContext.clear();
    }

    private static MockMultipartFile pdf() {
        return new MockMultipartFile("files", "s.pdf", "application/pdf", "bytes".getBytes());
    }

    @Test
    void ingestWithoutAnAccountEnqueuesAnAutoDetectJobForTheCurrentUser() {
        Job job = new Job();
        job.setId(UUID.randomUUID());
        when(jobService.enqueue(any(), any(), any(), any(), anyList(), any())).thenReturn(job);

        ResponseEntity<EnqueueResponse> response = controller.ingestAuto(new org.springframework.web.multipart.MultipartFile[]{pdf()}, null);

        assertThat(response.getStatusCode().value()).isEqualTo(202);
        assertThat(response.getBody().jobId()).isEqualTo(job.getId());
        ArgumentCaptor<Object> payload = ArgumentCaptor.forClass(Object.class);
        verify(jobService).enqueue(eq(userId), eq(JobType.STATEMENT_INGEST), eq(JobTrigger.USER), payload.capture(), anyList(), any());
        StatementIngestPayload p = (StatementIngestPayload) payload.getValue();
        assertThat(p.accountId()).isNull();
        assertThat(p.encryptedPassword()).isNull();
        verifyNoInteractions(encryption);
    }

    @Test
    void ingestEncryptsATypedPasswordAndNeverPutsItInThePayloadInTheClear() {
        Job job = new Job();
        job.setId(UUID.randomUUID());
        when(jobService.enqueue(any(), any(), any(), any(), anyList(), any())).thenReturn(job);
        when(encryption.encrypt("pw")).thenReturn("ciphertext");

        controller.ingestAuto(new org.springframework.web.multipart.MultipartFile[]{pdf()}, "pw");

        ArgumentCaptor<Object> payload = ArgumentCaptor.forClass(Object.class);
        verify(jobService).enqueue(any(), any(), any(), payload.capture(), anyList(), any());
        assertThat(((StatementIngestPayload) payload.getValue()).encryptedPassword()).isEqualTo("ciphertext");
    }

    @Test
    void ingestWithNoFilesIsRejected() {
        assertThatThrownBy(() -> controller.ingestAuto(new org.springframework.web.multipart.MultipartFile[0], null))
                .isInstanceOf(ValidationException.class);
        verifyNoInteractions(jobService);
    }

    @Test
    void everyActionRunsAsTheCurrentUser() {
        UUID id = UUID.randomUUID();
        UUID accountId = UUID.randomUUID();
        controller.discardHeld(id);
        verify(held).discard(userId, id);
        controller.listHeld();
        verify(held).list(userId);
        controller.assignHeld(id, new com.financeos.api.ingestion.dto.IngestDtos.AssignHeldUploadRequest(accountId, "pw"));
        verify(held).assign(userId, id, accountId, "pw");
        controller.moveStatement(accountId, id, new com.financeos.api.ingestion.dto.IngestDtos.MoveStatementRequest(id));
        verify(intake).moveStatement(userId, accountId, id, id);
    }
}
