package com.financeos.api.admin;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.financeos.core.time.AppTime;
import com.financeos.domain.job.Job;
import com.financeos.domain.job.JobRepository;
import com.financeos.domain.job.JobStatus;
import com.financeos.domain.job.JobTrigger;
import com.financeos.domain.job.JobType;
import com.financeos.domain.job.JobWorker;
import com.financeos.domain.user.User;
import com.financeos.domain.user.UserRepository;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Admin retry-all for jobs retries once per distinct failure (owner, type, payload), not once per failed row, and never
 * reruns a failure a later successful run already fixed. The single retry keeps the job's dedup key.
 */
@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(properties = {"app.admin.emails=failures-admin@example.test", "app.invite.code=test-invite-code"})
class AdminFailuresRetryAllJobsTest {

    private static final String BASE = "/api/v1/admin/failures";

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper mapper;
    @Autowired private UserRepository userRepository;
    @Autowired private JobRepository jobRepository;
    @MockBean private JobWorker jobWorker;

    private Cookie admin;
    private User userA;
    private User userB;

    @BeforeEach
    void setUp() throws Exception {
        admin = AdminFailuresTestSupport.login(mockMvc, mapper, "failures-admin@example.test", "adminPass123!");
        wipe();
        userA = saveUser("ra-a");
        userB = saveUser("ra-b");
    }

    @AfterEach
    void tearDown() {
        wipe();
        jobRepository.deleteAll(jobRepository.findAll().stream()
                .filter(j -> userA.getId().equals(j.getUserId()) || userB.getId().equals(j.getUserId())).toList());
        userRepository.deleteAll(List.of(userA, userB));
    }

    private void wipe() {
        jobRepository.deleteAll(jobRepository.findAll().stream()
                .filter(j -> j.getStatus() == JobStatus.FAILED
                        || j.getType() == JobType.GMAIL_SYNC).toList());
    }

    @Test
    void repeatedFailuresOfTheSameKindAreRetriedOnceAndTheOlderOnesDismissed() throws Exception {
        Job oldest = job(userA, JobType.GMAIL_SYNC, JobStatus.FAILED, "{\"c\":1}", "conn-1", daysAgo(3));
        Job middle = job(userA, JobType.GMAIL_SYNC, JobStatus.FAILED, "{\"c\":1}", "conn-1", daysAgo(2));
        Job newest = job(userA, JobType.GMAIL_SYNC, JobStatus.FAILED, "{\"c\":1}", "conn-1", daysAgo(1));

        retryAll().andExpect(jsonPath("$.affected").value(1)).andExpect(jsonPath("$.skipped").value(0))
                .andExpect(jsonPath("$.jobsEnqueued").value(1));

        List<Job> pending = pendingJobs();
        assertThat(pending).hasSize(1);
        Job clone = pending.get(0);
        assertThat(clone.getPayload()).isEqualTo("{\"c\":1}");
        assertThat(reload(newest).getDismissReason()).isEqualTo("Retried as " + clone.getId());
        for (Job older : List.of(oldest, middle)) {
            Job after = reload(older);
            assertThat(after.getDismissedAt()).isNotNull();
            assertThat(after.getDismissedBy()).isEqualTo("failures-admin@example.test");
            assertThat(after.getDismissReason()).isEqualTo("Superseded by " + clone.getId());
        }
    }

    @Test
    void differentOwnersTypesAndPayloadsAreSeparateGroups() throws Exception {
        job(userA, JobType.GMAIL_SYNC, JobStatus.FAILED, "{\"c\":1}", null, daysAgo(2));
        job(userA, JobType.GMAIL_SYNC, JobStatus.FAILED, "{\"c\":2}", null, daysAgo(1));
        job(userB, JobType.GMAIL_SYNC, JobStatus.FAILED, "{\"c\":1}", null, daysAgo(1));
        job(userA, JobType.PRICE_REFRESH, JobStatus.FAILED, "{\"c\":1}", null, daysAgo(1));

        retryAll().andExpect(jsonPath("$.affected").value(4)).andExpect(jsonPath("$.skipped").value(0))
                .andExpect(jsonPath("$.jobsEnqueued").value(4));
        assertThat(pendingJobs()).hasSize(4);
    }

    @Test
    void aLaterSuccessSupersedesTheWholeGroupAndIsCountedAsSkipped() throws Exception {
        Job f1 = job(userA, JobType.GMAIL_SYNC, JobStatus.FAILED, "{\"c\":1}", null, daysAgo(3));
        Job f2 = job(userA, JobType.GMAIL_SYNC, JobStatus.FAILED, "{\"c\":1}", null, daysAgo(2));
        job(userA, JobType.GMAIL_SYNC, JobStatus.SUCCEEDED, "{\"c\":1}", null, daysAgo(1));
        Job other = job(userA, JobType.GMAIL_SYNC, JobStatus.FAILED, "{\"c\":2}", null, daysAgo(2)); // success was for c=1 only

        retryAll().andExpect(jsonPath("$.affected").value(1)).andExpect(jsonPath("$.skipped").value(1))
                .andExpect(jsonPath("$.jobsEnqueued").value(1));

        for (Job j : List.of(f1, f2)) {
            Job after = reload(j);
            assertThat(after.getDismissedAt()).isNotNull();
            assertThat(after.getDismissReason()).isEqualTo("Superseded by a later successful run");
        }
        assertThat(reload(other).getDismissReason()).startsWith("Retried as ");
        assertThat(pendingJobs()).hasSize(1);
        assertThat(pendingJobs().get(0).getPayload()).isEqualTo("{\"c\":2}");
    }

    @Test
    void aSuccessOlderThanTheNewestFailureDoesNotSupersede() throws Exception {
        job(userA, JobType.GMAIL_SYNC, JobStatus.SUCCEEDED, "{\"c\":1}", null, daysAgo(3));
        Job failed = job(userA, JobType.GMAIL_SYNC, JobStatus.FAILED, "{\"c\":1}", null, daysAgo(1));

        retryAll().andExpect(jsonPath("$.affected").value(1)).andExpect(jsonPath("$.skipped").value(0));
        assertThat(reload(failed).getDismissReason()).startsWith("Retried as ");
    }

    @Test
    void aSuccessOfAnotherUserDoesNotSupersede() throws Exception {
        job(userB, JobType.GMAIL_SYNC, JobStatus.SUCCEEDED, "{\"c\":1}", null, daysAgo(1));
        Job failed = job(userA, JobType.GMAIL_SYNC, JobStatus.FAILED, "{\"c\":1}", null, daysAgo(2));

        retryAll().andExpect(jsonPath("$.affected").value(1)).andExpect(jsonPath("$.skipped").value(0));
        assertThat(reload(failed).getDismissReason()).startsWith("Retried as ");
    }

    @Test
    void singleRetryKeepsTheDedupKeyOnTheClone() throws Exception {
        Job failed = job(userA, JobType.GMAIL_SYNC, JobStatus.FAILED, "{\"c\":1}", "conn-9", daysAgo(1));

        JsonNode res = mapper.readTree(mockMvc.perform(post(BASE + "/jobs/" + failed.getId() + "/retry").cookie(admin))
                .andExpect(status().isAccepted()).andReturn().getResponse().getContentAsString());
        Job clone = jobRepository.findById(UUID.fromString(res.get("jobId").asText())).orElseThrow();
        assertThat(clone.getDedupKey()).isEqualTo("conn-9");
        assertThat(clone.getStatus()).isEqualTo(JobStatus.PENDING);
    }

    @Test
    void singleRetryDoesNotDuplicateAPendingJobWithTheSameDedupKey() throws Exception {
        Job failed = job(userA, JobType.GMAIL_SYNC, JobStatus.FAILED, "{\"c\":1}", "conn-9", daysAgo(1));
        Job active = job(userA, JobType.GMAIL_SYNC, JobStatus.PENDING, "{\"c\":1}", "conn-9", null);

        JsonNode res = mapper.readTree(mockMvc.perform(post(BASE + "/jobs/" + failed.getId() + "/retry").cookie(admin))
                .andExpect(status().isAccepted()).andReturn().getResponse().getContentAsString());
        assertThat(res.get("jobId").asText()).isEqualTo(active.getId().toString());
        assertThat(pendingJobs()).hasSize(1);
        assertThat(reload(failed).getDismissReason()).isEqualTo("Retried as " + active.getId());
    }

    @Test
    void retryAllDoesNotDuplicateAPendingJobWithTheSameDedupKey() throws Exception {
        Job failed = job(userA, JobType.GMAIL_SYNC, JobStatus.FAILED, "{\"c\":1}", "conn-9", daysAgo(1));
        Job active = job(userA, JobType.GMAIL_SYNC, JobStatus.RUNNING, "{\"c\":1}", "conn-9", null);

        retryAll().andExpect(jsonPath("$.affected").value(1));
        assertThat(pendingJobs()).isEmpty();
        assertThat(reload(failed).getDismissReason()).isEqualTo("Retried as " + active.getId());
    }

    // ------------------------------------------------------------------ helpers

    private org.springframework.test.web.servlet.ResultActions retryAll() throws Exception {
        return mockMvc.perform(post(BASE + "/retry-all").cookie(admin).contentType(MediaType.APPLICATION_JSON).content("{\"kind\":\"jobs\"}"))
                .andExpect(status().isOk());
    }

    private List<Job> pendingJobs() {
        return jobRepository.findAll().stream().filter(j -> j.getStatus() == JobStatus.PENDING
                && (userA.getId().equals(j.getUserId()) || userB.getId().equals(j.getUserId()))).toList();
    }

    private Job reload(Job j) {
        return jobRepository.findById(j.getId()).orElseThrow();
    }

    private static Instant daysAgo(int days) {
        return AppTime.nowInstant().minus(days, ChronoUnit.DAYS);
    }

    private Job job(User owner, JobType type, JobStatus status, String payload, String dedupKey, Instant finishedAt) {
        Job j = new Job();
        j.setUserId(owner.getId());
        j.setType(type);
        j.setStatus(status);
        j.setTriggerSource(JobTrigger.USER);
        j.setPayload(payload);
        j.setDedupKey(dedupKey);
        if (status == JobStatus.FAILED) {
            j.setErrorCode("IllegalStateException");
            j.setErrorMessage("boom");
            j.setErrorDetail("java.lang.IllegalStateException: boom");
            j.setAttempt(1);
        }
        if (finishedAt != null) {
            j.setStartedAt(finishedAt.minusSeconds(5));
            j.setFinishedAt(finishedAt);
        }
        return jobRepository.save(j);
    }

    private User saveUser(String prefix) {
        User u = new User();
        u.setDisplayName(prefix);
        u.setEmail(prefix + "-" + UUID.randomUUID() + "@example.test");
        u.setPasswordHash("hash");
        return userRepository.save(u);
    }
}
