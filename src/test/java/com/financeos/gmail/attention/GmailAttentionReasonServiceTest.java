package com.financeos.gmail.attention;

import com.financeos.api.gmail.dto.AttentionReasonCount;
import com.financeos.api.gmail.dto.GmailAttentionItemResponse;
import com.financeos.domain.account.StatementPasswordSavedEvent;
import com.financeos.domain.job.JobService;
import com.financeos.domain.llm.LlmKeySavedEvent;
import com.financeos.domain.user.User;
import com.financeos.gmail.classify.BankSenderDomains;
import com.financeos.gmail.domain.*;
import com.financeos.gmail.domain.GmailProcessedMessageRepository.AttentionBrief;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.transaction.PlatformTransactionManager;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

class GmailAttentionReasonServiceTest {

    private GmailProcessedMessageRepository repo;
    private JobService jobs;
    private GmailAttentionService service;
    private final UUID userId = UUID.randomUUID();
    private GmailConnection conn;

    @BeforeEach
    void setUp() {
        repo = mock(GmailProcessedMessageRepository.class);
        jobs = mock(JobService.class);
        service = new GmailAttentionService(repo, jobs, mock(PlatformTransactionManager.class));
        conn = new GmailConnection();
        conn.setId(UUID.randomUUID());
    }

    @ParameterizedTest
    @CsvSource(delimiter = '|', nullValues = "null", value = {
            "UNRESOLVED_ACCOUNT|null|NO_ACCOUNT",
            "UNRESOLVED_ACCOUNT|No active LLM API keys|NO_ACCOUNT",
            "ACCOUNT_NOT_OPTED_IN|null|IMPORT_OFF",
            "FAILED_PERMANENT|No active LLM API keys configured|NO_AI_KEY",
            "FAILED_PERMANENT|Please add an API key first|NO_AI_KEY",
            "FAILED_PERMANENT|Attachment could not be decrypted|LOCKED_STATEMENT",
            "FAILED_PERMANENT|Statement parse failed: bad|UNREADABLE_STATEMENT",
            "FAILED_PERMANENT|statement parse failed|UNREADABLE_STATEMENT",
            "FAILED_PERMANENT|Extraction failed: x|FAILED",
            "FAILED_PERMANENT|null|FAILED",
            "FAILED_RETRYABLE|Timeout|FAILED",
            "FAILED_PERMANENT|Other: statement parse failed|FAILED"})
    void reasonMapping(GmailProcessedStatus status, String error, AttentionReason expected) {
        assertThat(AttentionReason.of(status, error)).isEqualTo(expected);
    }

    private AttentionBrief brief(UUID id, GmailProcessedStatus status, String error) {
        return new AttentionBrief() {
            public UUID getId() { return id; }
            public GmailProcessedStatus getStatus() { return status; }
            public String getError() { return error; }
        };
    }

    private GmailProcessedMessage row(UUID id, GmailProcessedStatus status, String error) {
        GmailProcessedMessage g = new GmailProcessedMessage();
        g.setId(id);
        g.setStatus(status);
        g.setError(error);
        g.setAttemptCount(3);
        g.setConnection(conn);
        return g;
    }

    @Test
    void summaryCountsPerReason() {
        when(repo.findAttentionBriefs(eq(userId), anyCollection())).thenReturn(List.of(
                brief(UUID.randomUUID(), GmailProcessedStatus.UNRESOLVED_ACCOUNT, null),
                brief(UUID.randomUUID(), GmailProcessedStatus.UNRESOLVED_ACCOUNT, null),
                brief(UUID.randomUUID(), GmailProcessedStatus.FAILED_PERMANENT, "Statement parse failed"),
                brief(UUID.randomUUID(), GmailProcessedStatus.FAILED_PERMANENT, "boom")));

        List<AttentionReasonCount> out = service.summary(userId, false);

        assertThat(out).containsExactly(
                new AttentionReasonCount(AttentionReason.NO_ACCOUNT, 2),
                new AttentionReasonCount(AttentionReason.UNREADABLE_STATEMENT, 1),
                new AttentionReasonCount(AttentionReason.FAILED, 1));
        verify(repo, times(1)).findAttentionBriefs(eq(userId), argThat(c -> !c.contains(GmailProcessedStatus.FAILED_RETRYABLE)));
    }

    @Test
    void summaryIncludeRetryablePassesRetryableStatus() {
        when(repo.findAttentionBriefs(eq(userId), anyCollection())).thenReturn(List.of());
        assertThat(service.summary(userId, true)).isEmpty();
        verify(repo).findAttentionBriefs(eq(userId), argThat(c -> c.contains(GmailProcessedStatus.FAILED_RETRYABLE)));
    }

    @Test
    void retryAllResetsOnlyItemsOfThatReasonScopedToTheUser() {
        UUID noKey = UUID.randomUUID();
        UUID locked = UUID.randomUUID();
        when(repo.findAttentionBriefs(eq(userId), anyCollection())).thenReturn(List.of(
                brief(noKey, GmailProcessedStatus.FAILED_PERMANENT, "No active LLM API keys"),
                brief(locked, GmailProcessedStatus.FAILED_PERMANENT, "could not be decrypted")));
        GmailProcessedMessage g = row(noKey, GmailProcessedStatus.FAILED_PERMANENT, "No active LLM API keys");
        when(repo.findByUserIdAndIdIn(eq(userId), eq(List.of(noKey)))).thenReturn(List.of(g));

        int n = service.retryAll(userId, AttentionReason.NO_AI_KEY);

        assertThat(n).isEqualTo(1);
        assertThat(g.getStatus()).isEqualTo(GmailProcessedStatus.DISCOVERED);
        assertThat(g.getError()).isNull();
        assertThat(g.getAttemptCount()).isZero();
        verify(repo, never()).findByUserIdAndIdIn(any(), eq(List.of(locked)));
        verify(jobs, times(1)).enqueue(eq(userId), any(), any(), any(), any(), eq(conn.getId().toString()));
    }

    @Test
    void retryAllSkipsRowsNoLongerRetryable() {
        UUID id = UUID.randomUUID();
        when(repo.findAttentionBriefs(eq(userId), anyCollection())).thenReturn(List.of(brief(id, GmailProcessedStatus.FAILED_PERMANENT, "boom")));
        GmailProcessedMessage g = row(id, GmailProcessedStatus.CREATED, null);
        when(repo.findByUserIdAndIdIn(eq(userId), anyCollection())).thenReturn(List.of(g));

        assertThat(service.retryAll(userId, AttentionReason.FAILED)).isZero();
        assertThat(g.getStatus()).isEqualTo(GmailProcessedStatus.CREATED);
        verifyNoInteractions(jobs);
    }

    @Test
    void retryAllRunsInBatches() {
        List<AttentionBrief> briefs = new ArrayList<>();
        for (int i = 0; i < GmailAttentionService.BATCH_SIZE + 5; i++) {
            briefs.add(brief(UUID.randomUUID(), GmailProcessedStatus.UNRESOLVED_ACCOUNT, null));
        }
        when(repo.findAttentionBriefs(eq(userId), anyCollection())).thenReturn(briefs);
        when(repo.findByUserIdAndIdIn(eq(userId), anyCollection())).thenReturn(List.of());

        service.retryAll(userId, AttentionReason.NO_ACCOUNT);

        verify(repo, times(2)).findByUserIdAndIdIn(eq(userId), anyCollection());
    }

    @Test
    void archiveAllStampsOnlyThatReason() {
        UUID a = UUID.randomUUID();
        UUID b = UUID.randomUUID();
        when(repo.findAttentionBriefs(eq(userId), anyCollection())).thenReturn(List.of(
                brief(a, GmailProcessedStatus.ACCOUNT_NOT_OPTED_IN, null),
                brief(b, GmailProcessedStatus.UNRESOLVED_ACCOUNT, null)));
        GmailProcessedMessage g = row(a, GmailProcessedStatus.ACCOUNT_NOT_OPTED_IN, null);
        when(repo.findByUserIdAndIdIn(eq(userId), eq(List.of(a)))).thenReturn(List.of(g));

        assertThat(service.archiveAll(userId, AttentionReason.IMPORT_OFF)).isEqualTo(1);
        assertThat(g.getArchivedAt()).isNotNull();
        assertThat(g.getStatus()).isEqualTo(GmailProcessedStatus.ACCOUNT_NOT_OPTED_IN);
        verify(repo, never()).findByUserIdAndIdIn(any(), eq(List.of(b)));
    }

    @Test
    void autoRetryMapsEventsToReasonsAndSwallowsFailures() {
        GmailAttentionService svc = mock(GmailAttentionService.class);
        GmailAttentionAutoRetry retry = new GmailAttentionAutoRetry(svc);

        retry.onKeySaved(new LlmKeySavedEvent(userId));
        verify(svc).retryAll(userId, AttentionReason.NO_AI_KEY);

        retry.onPasswordSaved(new StatementPasswordSavedEvent(userId));
        verify(svc).retryAll(userId, AttentionReason.LOCKED_STATEMENT);
        verifyNoMoreInteractions(svc);

        when(svc.retryAll(any(), any())).thenThrow(new IllegalStateException("db down"));
        retry.onKeySaved(new LlmKeySavedEvent(userId)); // must not throw
    }

    @Test
    void responseCarriesReasonAndAccountHints() {
        User u = new User();
        u.setId(userId);
        GmailProcessedMessage g = row(UUID.randomUUID(), GmailProcessedStatus.UNRESOLVED_ACCOUNT, null);
        g.setUser(u);
        g.setSenderAddress("alerts@hdfcbank.net");
        g.setExtractedLast4("1234");

        GmailAttentionItemResponse r = GmailAttentionItemResponse.from(new GmailAttentionRow(g, "me@gmail.com", null, null));

        assertThat(r.reason()).isEqualTo(AttentionReason.NO_ACCOUNT);
        assertThat(r.suggestedBankId()).isEqualTo(BankSenderDomains.bankIdFor("alerts@hdfcbank.net"));
        assertThat(r.suggestedBankId()).isNotNull();
        assertThat(r.suggestedLast4()).isEqualTo("1234");

        g.setExtractedLast4(null);
        g.setSenderAddress("someone@unknown-example.org");
        GmailAttentionItemResponse r2 = GmailAttentionItemResponse.from(new GmailAttentionRow(g, "me@gmail.com", null, null));
        assertThat(r2.suggestedLast4()).isNull();
        assertThat(r2.suggestedBankId()).isNull();
    }
}
