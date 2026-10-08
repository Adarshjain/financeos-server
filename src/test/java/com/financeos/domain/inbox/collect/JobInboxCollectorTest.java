package com.financeos.domain.inbox.collect;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.financeos.api.inbox.dto.InboxItemResponse;
import com.financeos.api.inbox.dto.InboxRefsResponse;
import com.financeos.core.time.AppTime;
import com.financeos.domain.job.Job;
import com.financeos.domain.job.JobRepository;
import com.financeos.domain.job.JobStatus;
import com.financeos.domain.job.JobTrigger;
import com.financeos.domain.job.JobType;
import com.financeos.domain.notification.job.JobNotificationService;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;

class JobInboxCollectorTest {

    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");
    private static final LocalDate TODAY = LocalDate.of(2026, 10, 20);
    /** Midnight IST seven days ago: the oldest finish that still counts. */
    private static final Instant CUTOFF = TODAY.minusDays(7).atStartOfDay(IST).toInstant();

    private final JobRepository repository = mock(JobRepository.class);
    private final JobInboxCollector collector = new JobInboxCollector(repository, new ObjectMapper());
    private final UUID userId = UUID.randomUUID();
    private final List<Job> jobs = new ArrayList<>();

    @BeforeEach
    void setUp() {
        AppTime.useClock(Clock.fixed(TODAY.atTime(10, 5).atZone(IST).toInstant(), IST));
        when(repository.findByUserIdAndTypeInAndStatusIn(eq(userId), any(), any(), any())).thenAnswer(inv -> new PageImpl<>(jobs));
    }

    @AfterEach
    void tearDown() {
        AppTime.reset();
    }

    private Job job(JobType type, JobStatus status, JobTrigger trigger, Instant finishedAt) {
        Job job = new Job();
        job.setId(UUID.randomUUID());
        job.setUserId(userId);
        job.setType(type);
        job.setStatus(status);
        job.setTriggerSource(trigger);
        job.setFinishedAt(finishedAt);
        jobs.add(job);
        return job;
    }

    @Test
    @SuppressWarnings("unchecked")
    void asksOnlyForFinishedJobsOfTheNotifiedTypesNewestFirstCappedAtFifty() {
        collector.collect(userId, TODAY);

        ArgumentCaptor<java.util.Collection<JobType>> types = ArgumentCaptor.forClass(java.util.Collection.class);
        ArgumentCaptor<java.util.Collection<JobStatus>> statuses = ArgumentCaptor.forClass(java.util.Collection.class);
        ArgumentCaptor<Pageable> page = ArgumentCaptor.forClass(Pageable.class);
        verify(repository).findByUserIdAndTypeInAndStatusIn(eq(userId), types.capture(), statuses.capture(), page.capture());
        assertEquals(JobNotificationService.NOTIFIED_TYPES, types.getValue());
        assertEquals(List.of(JobStatus.SUCCEEDED, JobStatus.FAILED), List.copyOf(statuses.getValue()));
        assertEquals(0, page.getValue().getPageNumber());
        assertEquals(50, page.getValue().getPageSize());
        assertEquals(Sort.by(Sort.Direction.DESC, "finishedAt"), page.getValue().getSort());
    }

    @Test
    void aSucceededUserJobIsInformationWithItsResultSummary() {
        Instant finished = Instant.parse("2026-10-18T20:30:00Z"); // 19 Oct, 02:00 IST
        Job job = job(JobType.STATEMENT_INGEST, JobStatus.SUCCEEDED, JobTrigger.USER, finished);
        job.setResult("{\"filesProcessed\":2,\"totalCreated\":142,\"totalDuplicatesFound\":3}");

        List<InboxItemResponse> rows = collector.collect(userId, TODAY);

        assertEquals(1, rows.size());
        InboxItemResponse row = rows.get(0);
        String href = "/settings/activity?type=STATEMENT_INGEST&status=SUCCEEDED";
        assertEquals("job:" + job.getId(), row.key());
        assertEquals("job", row.kind());
        assertEquals(InboxItemResponse.ROW_ITEM, row.rowType());
        assertEquals("info", row.severity());
        assertEquals("info", row.section());
        assertEquals("Statement import finished", row.title());
        assertEquals("142 transactions added from 2 files · 3 flagged as possible duplicates", row.subtitle());
        assertEquals(href, row.href());
        assertEquals(null, row.amount());
        assertEquals(LocalDate.of(2026, 10, 19), row.date(), "the finish date in the business zone");
        assertEquals(List.of(InboxRows.open(href), InboxRows.dismiss()), row.actions());
        assertEquals(InboxRefsResponse.ofJob(job.getId()), row.refs());
    }

    @Test
    void aFailedUserJobNeedsALookWithItsErrorAndAFailedFilterLink() {
        Job job = job(JobType.RULE_APPLY, JobStatus.FAILED, JobTrigger.USER, TODAY.atTime(9, 0).atZone(IST).toInstant());
        job.setErrorMessage("Rule regex did not compile");

        InboxItemResponse row = collector.collect(userId, TODAY).get(0);

        assertEquals("warning", row.severity());
        assertEquals("needs_look", row.section());
        assertEquals("Rule apply failed", row.title());
        assertEquals("Rule regex did not compile", row.subtitle());
        assertEquals("/settings/activity?type=RULE_APPLY&status=FAILED", row.href());
        assertEquals(List.of(InboxRows.open(row.href()), InboxRows.dismiss()), row.actions());
    }

    @Test
    void cronJobsAndUnfinishedRowsNeverShow() {
        job(JobType.STATEMENT_INGEST, JobStatus.SUCCEEDED, JobTrigger.CRON, TODAY.atStartOfDay(IST).toInstant());
        job(JobType.STATEMENT_INGEST, JobStatus.FAILED, JobTrigger.USER, null);

        assertEquals(List.of(), collector.collect(userId, TODAY));
    }

    @Test
    void onlyJobsFinishedSinceMidnightSevenDaysAgoShow() {
        Job atCutoff = job(JobType.STATEMENT_INGEST, JobStatus.SUCCEEDED, JobTrigger.USER, CUTOFF);
        job(JobType.STATEMENT_INGEST, JobStatus.SUCCEEDED, JobTrigger.USER, CUTOFF.minusSeconds(1));

        assertEquals(List.of("job:" + atCutoff.getId()), collector.collect(userId, TODAY).stream().map(InboxItemResponse::key).toList());
    }
}
