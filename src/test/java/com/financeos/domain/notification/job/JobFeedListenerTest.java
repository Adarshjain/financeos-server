package com.financeos.domain.notification.job;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.financeos.core.security.UserContext;
import com.financeos.domain.job.Job;
import com.financeos.domain.job.JobRepository;
import com.financeos.domain.job.JobStatus;
import com.financeos.domain.job.JobTrigger;
import com.financeos.domain.job.JobType;
import com.financeos.domain.notification.feed.AppNotificationKind;
import com.financeos.domain.notification.feed.AppNotificationRefs;
import com.financeos.domain.notification.feed.NotificationFeedService;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class JobFeedListenerTest {

    private final JobRepository jobRepository = mock(JobRepository.class);
    private final NotificationFeedService feedService = mock(NotificationFeedService.class);
    private final JobFeedListener listener = new JobFeedListener(jobRepository, feedService, new ObjectMapper());
    private final UUID userId = UUID.randomUUID();

    @AfterEach
    void tearDown() {
        UserContext.clear();
    }

    private Job fire(JobType type, JobStatus status, JobTrigger trigger, String result, String error) {
        Job job = new Job();
        job.setId(UUID.randomUUID());
        job.setUserId(userId);
        job.setType(type);
        job.setStatus(status);
        job.setTriggerSource(trigger);
        job.setResult(result);
        job.setErrorMessage(error);
        job.setFinishedAt(Instant.parse("2026-10-20T05:00:00Z"));
        when(jobRepository.findById(job.getId())).thenReturn(Optional.of(job));
        listener.onJobFinished(new JobFinishedEvent(job.getId(), userId, type, status, trigger));
        return job;
    }

    private void recorded(Job job, String kind, String title, String body) {
        verify(feedService).record(userId, kind, title, body, AppNotificationRefs.ofJob(job.getId(), job.getType().name()), "job:" + job.getId());
    }

    private void nothing() {
        verify(feedService, never()).record(any(), any(), any(), any(), any(), any());
    }

    private void previous(JobStatus status) {
        Job prev = new Job();
        prev.setStatus(status);
        when(jobRepository.findPreviousFinished(eq(userId), any(), any(), any(), any(), any())).thenReturn(List.of(prev));
    }

    // ---------------------------------------------------------------- statement ingest

    @Test
    void statementIngestFailureNotifiesWithTheReasonForUserAndCron() {
        Job job = fire(JobType.STATEMENT_INGEST, JobStatus.FAILED, JobTrigger.USER, null, "Password wrong");
        recorded(job, AppNotificationKind.JOB_FAILED, "Couldn't import statement", "Password wrong");
        assertNull(UserContext.getCurrentUserId());
    }

    @Test
    void statementIngestFailureWithoutAnErrorUsesTheGenericLine() {
        Job job = fire(JobType.STATEMENT_INGEST, JobStatus.FAILED, JobTrigger.CRON, null, null);
        recorded(job, AppNotificationKind.JOB_FAILED, "Couldn't import statement", "Open the job to see what went wrong.");
    }

    @Test
    void statementIngestSuccessNeverNotifiesWhateverTheTrigger() {
        fire(JobType.STATEMENT_INGEST, JobStatus.SUCCEEDED, JobTrigger.USER, "{\"totalCreated\":12}", null);
        fire(JobType.STATEMENT_INGEST, JobStatus.SUCCEEDED, JobTrigger.CRON, "{\"totalCreated\":12}", null);
        nothing();
    }

    // ---------------------------------------------------------------- gmail sync

    @Test
    void gmailSyncSuccessNotifiesOnlyWhenTransactionsWereCreated() {
        Job job = fire(JobType.GMAIL_SYNC, JobStatus.SUCCEEDED, JobTrigger.CRON, "{\"created\":3}", null);
        recorded(job, AppNotificationKind.GMAIL_SYNC, "Gmail sync", "3 new transactions");
    }

    @Test
    void gmailSyncSingularAndZero() {
        Job one = fire(JobType.GMAIL_SYNC, JobStatus.SUCCEEDED, JobTrigger.USER, "{\"created\":1}", null);
        recorded(one, AppNotificationKind.GMAIL_SYNC, "Gmail sync", "1 new transaction");
        fire(JobType.GMAIL_SYNC, JobStatus.SUCCEEDED, JobTrigger.CRON, "{\"created\":0}", null);
        fire(JobType.GMAIL_SYNC, JobStatus.SUCCEEDED, JobTrigger.CRON, null, null);
        verify(feedService, org.mockito.Mockito.times(1)).record(any(), any(), any(), any(), any(), any());
    }

    @Test
    void gmailSyncFailureNotifiesWhenThereIsNoPreviousJobOrItSucceeded() {
        Job first = fire(JobType.GMAIL_SYNC, JobStatus.FAILED, JobTrigger.CRON, null, "Token rejected");
        recorded(first, AppNotificationKind.JOB_FAILED, "Gmail sync failed", "Token rejected");

        previous(JobStatus.SUCCEEDED);
        Job second = fire(JobType.GMAIL_SYNC, JobStatus.FAILED, JobTrigger.CRON, null, "Token rejected");
        recorded(second, AppNotificationKind.JOB_FAILED, "Gmail sync failed", "Token rejected");
    }

    @Test
    void gmailSyncFailureRightAfterAFailureDoesNotRepeat() {
        previous(JobStatus.FAILED);
        fire(JobType.GMAIL_SYNC, JobStatus.FAILED, JobTrigger.CRON, null, "Token rejected");
        nothing();
    }

    @Test
    void gmailSyncSuccessIsNotSuppressedByAPreviousFailure() {
        previous(JobStatus.FAILED);
        Job job = fire(JobType.GMAIL_SYNC, JobStatus.SUCCEEDED, JobTrigger.CRON, "{\"created\":2}", null);
        recorded(job, AppNotificationKind.GMAIL_SYNC, "Gmail sync", "2 new transactions");
    }

    // ---------------------------------------------------------------- price refresh

    @Test
    void priceRefreshFailureNotifiesOnceAndSuccessNever() {
        Job job = fire(JobType.PRICE_REFRESH, JobStatus.FAILED, JobTrigger.CRON, null, "Provider down");
        recorded(job, AppNotificationKind.JOB_FAILED, "Couldn't refresh prices", "Provider down");

        previous(JobStatus.FAILED);
        fire(JobType.PRICE_REFRESH, JobStatus.FAILED, JobTrigger.CRON, null, "Provider down");
        fire(JobType.PRICE_REFRESH, JobStatus.SUCCEEDED, JobTrigger.CRON, "{\"updated\":9}", null);
        verify(feedService, org.mockito.Mockito.times(1)).record(any(), any(), any(), any(), any(), any());
    }

    // ---------------------------------------------------------------- investment import / broker reconcile

    @Test
    void investmentImportSuccessNotifiesWhenRowsWereCommitted() {
        Job job = fire(JobType.INVESTMENT_IMPORT_COMMIT, JobStatus.SUCCEEDED, JobTrigger.USER, "{\"committed\":5,\"skipped\":1}", null);
        recorded(job, AppNotificationKind.JOB_FINISHED, "Investment import finished", "5 rows imported · 1 skipped");
    }

    @Test
    void brokerReconcileSuccessUsesItsOwnTitle() {
        Job job = fire(JobType.BROKER_RECONCILE_COMMIT, JobStatus.SUCCEEDED, JobTrigger.USER, "{\"committed\":1}", null);
        recorded(job, AppNotificationKind.JOB_FINISHED, "Broker reconciliation finished", "1 row imported");
    }

    @Test
    void commitJobsWithNothingCommittedStaySilentButFailuresNotify() {
        fire(JobType.INVESTMENT_IMPORT_COMMIT, JobStatus.SUCCEEDED, JobTrigger.USER, "{\"committed\":0}", null);
        fire(JobType.BROKER_RECONCILE_COMMIT, JobStatus.SUCCEEDED, JobTrigger.USER, "{\"committed\":0}", null);
        nothing();

        Job inv = fire(JobType.INVESTMENT_IMPORT_COMMIT, JobStatus.FAILED, JobTrigger.USER, null, "Bad file");
        recorded(inv, AppNotificationKind.JOB_FAILED, "Investment import failed", "Bad file");
        Job broker = fire(JobType.BROKER_RECONCILE_COMMIT, JobStatus.FAILED, JobTrigger.USER, null, "Bad file");
        recorded(broker, AppNotificationKind.JOB_FAILED, "Broker reconciliation failed", "Bad file");
    }

    // ---------------------------------------------------------------- rule apply

    @Test
    void ruleApplyTitleNamesTheRuleWhenTheResultCarriesIt() {
        Job job = fire(JobType.RULE_APPLY, JobStatus.SUCCEEDED, JobTrigger.USER, "{\"appliedCount\":1,\"ruleName\":\"Swiggy\"}", null);
        recorded(job, AppNotificationKind.JOB_FINISHED, "Rule 'Swiggy' applied", "1 transaction categorised");
    }

    @Test
    void ruleApplySuccessNotifiesOnlyWhenSomethingWasCategorised() {
        Job job = fire(JobType.RULE_APPLY, JobStatus.SUCCEEDED, JobTrigger.USER, "{\"appliedCount\":4}", null);
        recorded(job, AppNotificationKind.JOB_FINISHED, "Rule applied", "4 transactions categorised");

        fire(JobType.RULE_APPLY, JobStatus.SUCCEEDED, JobTrigger.USER, "{\"appliedCount\":0}", null);
        verify(feedService, org.mockito.Mockito.times(1)).record(any(), any(), any(), any(), any(), any());
    }

    @Test
    void ruleApplyFailureNotifies() {
        Job job = fire(JobType.RULE_APPLY, JobStatus.FAILED, JobTrigger.USER, null, "Rule deleted");
        recorded(job, AppNotificationKind.JOB_FAILED, "Rule apply failed", "Rule deleted");
    }

    // ---------------------------------------------------------------- guards

    @Test
    void cancelledJobsAndMissingJobsAreIgnored() {
        fire(JobType.RULE_APPLY, JobStatus.CANCELLED, JobTrigger.USER, null, null);
        listener.onJobFinished(new JobFinishedEvent(UUID.randomUUID(), userId, JobType.RULE_APPLY, JobStatus.FAILED, JobTrigger.USER));
        nothing();
    }

    @Test
    void keepsAnAlreadySetUserAndSwallowsFeedFailures() {
        UUID existing = UUID.randomUUID();
        UserContext.setCurrentUserId(existing);
        doThrow(new IllegalStateException("feed down")).when(feedService).record(any(), any(), any(), any(), any(), any());

        fire(JobType.STATEMENT_INGEST, JobStatus.FAILED, JobTrigger.USER, null, "x");

        assertEquals(existing, UserContext.getCurrentUserId());
    }
}
