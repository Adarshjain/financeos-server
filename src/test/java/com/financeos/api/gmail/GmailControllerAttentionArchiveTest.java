package com.financeos.api.gmail;

import com.financeos.api.gmail.dto.GmailAttentionItemResponse;
import com.financeos.core.exception.ResourceNotFoundException;
import com.financeos.core.exception.ValidationException;
import com.financeos.domain.account.Account;
import com.financeos.domain.account.AccountIdentifierService;
import com.financeos.domain.account.AccountRepository;
import com.financeos.domain.job.Job;
import com.financeos.domain.job.JobService;
import com.financeos.domain.transaction.TransactionRepository;
import com.financeos.domain.user.AuthService;
import com.financeos.domain.user.User;
import com.financeos.gmail.domain.*;
import com.financeos.gmail.ingest.GmailIngestProperties;
import com.financeos.gmail.ingest.GmailIngestionService;
import com.financeos.gmail.ingest.SenderAllowlistService;
import com.financeos.gmail.oauth.GmailOAuthService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.mockito.ArgumentCaptor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/** Archive / unarchive of Gmail "needs attention" items, the archived listing and the richer item fields. */
class GmailControllerAttentionArchiveTest {

    private AuthService authService;
    private GmailProcessedMessageRepository processedMessageRepository;
    private JobService jobService;
    private GmailController controller;
    private User currentUser;
    private GmailConnection connection;

    @BeforeEach
    void setUp() {
        authService = mock(AuthService.class);
        processedMessageRepository = mock(GmailProcessedMessageRepository.class);
        jobService = mock(JobService.class);
        controller = new GmailController(
                mock(GmailOAuthService.class),
                authService,
                mock(GmailIngestionService.class),
                mock(SenderAllowlistService.class),
                mock(GmailConnectionRepository.class),
                mock(GmailSyncCursorRepository.class),
                processedMessageRepository,
                mock(GmailBackfillDemandRepository.class),
                mock(AccountRepository.class),
                mock(TransactionRepository.class),
                jobService,
                mock(GmailIngestProperties.class),
                mock(AccountIdentifierService.class));

        currentUser = new User();
        currentUser.setId(UUID.randomUUID());
        when(authService.getCurrentUser()).thenReturn(currentUser);

        connection = new GmailConnection();
        connection.setId(UUID.randomUUID());
        connection.setUser(currentUser);
        connection.setEmail("me@gmail.com");
    }

    // ------------------------------------------------------------------ archive

    @Test
    void archive_stampsArchivedAt() {
        GmailProcessedMessage gpm = item(currentUser, GmailProcessedStatus.FAILED_PERMANENT, null);

        ResponseEntity<Void> response = controller.archiveAttentionItem(gpm.getId());

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        assertThat(gpm.getArchivedAt()).isNotNull();
        verify(processedMessageRepository).save(gpm);
    }

    @ParameterizedTest
    @EnumSource(value = GmailProcessedStatus.class, names = {"UNRESOLVED_ACCOUNT", "ACCOUNT_NOT_OPTED_IN", "FAILED_PERMANENT"})
    void archive_acceptsEveryAttentionStatus(GmailProcessedStatus status) {
        GmailProcessedMessage gpm = item(currentUser, status, null);

        controller.archiveAttentionItem(gpm.getId());

        assertThat(gpm.getArchivedAt()).isNotNull();
    }

    @Test
    void archive_alreadyArchived_keepsOriginalTimestampAndDoesNotSave() {
        Instant original = Instant.parse("2026-10-01T00:00:00Z");
        GmailProcessedMessage gpm = item(currentUser, GmailProcessedStatus.FAILED_PERMANENT, original);

        ResponseEntity<Void> response = controller.archiveAttentionItem(gpm.getId());

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        assertThat(gpm.getArchivedAt()).isEqualTo(original);
        verify(processedMessageRepository, never()).save(any());
    }

    @ParameterizedTest
    @EnumSource(value = GmailProcessedStatus.class, mode = EnumSource.Mode.EXCLUDE,
            names = {"UNRESOLVED_ACCOUNT", "ACCOUNT_NOT_OPTED_IN", "FAILED_PERMANENT"})
    void archive_rejectsNonAttentionStatus(GmailProcessedStatus status) {
        GmailProcessedMessage gpm = item(currentUser, status, null);

        assertThatThrownBy(() -> controller.archiveAttentionItem(gpm.getId()))
                .isInstanceOf(ValidationException.class);
        assertThat(gpm.getArchivedAt()).isNull();
        verify(processedMessageRepository, never()).save(any());
    }

    @Test
    void archive_anotherUsersItem_isNotFound() {
        User other = new User();
        other.setId(UUID.randomUUID());
        GmailProcessedMessage gpm = item(other, GmailProcessedStatus.FAILED_PERMANENT, null);

        assertThatThrownBy(() -> controller.archiveAttentionItem(gpm.getId()))
                .isInstanceOf(ResourceNotFoundException.class);
        assertThat(gpm.getArchivedAt()).isNull();
        verify(processedMessageRepository, never()).save(any());
    }

    @Test
    void archive_unknownItem_isNotFound() {
        UUID id = UUID.randomUUID();
        when(processedMessageRepository.findById(id)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> controller.archiveAttentionItem(id))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    // ------------------------------------------------------------------ unarchive

    @Test
    void unarchive_clearsArchivedAt() {
        GmailProcessedMessage gpm = item(currentUser, GmailProcessedStatus.UNRESOLVED_ACCOUNT, Instant.now());

        ResponseEntity<Void> response = controller.unarchiveAttentionItem(gpm.getId());

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        assertThat(gpm.getArchivedAt()).isNull();
        verify(processedMessageRepository).save(gpm);
    }

    @Test
    void unarchive_notArchived_isANoOp() {
        GmailProcessedMessage gpm = item(currentUser, GmailProcessedStatus.UNRESOLVED_ACCOUNT, null);

        ResponseEntity<Void> response = controller.unarchiveAttentionItem(gpm.getId());

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        verify(processedMessageRepository, never()).save(any());
    }

    @Test
    void unarchive_anotherUsersItem_isNotFound() {
        User other = new User();
        other.setId(UUID.randomUUID());
        GmailProcessedMessage gpm = item(other, GmailProcessedStatus.FAILED_PERMANENT, Instant.now());

        assertThatThrownBy(() -> controller.unarchiveAttentionItem(gpm.getId()))
                .isInstanceOf(ResourceNotFoundException.class);
        assertThat(gpm.getArchivedAt()).isNotNull();
    }

    @Test
    void unarchive_rejectsNonAttentionStatus() {
        GmailProcessedMessage gpm = item(currentUser, GmailProcessedStatus.CREATED, Instant.now());

        assertThatThrownBy(() -> controller.unarchiveAttentionItem(gpm.getId()))
                .isInstanceOf(ValidationException.class);
        verify(processedMessageRepository, never()).save(any());
    }

    // ------------------------------------------------------------------ retry un-archives

    @Test
    void retry_ofArchivedItem_clearsTheArchive() {
        GmailProcessedMessage gpm = item(currentUser, GmailProcessedStatus.FAILED_PERMANENT, Instant.now());
        Job job = new Job();
        job.setId(UUID.randomUUID());
        when(jobService.enqueue(any(), any(), any(), any(), any(), any())).thenReturn(job);

        controller.retryAttentionItem(gpm.getId());

        assertThat(gpm.getStatus()).isEqualTo(GmailProcessedStatus.DISCOVERED);
        assertThat(gpm.getArchivedAt()).isNull();
    }

    // ------------------------------------------------------------------ listing

    @Test
    void list_default_readsActiveItemsNewestDiscoveredFirst() {
        when(processedMessageRepository.findAttentionItems(eq(currentUser.getId()), anyCollection(), any()))
                .thenReturn(Page.empty());

        controller.getAttentionItems(0, 10, false, false);

        ArgumentCaptor<Pageable> pageable = ArgumentCaptor.forClass(Pageable.class);
        verify(processedMessageRepository).findAttentionItems(eq(currentUser.getId()), anyCollection(), pageable.capture());
        verify(processedMessageRepository, never()).findArchivedAttentionItems(any(), any(), any());
        assertThat(pageable.getValue().getSort()).isEqualTo(Sort.by(Sort.Direction.DESC, "discoveredAt"));
    }

    @Test
    void list_archived_readsArchivedItemsNewestArchivedFirst() {
        when(processedMessageRepository.findArchivedAttentionItems(eq(currentUser.getId()), anyCollection(), any()))
                .thenReturn(Page.empty());

        controller.getAttentionItems(1, 10, false, true);

        ArgumentCaptor<Pageable> pageable = ArgumentCaptor.forClass(Pageable.class);
        verify(processedMessageRepository).findArchivedAttentionItems(eq(currentUser.getId()), anyCollection(), pageable.capture());
        verify(processedMessageRepository, never()).findAttentionItems(any(), any(), any());
        assertThat(pageable.getValue().getPageNumber()).isEqualTo(1);
        assertThat(pageable.getValue().getSort()).isEqualTo(Sort.by(Sort.Direction.DESC, "archivedAt"));
    }

    @Test
    void list_mapsMailboxAccountAndArchiveFields() {
        Account account = new Account();
        account.setId(UUID.randomUUID());
        account.setName("HSBC Live+");
        Instant processedAt = Instant.parse("2026-10-02T10:00:00Z");
        Instant archivedAt = Instant.parse("2026-10-03T10:00:00Z");
        GmailProcessedMessage gpm = item(currentUser, GmailProcessedStatus.ACCOUNT_NOT_OPTED_IN, archivedAt);
        gpm.setProcessedAt(processedAt);
        when(processedMessageRepository.findArchivedAttentionItems(eq(currentUser.getId()), anyCollection(), any()))
                .thenReturn(new PageImpl<>(List.of(new GmailAttentionRow(gpm, "me@gmail.com", account.getId(), account.getName()))));

        GmailAttentionItemResponse row = controller.getAttentionItems(0, 10, false, true).getBody().getContent().get(0);

        assertThat(row.connectionEmail()).isEqualTo("me@gmail.com");
        assertThat(row.accountId()).isEqualTo(account.getId());
        assertThat(row.accountName()).isEqualTo("HSBC Live+");
        assertThat(row.processedAt()).isEqualTo(processedAt);
        assertThat(row.archivedAt()).isEqualTo(archivedAt);
    }

    @Test
    void list_itemWithoutAccount_hasNullAccountFields() {
        GmailProcessedMessage gpm = item(currentUser, GmailProcessedStatus.UNRESOLVED_ACCOUNT, null);
        when(processedMessageRepository.findAttentionItems(eq(currentUser.getId()), anyCollection(), any()))
                .thenReturn(new PageImpl<>(List.of(new GmailAttentionRow(gpm, "me@gmail.com", null, null))));

        GmailAttentionItemResponse row = controller.getAttentionItems(0, 10, false, false).getBody().getContent().get(0);

        assertThat(row.accountId()).isNull();
        assertThat(row.accountName()).isNull();
        assertThat(row.archivedAt()).isNull();
    }

    // ------------------------------------------------------------------ helpers

    private GmailProcessedMessage item(User owner, GmailProcessedStatus status, Instant archivedAt) {
        GmailProcessedMessage gpm = new GmailProcessedMessage();
        gpm.setId(UUID.randomUUID());
        gpm.setUser(owner);
        gpm.setConnection(connection);
        gpm.setGmailMessageId("msg-" + UUID.randomUUID());
        gpm.setStatus(status);
        gpm.setArchivedAt(archivedAt);
        gpm.setDiscoveredAt(Instant.now());
        when(processedMessageRepository.findById(gpm.getId())).thenReturn(Optional.of(gpm));
        return gpm;
    }
}
