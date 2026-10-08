package com.financeos.domain.notification.job;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.financeos.core.security.UserContext;
import com.financeos.core.time.AppTime;
import com.financeos.domain.job.Job;
import com.financeos.domain.job.JobRepository;
import com.financeos.domain.job.JobStatus;
import com.financeos.domain.job.JobTrigger;
import com.financeos.domain.job.JobType;
import com.financeos.domain.notification.NotificationKind;
import com.financeos.domain.notification.NotificationPrefs;
import com.financeos.domain.notification.NotificationPrefsLoader;
import com.financeos.domain.notification.NotificationSettingsCodec;
import com.financeos.domain.notification.NotificationSettingsService;
import com.financeos.domain.notification.UserNotificationSettings;
import com.financeos.domain.notification.push.PushMessage;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class JobNotificationServiceTest {

    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");
    private static final LocalDate TODAY = LocalDate.of(2026, 10, 20);

    private final JobRepository jobRepository = mock(JobRepository.class);
    private final NotificationPrefsLoader prefsLoader = mock(NotificationPrefsLoader.class);
    private final NotificationSettingsService settingsService = mock(NotificationSettingsService.class);
    private final ObjectMapper mapper = new ObjectMapper();
    private final JobNotificationService service = new JobNotificationService(jobRepository, prefsLoader, settingsService, mapper);

    private final UUID userId = UUID.randomUUID();
    private final UserNotificationSettings settings = new UserNotificationSettings(userId);
    private final Map<NotificationKind, Boolean> kinds = new EnumMap<>(NotificationSettingsCodec.parseKinds(null));
    private Job job;

    @BeforeEach
    void setUp() {
        AppTime.useClock(Clock.fixed(TODAY.atTime(23, 5).atZone(IST).toInstant(), IST)); // late night: events are never hour-gated
        job = new Job();
        job.setId(UUID.randomUUID());
        job.setUserId(userId);
        job.setType(JobType.STATEMENT_INGEST);
        job.setStatus(JobStatus.SUCCEEDED);
        job.setTriggerSource(JobTrigger.USER);
        job.setResult("{\"filesProcessed\":2,\"totalCreated\":142,\"totalDuplicatesFound\":3}");
        when(jobRepository.findById(job.getId())).thenReturn(Optional.of(job));
        when(settingsService.deliver(any(), any())).thenReturn(1);
        when(prefsLoader.load(userId)).thenReturn(new NotificationPrefs(settings, true, 9, List.of(0), kinds));
    }

    @AfterEach
    void tearDown() {
        AppTime.reset();
        UserContext.clear();
    }

    private JobFinishedEvent event() {
        return new JobFinishedEvent(job.getId(), job.getUserId(), job.getType(), job.getStatus(), job.getTriggerSource());
    }

    private PushMessage delivered() {
        ArgumentCaptor<PushMessage> message = ArgumentCaptor.forClass(PushMessage.class);
        verify(settingsService).deliver(eq(settings), message.capture());
        return message.getValue();
    }

    // ---------------------------------------------------------------- which jobs

    @Test
    void onlyUserTriggeredLongJobsThatSucceededOrFailedAreNotifiable() {
        assertTrue(JobNotificationService.notifiable(event()));
        assertFalse(JobNotificationService.notifiable(new JobFinishedEvent(job.getId(), userId, JobType.STATEMENT_INGEST, JobStatus.SUCCEEDED, JobTrigger.CRON)));
        assertFalse(JobNotificationService.notifiable(new JobFinishedEvent(job.getId(), userId, JobType.GMAIL_SYNC, JobStatus.SUCCEEDED, JobTrigger.USER)));
        assertFalse(JobNotificationService.notifiable(new JobFinishedEvent(job.getId(), userId, JobType.PRICE_REFRESH, JobStatus.FAILED, JobTrigger.USER)));
        assertFalse(JobNotificationService.notifiable(new JobFinishedEvent(job.getId(), userId, JobType.RULE_APPLY, JobStatus.CANCELLED, JobTrigger.USER)));
        assertFalse(JobNotificationService.notifiable(new JobFinishedEvent(job.getId(), null, JobType.RULE_APPLY, JobStatus.SUCCEEDED, JobTrigger.USER)));
        for (JobType type : List.of(JobType.INVESTMENT_IMPORT_COMMIT, JobType.BROKER_RECONCILE_COMMIT, JobType.RULE_APPLY)) {
            assertTrue(JobNotificationService.notifiable(new JobFinishedEvent(job.getId(), userId, type, JobStatus.FAILED, JobTrigger.USER)), type.name());
        }
    }

    // ---------------------------------------------------------------- service

    @Test
    void successPushesAQuietSummaryAndStampsTheJob() {
        assertEquals(1, service.onJobFinished(event()));
        PushMessage message = delivered();
        assertEquals("Statement import finished", message.title());
        assertEquals("142 transactions added from 2 files · 3 flagged as possible duplicates", message.body());
        assertEquals("/transactions/import", message.url());
        assertEquals("job-" + job.getId(), message.tag());
        assertTrue(message.quietWhenVisible(), "an open tab already toasts the job");
        assertNotNull(job.getNotifiedAt());
        verify(jobRepository).save(job);
    }

    @Test
    void alreadyNotifiedOrUnknownJobsAreIgnored() {
        job.setNotifiedAt(Instant.now());
        assertEquals(0, service.onJobFinished(event()));
        verify(settingsService, never()).deliver(any(), any());

        UUID other = UUID.randomUUID();
        when(jobRepository.findById(other)).thenReturn(Optional.empty());
        assertEquals(0, service.onJobFinished(new JobFinishedEvent(other, userId, JobType.RULE_APPLY, JobStatus.SUCCEEDED, JobTrigger.USER)));
    }

    @Test
    void cronJobsNeverTouchTheRepository() {
        job.setTriggerSource(JobTrigger.CRON);
        assertEquals(0, service.onJobFinished(event()));
        verify(jobRepository, never()).findById(any());
    }

    @Test
    void kindOffStampsWithoutPushing() {
        kinds.put(NotificationKind.JOB_FINISHED, false);
        assertEquals(0, service.onJobFinished(event()));
        assertNotNull(job.getNotifiedAt());
        verify(settingsService, never()).deliver(any(), any());
    }

    @Test
    void failureCarriesTheErrorTruncated() {
        job.setStatus(JobStatus.FAILED);
        job.setErrorMessage("x".repeat(200));
        service.onJobFinished(event());
        PushMessage message = delivered();
        assertEquals("Statement import failed", message.title());
        assertEquals(140, message.body().length());
        assertTrue(message.body().endsWith("…"));

        job.setErrorMessage(null);
        assertEquals("Open the job to see what went wrong.", JobMessages.forJob(job, mapper).body());
    }

    // ---------------------------------------------------------------- messages

    @Test
    void summariesPerJobType() {
        job.setType(JobType.INVESTMENT_IMPORT_COMMIT);
        job.setResult("{\"committed\":12,\"skipped\":2,\"failed\":[{\"rowIndex\":1}],\"skippedItems\":[]}");
        PushMessage imp = JobMessages.forJob(job, mapper);
        assertEquals("Investment import finished", imp.title());
        assertEquals("12 rows imported · 2 skipped · 1 failed", imp.body());
        assertEquals("/investments", imp.url());

        job.setType(JobType.BROKER_RECONCILE_COMMIT);
        job.setResult("{\"committed\":1,\"skipped\":0,\"failed\":[],\"skippedItems\":[]}");
        PushMessage rec = JobMessages.forJob(job, mapper);
        assertEquals("Broker reconciliation finished", rec.title());
        assertEquals("1 row imported", rec.body());
        assertEquals("/investments", rec.url());

        job.setType(JobType.RULE_APPLY);
        job.setResult("{\"appliedCount\":37}");
        PushMessage rule = JobMessages.forJob(job, mapper);
        assertEquals("Rule apply finished", rule.title());
        assertEquals("37 transactions categorised", rule.body());
        assertEquals("/settings/activity?type=RULE_APPLY", rule.url());

        job.setType(JobType.STATEMENT_INGEST);
        job.setResult("not json");
        assertEquals("Tap to see the result.", JobMessages.forJob(job, mapper).body());
        job.setResult(null);
        assertEquals("Tap to see the result.", JobMessages.forJob(job, mapper).body());
        job.setResult("{\"filesProcessed\":1,\"totalCreated\":1,\"totalDuplicatesFound\":0}");
        assertEquals("1 transaction added from 1 file", JobMessages.forJob(job, mapper).body());
    }

    // ---------------------------------------------------------------- listener

    @Test
    void listenerScopesTheUserAndSwallowsFailures() {
        JobNotificationService inner = mock(JobNotificationService.class);
        JobFinishedListener listener = new JobFinishedListener(inner);
        when(inner.onJobFinished(any())).thenAnswer(inv -> {
            assertEquals(userId, UserContext.getCurrentUserId());
            return 1;
        });
        listener.onJobFinished(event());
        assertNull(UserContext.getCurrentUserId(), "cleared afterwards when it set it");

        doThrow(new IllegalStateException("push down")).when(inner).onJobFinished(any());
        listener.onJobFinished(event()); // must not throw
        assertNull(UserContext.getCurrentUserId());
    }

    @Test
    void listenerKeepsAnAlreadySetUser() {
        UUID worker = UUID.randomUUID();
        UserContext.setCurrentUserId(worker);
        JobNotificationService inner = mock(JobNotificationService.class);
        new JobFinishedListener(inner).onJobFinished(event());
        assertEquals(worker, UserContext.getCurrentUserId());
    }
}
