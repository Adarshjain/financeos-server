package com.financeos.domain.ingestion.held;

import com.financeos.core.exception.ApiStatusException;
import com.financeos.core.exception.ResourceNotFoundException;
import com.financeos.domain.account.Account;
import com.financeos.domain.account.AccountRepository;
import com.financeos.domain.account.AccountType;
import com.financeos.domain.ingestion.FileIngestionResult;
import com.financeos.domain.ingestion.FileIngestionService;
import com.financeos.domain.ingestion.StatementIdentifier;
import com.financeos.domain.ingestion.StatementPasswordVault;
import com.financeos.domain.ingestion.UploadedFile;
import com.financeos.domain.job.JobArtifact;
import com.financeos.domain.job.JobArtifactRepository;
import com.financeos.domain.notification.feed.AppNotificationKind;
import com.financeos.domain.notification.feed.NotificationFeedService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.nullable;
import static org.mockito.Mockito.*;

class HeldUploadServiceTest {

    private HeldUploadRepository heldRepository;
    private JobArtifactRepository artifactRepository;
    private AccountRepository accountRepository;
    private FileIngestionService ingestion;
    private StatementPasswordVault vault;
    private StatementIdentifier identifier;
    private NotificationFeedService feed;
    private HeldUploadService service;

    private final UUID userId = UUID.randomUUID();
    private final UUID accountId = UUID.randomUUID();
    private JobArtifact artifact;
    private HeldUpload held;

    @BeforeEach
    void setUp() {
        heldRepository = mock(HeldUploadRepository.class);
        artifactRepository = mock(JobArtifactRepository.class);
        accountRepository = mock(AccountRepository.class);
        ingestion = mock(FileIngestionService.class);
        vault = mock(StatementPasswordVault.class);
        identifier = mock(StatementIdentifier.class);
        feed = mock(NotificationFeedService.class);
        service = new HeldUploadService(heldRepository, artifactRepository, accountRepository, ingestion, vault, identifier, feed);

        artifact = new JobArtifact();
        artifact.setId(UUID.randomUUID());
        artifact.setJobId(UUID.randomUUID());
        artifact.setFilename("s.pdf");
        artifact.setContentType("application/pdf");
        artifact.setData("bytes".getBytes());

        held = new HeldUpload();
        held.setId(UUID.randomUUID());
        held.setUserId(userId);
        held.setJobId(artifact.getJobId());
        held.setArtifactId(artifact.getId());
        held.setFileName("s.pdf");
        held.setReason(HeldUpload.Reason.NO_MATCH);

        when(heldRepository.save(any())).thenAnswer(inv -> {
            HeldUpload h = inv.getArgument(0);
            if (h.getId() == null) h.setId(UUID.randomUUID());
            return h;
        });
        when(heldRepository.findByIdAndUserId(held.getId(), userId)).thenReturn(Optional.of(held));
        when(artifactRepository.findById(artifact.getId())).thenReturn(Optional.of(artifact));
        Account account = new Account();
        account.setId(accountId);
        account.setType(AccountType.bank_account);
        when(accountRepository.findByIdAndUser_Id(accountId, userId)).thenReturn(Optional.of(account));
    }

    private static FileIngestionResult result(FileIngestionResult.FileSummary file) {
        return new FileIngestionResult(1, 0, 0, List.of(file));
    }

    @Test
    void holdKeepsTheFileAndRecordsOneFeedEntryDedupedPerHeldUpload() {
        HeldUpload saved = service.hold(userId, artifact, 2, "hdfc", "1234", HeldUpload.Reason.NO_MATCH, List.of());

        assertThat(artifact.isRetained()).isTrue();
        verify(artifactRepository).save(artifact);
        assertThat(saved.getFileIndex()).isEqualTo(2);
        assertThat(saved.getDetectedBankId()).isEqualTo("hdfc");
        verify(feed, times(1)).record(eq(userId), eq(AppNotificationKind.UPLOAD_HELD), eq("A statement needs an account"),
                any(), any(), eq("held-upload:" + saved.getId()));
    }

    @Test
    void holdStoresTheCandidateAccounts() {
        UUID one = UUID.randomUUID();
        UUID two = UUID.randomUUID();
        HeldUpload saved = service.hold(userId, artifact, 0, "hdfc", "1234", HeldUpload.Reason.AMBIGUOUS, List.of(one, two));
        assertThat(saved.candidateIds()).containsExactly(one, two);
    }

    @Test
    void listReturnsOnlyTheUsersUnresolvedFiles() {
        when(heldRepository.findByUserIdAndResolvedAtIsNullOrderByCreatedAtDesc(userId)).thenReturn(List.of(held));
        assertThat(service.list(userId)).containsExactly(held);
        verify(heldRepository, never()).findAll();
    }

    @Test
    void assignIngestsTheStoredFileAndResolvesIt() {
        when(ingestion.ingest(eq(accountId), anyList(), nullable(com.financeos.domain.job.JobExecutionContext.class), nullable(String.class)))
                .thenReturn(result(new FileIngestionResult.FileSummary("s.pdf", "SUCCESS", 3, null, null, 3, 0)));

        FileIngestionResult r = service.assign(userId, held.getId(), accountId, null);

        assertThat(r.fileDetails().get(0).status()).isEqualTo("SUCCESS");
        assertThat(held.getResolvedAt()).isNotNull();
        verify(artifactRepository).delete(artifact);
        verify(vault, never()).save(any(), any(), any());
    }

    @Test
    void assignSavesThePasswordWhenItOpenedALockedFile() {
        when(identifier.isLocked(artifact.getData())).thenReturn(true);
        when(ingestion.ingest(eq(accountId), anyList(), nullable(com.financeos.domain.job.JobExecutionContext.class), eq("pw")))
                .thenReturn(result(new FileIngestionResult.FileSummary("s.pdf", "SUCCESS", 3, null, null, 3, 0)));

        service.assign(userId, held.getId(), accountId, "pw");

        verify(vault).save(userId, accountId, "pw");
        assertThat(held.getResolvedAt()).isNotNull();
    }

    @Test
    void assignWithAWrongPasswordIs422AndKeepsTheFileHeld() {
        when(identifier.isLocked(artifact.getData())).thenReturn(true);
        when(ingestion.ingest(eq(accountId), anyList(), nullable(com.financeos.domain.job.JobExecutionContext.class), eq("bad")))
                .thenReturn(result(FileIngestionResult.FileSummary.failed("s.pdf", "locked",
                        FileIngestionResult.ErrorCode.WRONG_PASSWORD)));

        assertThatThrownBy(() -> service.assign(userId, held.getId(), accountId, "bad"))
                .isInstanceOfSatisfying(ApiStatusException.class, e -> {
                    assertThat(e.getStatus().value()).isEqualTo(422);
                    assertThat(e.getCode()).isEqualTo("WRONG_PASSWORD");
                });
        assertThat(held.getResolvedAt()).isNull();
        verify(vault, never()).save(any(), any(), any());
        verify(artifactRepository, never()).delete(any());
    }

    @Test
    void assignWhenTheStoredFileIsGoneIs409() {
        when(artifactRepository.findById(artifact.getId())).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.assign(userId, held.getId(), accountId, null))
                .isInstanceOfSatisfying(ApiStatusException.class, e -> {
                    assertThat(e.getStatus().value()).isEqualTo(409);
                    assertThat(e.getMessage()).isEqualTo("The file is no longer stored. Upload it again.");
                });
    }

    @Test
    void assignToSomeoneElsesAccountIs404() {
        UUID foreign = UUID.randomUUID();
        when(accountRepository.findByIdAndUser_Id(foreign, userId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.assign(userId, held.getId(), foreign, null)).isInstanceOf(ResourceNotFoundException.class);
        verifyNoInteractions(ingestion);
    }

    @Test
    void anotherUsersHeldUploadIs404ForAssignAndDiscard() {
        UUID other = UUID.randomUUID();
        when(heldRepository.findByIdAndUserId(held.getId(), other)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.assign(other, held.getId(), accountId, null)).isInstanceOf(ResourceNotFoundException.class);
        assertThatThrownBy(() -> service.discard(other, held.getId())).isInstanceOf(ResourceNotFoundException.class);
        verifyNoInteractions(ingestion);
        verify(artifactRepository, never()).delete(any());
    }

    @Test
    void discardResolvesTheHeldUploadAndDeletesTheStoredFile() {
        service.discard(userId, held.getId());

        assertThat(held.getResolvedAt()).isNotNull();
        verify(artifactRepository).delete(artifact);
    }

    @Test
    void anAlreadyResolvedHeldUploadIs404() {
        held.setResolvedAt(java.time.Instant.now());

        assertThatThrownBy(() -> service.discard(userId, held.getId())).isInstanceOf(ResourceNotFoundException.class);
    }
}
