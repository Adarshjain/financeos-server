package com.financeos.domain.inbox.collect;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

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
    private final JobInboxCollector collector = new JobInboxCollector(repository);
    private final UUID userId = UUID.randomUUID();
    private final List<Job> jobs = new ArrayList<>();

    @BeforeEach
    void setUp() {
        AppTime.useClock(Clock.fixed(TODAY.atTime(10, 5).atZone(IST).toInstant(), IST));
        when(repository.findByUserIdAndTypeInAndStatusInAndDismissedAtIsNull(eq(userId), any(), any(), any())).thenAnswer(inv -> new PageImpl<>(jobs));
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
    void asksOnlyForFailedJobsOfTheNotifiedTypesNewestFirstCappedAtFifty() {
        collector.collect(userId, TODAY);

        ArgumentCaptor<java.util.Collection<JobType>> types = ArgumentCaptor.forClass(java.util.Collection.class);
        ArgumentCaptor<java.util.Collection<JobStatus>> statuses = ArgumentCaptor.forClass(java.util.Collection.class);
        ArgumentCaptor<Pageable> page = ArgumentCaptor.forClass(Pageable.class);
        verify(repository).findByUserIdAndTypeInAndStatusInAndDismissedAtIsNull(eq(userId), types.capture(), statuses.capture(), page.capture());
        assertEquals(JobNotificationService.NOTIFIED_TYPES, types.getValue());
        assertEquals(List.of(JobStatus.FAILED), List.copyOf(statuses.getValue()));
        assertEquals(0, page.getValue().getPageNumber());
        assertEquals(50, page.getValue().getPageSize());
        assertEquals(Sort.by(Sort.Direction.DESC, "finishedAt"), page.getValue().getSort());
    }

    @Test
    void oneFailedImportIsASingularSummaryRow() {
        job(JobType.STATEMENT_INGEST, JobStatus.FAILED, JobTrigger.USER, TODAY.atTime(9, 0).atZone(IST).toInstant());

        List<InboxItemResponse> rows = collector.collect(userId, TODAY);

        assertEquals(1, rows.size());
        InboxItemResponse row = rows.get(0);
        assertEquals("jobs-failed", row.key());
        assertEquals("job", row.kind());
        assertEquals("summary", row.rowType());
        assertEquals("needs_look", row.section());
        assertEquals("warning", row.severity());
        assertEquals("1 import couldn't finish", row.title());
        assertEquals(1, row.count());
        assertEquals("/data-sources/sync?status=FAILED", row.href());
        assertEquals(InboxRefsResponse.NONE, row.refs());
        assertEquals(List.of(InboxRows.open(row.href()), InboxRows.dismiss()), row.actions());
    }

    @Test
    void severalFailedJobsAreOneRowWithTheirCount() {
        job(JobType.STATEMENT_INGEST, JobStatus.FAILED, JobTrigger.USER, TODAY.atTime(9, 0).atZone(IST).toInstant());
        job(JobType.RULE_APPLY, JobStatus.FAILED, JobTrigger.USER, TODAY.minusDays(1).atTime(9, 0).atZone(IST).toInstant());
        job(JobType.INVESTMENT_IMPORT_COMMIT, JobStatus.FAILED, JobTrigger.USER, TODAY.minusDays(2).atTime(9, 0).atZone(IST).toInstant());

        List<InboxItemResponse> rows = collector.collect(userId, TODAY);

        assertEquals(1, rows.size());
        assertEquals("jobs-failed", rows.get(0).key());
        assertEquals("3 tasks couldn't finish", rows.get(0).title());
        assertEquals(3, rows.get(0).count());
    }

    @Test
    void onlyImportTypesAreWordedAsImportsPlural() {
        job(JobType.STATEMENT_INGEST, JobStatus.FAILED, JobTrigger.USER, TODAY.atTime(9, 0).atZone(IST).toInstant());
        job(JobType.BROKER_RECONCILE_COMMIT, JobStatus.FAILED, JobTrigger.USER, TODAY.atTime(8, 0).atZone(IST).toInstant());

        assertEquals("2 imports couldn't finish", collector.collect(userId, TODAY).get(0).title());
    }

    @Test
    void aSingleNonImportJobIsWordedAsATask() {
        job(JobType.RULE_APPLY, JobStatus.FAILED, JobTrigger.USER, TODAY.atTime(9, 0).atZone(IST).toInstant());

        assertEquals("1 task couldn't finish", collector.collect(userId, TODAY).get(0).title());
    }

    @Test
    void cronJobsAndUnfinishedRowsNeverShow() {
        job(JobType.STATEMENT_INGEST, JobStatus.FAILED, JobTrigger.CRON, TODAY.atStartOfDay(IST).toInstant());
        job(JobType.STATEMENT_INGEST, JobStatus.FAILED, JobTrigger.USER, null);

        assertEquals(List.of(), collector.collect(userId, TODAY));
    }

    @Test
    void onlyJobsFinishedSinceMidnightSevenDaysAgoCount() {
        job(JobType.RULE_APPLY, JobStatus.FAILED, JobTrigger.USER, CUTOFF);
        job(JobType.RULE_APPLY, JobStatus.FAILED, JobTrigger.USER, CUTOFF.minusSeconds(1));

        List<InboxItemResponse> rows = collector.collect(userId, TODAY);

        assertEquals(1, rows.size());
        assertEquals(1, rows.get(0).count());
    }

    @Test
    void noFailedJobsMeansNoRow() {
        assertEquals(List.of(), collector.collect(userId, TODAY));
    }
}
