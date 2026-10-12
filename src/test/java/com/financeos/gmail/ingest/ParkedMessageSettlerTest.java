package com.financeos.gmail.ingest;

import com.financeos.domain.account.Account;
import com.financeos.domain.account.AccountType;
import com.financeos.gmail.domain.GmailProcessedMessage;
import com.financeos.gmail.domain.GmailProcessedMessageRepository;
import com.financeos.gmail.domain.GmailProcessedStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Instant;
import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

class ParkedMessageSettlerTest {

    private GmailProcessedMessageRepository repository;
    private AccountResolver accountResolver;
    private ParkedMessageSettler settler;
    private final UUID userId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        repository = mock(GmailProcessedMessageRepository.class);
        accountResolver = mock(AccountResolver.class);
        GmailIngestProperties properties = mock(GmailIngestProperties.class);
        when(properties.getDateWindowDays()).thenReturn(3);
        settler = new ParkedMessageSettler(repository, accountResolver, properties);
    }

    @Test
    void windowStart_isIngestDateMinusWindowAtIstMidnight() {
        assertThat(settler.windowStart(LocalDate.of(2026, 10, 7)))
                .isEqualTo(Instant.parse("2026-10-03T18:30:00Z"));
    }

    @Test
    void optedInAccount_skipsUnresolvedAndNotOptedInRowsOlderThanTheWindow() {
        Account account = account(LocalDate.of(2026, 10, 7));
        resolves("0438", account);
        GmailProcessedMessage unresolved = row(GmailProcessedStatus.UNRESOLVED_ACCOUNT);
        unresolved.setError("No single account matches card/account ending 0438");
        unresolved.setAttemptCount(2);
        unresolved.setNextRetryAt(Instant.now());
        GmailProcessedMessage notOptedIn = row(GmailProcessedStatus.ACCOUNT_NOT_OPTED_IN);
        when(repository.findParkedBefore(eq(userId), eq("0438"), any(), any())).thenReturn(List.of(unresolved, notOptedIn));

        int settled = settler.settle(userId, "0438");

        assertThat(settled).isEqualTo(2);
        for (GmailProcessedMessage row : List.of(unresolved, notOptedIn)) {
            assertThat(row.getStatus()).isEqualTo(GmailProcessedStatus.SKIPPED_BEFORE_WATERMARK);
            assertThat(row.getAccount()).isSameAs(account);
            assertThat(row.getError()).isNull();
            assertThat(row.getAttemptCount()).isZero();
            assertThat(row.getNextRetryAt()).isNull();
        }
        ArgumentCaptor<Collection<GmailProcessedStatus>> statuses = statusCaptor();
        verify(repository).findParkedBefore(eq(userId), eq("0438"), statuses.capture(), eq(Instant.parse("2026-10-03T18:30:00Z")));
        assertThat(statuses.getValue()).containsExactlyInAnyOrder(
                GmailProcessedStatus.UNRESOLVED_ACCOUNT, GmailProcessedStatus.ACCOUNT_NOT_OPTED_IN);
        verify(repository).saveAll(List.of(unresolved, notOptedIn));
    }

    @Test
    void accountWithoutIngestDate_reparksUnresolvedRowsAsNotOptedIn_keepingTheArchive() {
        Account account = account(null);
        resolves("5555", account);
        Instant archivedAt = Instant.parse("2026-10-01T00:00:00Z");
        GmailProcessedMessage archived = row(GmailProcessedStatus.UNRESOLVED_ACCOUNT);
        archived.setArchivedAt(archivedAt);
        when(repository.findParked(eq(userId), eq("5555"), any())).thenReturn(List.of(archived));

        settler.settle(userId, "5555");

        assertThat(archived.getStatus()).isEqualTo(GmailProcessedStatus.ACCOUNT_NOT_OPTED_IN);
        assertThat(archived.getAccount()).isSameAs(account);
        assertThat(archived.getError()).isEqualTo("Account HDFC Savings is not opted in for Gmail ingestion");
        assertThat(archived.getArchivedAt()).isEqualTo(archivedAt);
        ArgumentCaptor<Collection<GmailProcessedStatus>> statuses = statusCaptor();
        verify(repository).findParked(eq(userId), eq("5555"), statuses.capture());
        assertThat(statuses.getValue()).containsExactly(GmailProcessedStatus.UNRESOLVED_ACCOUNT);
        verify(repository, never()).findParkedBefore(any(), any(), any(), any());
    }

    @Test
    void lastFourWithNoSingleAccount_leavesRowsParked() {
        when(accountResolver.resolve("7358")).thenReturn(Optional.empty());

        assertThat(settler.settle(userId, "7358")).isZero();

        verify(repository, never()).findParkedBefore(any(), any(), any(), any());
        verify(repository, never()).findParked(any(), any(), any());
        verify(repository, never()).saveAll(anyList());
    }

    @Test
    void nothingOlderThanTheWindow_savesNothing() {
        resolves("0438", account(LocalDate.of(2026, 10, 7)));
        when(repository.findParkedBefore(eq(userId), eq("0438"), any(), any())).thenReturn(List.of());

        assertThat(settler.settle(userId, "0438")).isZero();

        verify(repository, never()).saveAll(anyList());
    }

    @Test
    void blankOrNullLastFour_settlesNothing() {
        assertThat(settler.settle(userId, null)).isZero();
        assertThat(settler.settle(userId, "  ")).isZero();

        verifyNoInteractions(accountResolver, repository);
    }

    @Test
    void settleAll_settlesEachParkedLastFourAndSumsThem() {
        when(repository.findParkedLast4s(eq(userId), any())).thenReturn(List.of("0438", "7106", "7358"));
        resolves("0438", account(LocalDate.of(2026, 10, 7)));
        resolves("7106", account(LocalDate.of(2026, 9, 17)));
        when(accountResolver.resolve("7358")).thenReturn(Optional.empty());
        when(repository.findParkedBefore(eq(userId), eq("0438"), any(), any()))
                .thenReturn(List.of(row(GmailProcessedStatus.UNRESOLVED_ACCOUNT), row(GmailProcessedStatus.UNRESOLVED_ACCOUNT)));
        when(repository.findParkedBefore(eq(userId), eq("7106"), any(), any()))
                .thenReturn(List.of(row(GmailProcessedStatus.UNRESOLVED_ACCOUNT)));

        assertThat(settler.settleAll(userId)).isEqualTo(3);

        ArgumentCaptor<Collection<GmailProcessedStatus>> statuses = statusCaptor();
        verify(repository).findParkedLast4s(eq(userId), statuses.capture());
        assertThat(statuses.getValue()).containsExactlyInAnyOrder(
                GmailProcessedStatus.UNRESOLVED_ACCOUNT, GmailProcessedStatus.ACCOUNT_NOT_OPTED_IN);
    }

    // ------------------------------------------------------------------ helpers

    private void resolves(String last4, Account account) {
        when(accountResolver.resolve(last4)).thenReturn(Optional.of(new AccountResolver.ResolvedCard(account, null)));
    }

    private static Account account(LocalDate ingestFromDate) {
        Account account = new Account("HDFC Savings", AccountType.bank_account);
        account.setId(UUID.randomUUID());
        account.setIngestFromDate(ingestFromDate);
        return account;
    }

    private static GmailProcessedMessage row(GmailProcessedStatus status) {
        GmailProcessedMessage row = new GmailProcessedMessage();
        row.setId(UUID.randomUUID());
        row.setStatus(status);
        return row;
    }

    @SuppressWarnings("unchecked")
    private static ArgumentCaptor<Collection<GmailProcessedStatus>> statusCaptor() {
        return ArgumentCaptor.forClass(Collection.class);
    }
}
