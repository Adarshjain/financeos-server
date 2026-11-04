package com.financeos.domain.job;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;

import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class JobServiceRetentionTest {

    private final JobRepository jobRepository = mock(JobRepository.class);
    private final JobArtifactRepository artifactRepository = mock(JobArtifactRepository.class);
    private final JobService service = new JobService(jobRepository, artifactRepository, new ObjectMapper(), null,
            mock(ApplicationEventPublisher.class));

    @Test
    void succeedDropsOnlyTheFilesThatAreNotRetained() {
        UUID jobId = UUID.randomUUID();
        Job job = new Job();
        job.setId(jobId);
        when(jobRepository.findById(jobId)).thenReturn(Optional.of(job));

        service.succeed(jobId, "{}");

        verify(artifactRepository).deleteByJobIdAndRetainedFalse(jobId);
        verify(artifactRepository, never()).deleteByJobId(jobId);
    }

    @Test
    void retainMarksOnlyTheNamedArtifacts() {
        UUID jobId = UUID.randomUUID();
        JobArtifact keep = new JobArtifact();
        keep.setId(UUID.randomUUID());
        JobArtifact drop = new JobArtifact();
        drop.setId(UUID.randomUUID());
        when(artifactRepository.findByJobId(jobId)).thenReturn(List.of(keep, drop));

        service.retainArtifacts(jobId, Set.of(keep.getId()));

        assertThat(keep.isRetained()).isTrue();
        assertThat(drop.isRetained()).isFalse();
        verify(artifactRepository).save(keep);
        verify(artifactRepository, never()).save(drop);
    }

    @Test
    void enqueueNumbersTheFilesAndHashesThem() {
        when(jobRepository.save(any())).thenAnswer(inv -> {
            Job j = inv.getArgument(0);
            j.setId(UUID.randomUUID());
            return j;
        });

        service.enqueue(UUID.randomUUID(), JobType.STATEMENT_INGEST, JobTrigger.USER, null,
                List.of(new StagedFile("a.pdf", "application/pdf", "a".getBytes()), new StagedFile("b.pdf", "application/pdf", "b".getBytes())), null);

        org.mockito.ArgumentCaptor<JobArtifact> saved = org.mockito.ArgumentCaptor.forClass(JobArtifact.class);
        verify(artifactRepository, times(2)).save(saved.capture());
        assertThat(saved.getAllValues()).extracting(JobArtifact::getFileIndex).containsExactly(0, 1);
        assertThat(saved.getAllValues().get(0).getFileSha256()).hasSize(64);
        assertThat(saved.getAllValues().get(0).getFileSha256()).isNotEqualTo(saved.getAllValues().get(1).getFileSha256());
    }

    @Test
    void getArtifactsComesBackInUploadOrder() {
        UUID jobId = UUID.randomUUID();
        JobArtifact second = new JobArtifact();
        second.setFileIndex(1);
        JobArtifact first = new JobArtifact();
        first.setFileIndex(0);
        when(artifactRepository.findByJobId(jobId)).thenReturn(List.of(second, first));

        assertThat(service.getArtifacts(jobId)).containsExactly(first, second);
    }

    @Test
    void succeedKeepsRetainedFilesSoTheyOutliveTheJobUntilItIsPurged() {
        UUID jobId = UUID.randomUUID();
        Job job = new Job();
        job.setId(jobId);
        when(jobRepository.findById(jobId)).thenReturn(Optional.of(job));
        JobArtifact kept = new JobArtifact();
        kept.setId(UUID.randomUUID());
        when(artifactRepository.findByJobId(jobId)).thenReturn(List.of(kept));

        service.retainArtifacts(jobId, Set.of(kept.getId()));
        service.succeed(jobId, "{}");

        assertThat(kept.isRetained()).isTrue();
        verify(artifactRepository, never()).delete(any(JobArtifact.class));
        verify(artifactRepository, never()).deleteByJobId(jobId); // only the 30-day job purge (FK cascade) removes it
    }

    @Test
    void retryDoesNotCopyFilesThatAlreadyWaitInTheInbox() {
        UUID jobId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        Job orig = new Job();
        orig.setId(jobId);
        orig.setUserId(userId);
        orig.setType(JobType.STATEMENT_INGEST);
        orig.setStatus(JobStatus.FAILED);
        JobArtifact held = new JobArtifact();
        held.setId(UUID.randomUUID());
        held.setFilename("held.pdf");
        JobArtifact other = new JobArtifact();
        other.setId(UUID.randomUUID());
        other.setFilename("other.pdf");
        when(jobRepository.findByIdAndUserId(jobId, userId)).thenReturn(Optional.of(orig));
        when(artifactRepository.findByJobId(jobId)).thenReturn(List.of(held, other));
        when(artifactRepository.hasUnresolvedHeldUpload(held.getId())).thenReturn(true);
        when(jobRepository.save(any())).thenAnswer(inv -> {
            Job j = inv.getArgument(0);
            j.setId(UUID.randomUUID());
            return j;
        });

        service.retry(userId, jobId);

        org.mockito.ArgumentCaptor<JobArtifact> saved = org.mockito.ArgumentCaptor.forClass(JobArtifact.class);
        verify(artifactRepository, times(1)).save(saved.capture());
        assertThat(saved.getValue().getFilename()).isEqualTo("other.pdf");
    }

    @Test
    void retryOfAJobWhoseFilesAllWaitInTheInboxIsRejected() {
        UUID jobId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        Job orig = new Job();
        orig.setId(jobId);
        orig.setUserId(userId);
        orig.setType(JobType.STATEMENT_INGEST);
        orig.setStatus(JobStatus.FAILED);
        JobArtifact held = new JobArtifact();
        held.setId(UUID.randomUUID());
        when(jobRepository.findByIdAndUserId(jobId, userId)).thenReturn(Optional.of(orig));
        when(artifactRepository.findByJobId(jobId)).thenReturn(List.of(held));
        when(artifactRepository.hasUnresolvedHeldUpload(held.getId())).thenReturn(true);

        org.assertj.core.api.Assertions.assertThatThrownBy(() -> service.retry(userId, jobId))
                .isInstanceOf(com.financeos.core.exception.ValidationException.class);
        verify(jobRepository, never()).save(any());
    }
}
