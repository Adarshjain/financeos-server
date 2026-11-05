package com.financeos.api.admin;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.financeos.core.time.AppTime;
import com.financeos.domain.job.Job;
import com.financeos.domain.job.JobArtifact;
import com.financeos.domain.job.JobArtifactRepository;
import com.financeos.domain.job.JobRepository;
import com.financeos.domain.job.JobStatus;
import com.financeos.domain.job.JobTrigger;
import com.financeos.domain.job.JobType;
import com.financeos.domain.job.JobWorker;
import com.financeos.domain.user.User;
import com.financeos.domain.user.UserRepository;
import com.financeos.gmail.domain.GmailConnection;
import com.financeos.gmail.domain.GmailConnectionRepository;
import com.financeos.gmail.domain.GmailProcessedMessage;
import com.financeos.gmail.domain.GmailProcessedMessageRepository;
import com.financeos.gmail.domain.GmailProcessedStatus;
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
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Admin Failures tool end to end over HTTP: lists across users, detail with the stack, retry / skip / dismiss, retry-all.
 * The job worker is mocked so enqueued jobs stay PENDING and can be inspected.
 */
@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(properties = {"app.admin.emails=failures-admin@example.test", "app.invite.code=test-invite-code"})
class AdminFailuresControllerTest {

    private static final String BASE = "/api/v1/admin/failures";

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper mapper;
    @Autowired private UserRepository userRepository;
    @Autowired private JobRepository jobRepository;
    @Autowired private JobArtifactRepository artifactRepository;
    @Autowired private GmailConnectionRepository connectionRepository;
    @Autowired private GmailProcessedMessageRepository gmailRepository;
    @MockBean private JobWorker jobWorker;

    private Cookie admin;
    private User userA;
    private User userB;
    private GmailConnection connA;
    private GmailConnection connB;

    @BeforeEach
    void setUp() throws Exception {
        admin = AdminFailuresTestSupport.login(mockMvc, mapper, "failures-admin@example.test", "adminPass123!");
        wipe();
        userA = saveUser("fa-a");
        userB = saveUser("fa-b");
        connA = saveConnection(userA);
        connB = saveConnection(userB);
    }

    @AfterEach
    void tearDown() {
        wipe();
        gmailRepository.deleteAll(gmailRepository.findAll().stream()
                .filter(g -> g.getConnection().getId().equals(connA.getId()) || g.getConnection().getId().equals(connB.getId())).toList());
        connectionRepository.deleteAll(List.of(connA, connB));
        userRepository.deleteAll(List.of(userA, userB));
    }

    /** Hermetic lists: no failed job or failed/skipped ledger row of another test is left around. */
    private void wipe() {
        jobRepository.deleteAll(jobRepository.findAll().stream()
                .filter(j -> j.getStatus() == JobStatus.FAILED || j.getType() == JobType.GMAIL_SYNC).toList());
        gmailRepository.deleteAll(gmailRepository.findAll().stream().filter(g -> g.getStatus() == GmailProcessedStatus.FAILED_RETRYABLE
                || g.getStatus() == GmailProcessedStatus.FAILED_PERMANENT || g.getStatus() == GmailProcessedStatus.SKIPPED_BY_ADMIN
                || g.getStatus() == GmailProcessedStatus.DISCOVERED).toList());
    }

    // ------------------------------------------------------------------ summary + lists

    @Test
    void summaryCountsNonDismissedFailuresOnly() throws Exception {
        failedJob(userA, JobType.PRICE_REFRESH, daysAgo(1));
        Job dismissed = failedJob(userB, JobType.PRICE_REFRESH, daysAgo(1));
        dismissed.setDismissedAt(Instant.now());
        jobRepository.save(dismissed);
        failedJob(userB, JobType.PRICE_REFRESH, daysAgo(45)); // out of the 30-day window
        gmail(connA, GmailProcessedStatus.FAILED_RETRYABLE);
        gmail(connA, GmailProcessedStatus.FAILED_PERMANENT);
        gmail(connB, GmailProcessedStatus.FAILED_PERMANENT);
        gmail(connB, GmailProcessedStatus.SKIPPED_BY_ADMIN);

        mockMvc.perform(get(BASE + "/summary").cookie(admin))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.failedJobs").value(1))
                .andExpect(jsonPath("$.failedGmailRetryable").value(1))
                .andExpect(jsonPath("$.failedGmailPermanent").value(2));
    }

    @Test
    void jobsListIsCrossUserNewestFirstPaginatedAndWindowed() throws Exception {
        Job old = failedJob(userA, JobType.PRICE_REFRESH, daysAgo(10));
        Job mid = failedJob(userB, JobType.RULE_APPLY, daysAgo(5));
        Job recent = failedJob(userA, JobType.PRICE_REFRESH, daysAgo(1));
        failedJob(userA, JobType.PRICE_REFRESH, daysAgo(31)); // out of window

        JsonNode page0 = json(get(BASE + "/jobs").param("size", "2").cookie(admin));
        assertThat(page0.get("totalElements").asInt()).isEqualTo(3);
        assertThat(page0.get("content")).hasSize(2);
        assertThat(page0.at("/content/0/id").asText()).isEqualTo(recent.getId().toString());
        assertThat(page0.at("/content/1/id").asText()).isEqualTo(mid.getId().toString());
        assertThat(page0.at("/content/1/userEmail").asText()).isEqualTo(userB.getEmail());
        assertThat(page0.at("/content/1/jobName").asText()).isEqualTo("rule-apply");
        assertThat(page0.at("/content/1/error").asText()).isEqualTo("boom");
        assertThat(page0.at("/content/1/errorCode").asText()).isEqualTo("IllegalStateException");

        JsonNode page1 = json(get(BASE + "/jobs").param("size", "2").param("page", "1").cookie(admin));
        assertThat(page1.get("content")).hasSize(1);
        assertThat(page1.at("/content/0/id").asText()).isEqualTo(old.getId().toString());
    }

    @Test
    void jobsListHidesDismissedUnlessAsked() throws Exception {
        Job kept = failedJob(userA, JobType.PRICE_REFRESH, daysAgo(1));
        Job dismissed = failedJob(userA, JobType.PRICE_REFRESH, daysAgo(2));
        dismissed.setDismissedAt(Instant.now());
        dismissed.setDismissedBy("someone@example.test");
        dismissed.setDismissReason("seen");
        jobRepository.save(dismissed);

        JsonNode hidden = json(get(BASE + "/jobs").cookie(admin));
        assertThat(hidden.get("content")).hasSize(1);
        assertThat(hidden.at("/content/0/id").asText()).isEqualTo(kept.getId().toString());

        JsonNode all = json(get(BASE + "/jobs").param("includeDismissed", "true").cookie(admin));
        assertThat(all.get("content")).hasSize(2);
        assertThat(all.at("/content/1/dismissedBy").asText()).isEqualTo("someone@example.test");
        assertThat(all.at("/content/1/dismissReason").asText()).isEqualTo("seen");
    }

    @Test
    void jobsListMarksStatementIngestWithoutFilesNotRetryable() throws Exception {
        Job expired = failedJob(userA, JobType.STATEMENT_INGEST, daysAgo(1));
        Job withFile = failedJob(userA, JobType.STATEMENT_INGEST, daysAgo(2));
        addArtifact(withFile);
        Job plain = failedJob(userA, JobType.PRICE_REFRESH, daysAgo(3));

        JsonNode list = json(get(BASE + "/jobs").cookie(admin));
        assertThat(retryableOf(list, expired)).isFalse();
        assertThat(retryableOf(list, withFile)).isTrue();
        assertThat(retryableOf(list, plain)).isTrue();
    }

    @Test
    void gmailListDefaultsToFailedStatusesAndFiltersByStatus() throws Exception {
        GmailProcessedMessage retryable = gmail(connA, GmailProcessedStatus.FAILED_RETRYABLE);
        GmailProcessedMessage permanent = gmail(connB, GmailProcessedStatus.FAILED_PERMANENT);
        GmailProcessedMessage skipped = gmail(connB, GmailProcessedStatus.SKIPPED_BY_ADMIN);
        gmail(connA, GmailProcessedStatus.CREATED);

        JsonNode both = json(get(BASE + "/gmail").cookie(admin));
        assertThat(both.get("totalElements").asInt()).isEqualTo(2);
        assertThat(ids(both, "ledgerId")).containsExactlyInAnyOrder(retryable.getId().toString(), permanent.getId().toString());
        JsonNode row = rowOf(both, "ledgerId", permanent.getId());
        assertThat(row.get("userEmail").asText()).isEqualTo(userB.getEmail());
        assertThat(row.get("connectionEmail").asText()).isEqualTo(connB.getEmail());
        assertThat(row.get("sender").asText()).isEqualTo("alerts@bank.test");
        assertThat(row.get("subject").asText()).isEqualTo("Alert");
        assertThat(row.get("status").asText()).isEqualTo("FAILED_PERMANENT");
        assertThat(row.get("error").asText()).isEqualTo("parse failed");

        JsonNode onlySkipped = json(get(BASE + "/gmail").param("status", "SKIPPED_BY_ADMIN").cookie(admin));
        assertThat(ids(onlySkipped, "ledgerId")).containsExactly(skipped.getId().toString());

        JsonNode onlyRetryable = json(get(BASE + "/gmail").param("status", "FAILED_RETRYABLE").cookie(admin));
        assertThat(ids(onlyRetryable, "ledgerId")).containsExactly(retryable.getId().toString());

        mockMvc.perform(get(BASE + "/gmail").param("status", "CREATED").cookie(admin)).andExpect(status().isBadRequest());
    }

    @Test
    void gmailListPaginates() throws Exception {
        for (int i = 0; i < 3; i++) {
            gmail(connA, GmailProcessedStatus.FAILED_PERMANENT);
        }
        JsonNode p0 = json(get(BASE + "/gmail").param("size", "2").cookie(admin));
        JsonNode p1 = json(get(BASE + "/gmail").param("size", "2").param("page", "1").cookie(admin));
        assertThat(p0.get("totalElements").asInt()).isEqualTo(3);
        assertThat(p0.get("content")).hasSize(2);
        assertThat(p1.get("content")).hasSize(1);
    }

    @Test
    void detailsReturnTheStack() throws Exception {
        Job job = failedJob(userA, JobType.PRICE_REFRESH, daysAgo(1));
        GmailProcessedMessage g = gmail(connA, GmailProcessedStatus.FAILED_PERMANENT);

        mockMvc.perform(get(BASE + "/jobs/" + job.getId()).cookie(admin))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.errorDetail").value("java.lang.IllegalStateException: boom\n\tat Foo.bar(Foo.java:1)"))
                .andExpect(jsonPath("$.userEmail").value(userA.getEmail()));
        mockMvc.perform(get(BASE + "/gmail/" + g.getId()).cookie(admin))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.errorDetail").value("java.lang.IllegalStateException: boom\n\tat Foo.bar(Foo.java:1)"))
                .andExpect(jsonPath("$.connectionEmail").value(connA.getEmail()));
        // lists carry no stack
        assertThat(json(get(BASE + "/jobs").cookie(admin)).at("/content/0/errorDetail").isNull()).isTrue();

        mockMvc.perform(get(BASE + "/jobs/" + UUID.randomUUID()).cookie(admin)).andExpect(status().isNotFound());
        mockMvc.perform(get(BASE + "/gmail/" + UUID.randomUUID()).cookie(admin)).andExpect(status().isNotFound());
    }

    // ------------------------------------------------------------------ gmail actions

    @Test
    void gmailRetryResetsTheRowAndEnqueuesASyncForThatUserAndConnection() throws Exception {
        for (GmailProcessedStatus from : List.of(GmailProcessedStatus.FAILED_RETRYABLE, GmailProcessedStatus.FAILED_PERMANENT,
                GmailProcessedStatus.SKIPPED_BY_ADMIN)) {
            GmailProcessedMessage g = gmail(connB, from);
            g.setAttemptCount(3);
            g.setNextRetryAt(Instant.now());
            g.setDismissedAt(Instant.now());
            g.setDismissedBy("x@example.test");
            g.setDismissReason("why");
            gmailRepository.save(g);

            JsonNode res = json(post(BASE + "/gmail/" + g.getId() + "/retry").cookie(admin), 202);
            Job job = jobRepository.findById(UUID.fromString(res.get("jobId").asText())).orElseThrow();
            assertThat(job.getType()).isEqualTo(JobType.GMAIL_SYNC);
            assertThat(job.getUserId()).isEqualTo(userB.getId());
            assertThat(job.getPayload()).contains(connB.getId().toString());
            assertThat(job.getStatus()).isEqualTo(JobStatus.PENDING);

            GmailProcessedMessage after = gmailRepository.findById(g.getId()).orElseThrow();
            assertThat(after.getStatus()).isEqualTo(GmailProcessedStatus.DISCOVERED);
            assertThat(after.getAttemptCount()).isZero();
            assertThat(after.getNextRetryAt()).isNull();
            assertThat(after.getError()).isNull();
            assertThat(after.getErrorDetail()).isNull();
            assertThat(after.getDismissedAt()).isNull();
            assertThat(after.getDismissedBy()).isNull();
            assertThat(after.getDismissReason()).isNull();
            jobRepository.delete(job);
        }
    }

    @Test
    void gmailRetryOfANonFailedRowIs409AndUnknownIs404() throws Exception {
        GmailProcessedMessage created = gmail(connA, GmailProcessedStatus.CREATED);
        mockMvc.perform(post(BASE + "/gmail/" + created.getId() + "/retry").cookie(admin)).andExpect(status().isConflict());
        assertThat(gmailRepository.findById(created.getId()).orElseThrow().getStatus()).isEqualTo(GmailProcessedStatus.CREATED);
        mockMvc.perform(post(BASE + "/gmail/" + UUID.randomUUID() + "/retry").cookie(admin)).andExpect(status().isNotFound());
    }

    @Test
    void gmailSkipMarksTheRowAndNeverBelongsToUserAttention() throws Exception {
        GmailProcessedMessage g = gmail(connA, GmailProcessedStatus.FAILED_PERMANENT);
        mockMvc.perform(post(BASE + "/gmail/" + g.getId() + "/skip").cookie(admin).contentType(MediaType.APPLICATION_JSON)
                .content("{\"reason\":\"  mail is corrupt  \"}")).andExpect(status().isNoContent());

        GmailProcessedMessage after = gmailRepository.findById(g.getId()).orElseThrow();
        assertThat(after.getStatus()).isEqualTo(GmailProcessedStatus.SKIPPED_BY_ADMIN);
        assertThat(after.getDismissedBy()).isEqualTo("failures-admin@example.test");
        assertThat(after.getDismissReason()).isEqualTo("mail is corrupt");
        assertThat(after.getDismissedAt()).isNotNull();
        assertThat(after.getNextRetryAt()).isNull();

        // not drained, not on the user's attention list or its count
        assertThat(gmailRepository.findPendingForDrain(connA.getId(), Instant.now().plusSeconds(86400), org.springframework.data.domain.PageRequest.of(0, 10))).isEmpty();
        assertThat(gmailRepository.findAttentionItems(userA.getId(), List.of(GmailProcessedStatus.UNRESOLVED_ACCOUNT,
                GmailProcessedStatus.ACCOUNT_NOT_OPTED_IN, GmailProcessedStatus.FAILED_PERMANENT),
                org.springframework.data.domain.PageRequest.of(0, 10)).getTotalElements()).isZero();
        assertThat(gmailRepository.countByUserIdAndStatusIn(userA.getId(), com.financeos.domain.notification.gmail
                .GmailAttentionNotificationService.ATTENTION_STATUSES)).isZero();
    }

    @Test
    void gmailSkipValidationAndState() throws Exception {
        GmailProcessedMessage failed = gmail(connA, GmailProcessedStatus.FAILED_RETRYABLE);
        GmailProcessedMessage created = gmail(connA, GmailProcessedStatus.CREATED);
        String url = BASE + "/gmail/" + failed.getId() + "/skip";
        mockMvc.perform(post(url).cookie(admin).contentType(MediaType.APPLICATION_JSON).content("{}")).andExpect(status().isBadRequest());
        mockMvc.perform(post(url).cookie(admin).contentType(MediaType.APPLICATION_JSON).content("{\"reason\":\"  \"}")).andExpect(status().isBadRequest());
        mockMvc.perform(post(url).cookie(admin).contentType(MediaType.APPLICATION_JSON)
                .content("{\"reason\":\"" + "x".repeat(501) + "\"}")).andExpect(status().isBadRequest());
        mockMvc.perform(post(BASE + "/gmail/" + created.getId() + "/skip").cookie(admin).contentType(MediaType.APPLICATION_JSON)
                .content("{\"reason\":\"no\"}")).andExpect(status().isConflict());
        mockMvc.perform(post(BASE + "/gmail/" + UUID.randomUUID() + "/skip").cookie(admin).contentType(MediaType.APPLICATION_JSON)
                .content("{\"reason\":\"no\"}")).andExpect(status().isNotFound());
        assertThat(gmailRepository.findById(failed.getId()).orElseThrow().getStatus()).isEqualTo(GmailProcessedStatus.FAILED_RETRYABLE);
    }

    // ------------------------------------------------------------------ job actions

    @Test
    void jobRetryClonesAcrossUsersAndDismissesTheOriginal() throws Exception {
        Job orig = failedJob(userB, JobType.PRICE_REFRESH, daysAgo(1));
        orig.setPayload("{\"x\":1}");
        jobRepository.save(orig);

        JsonNode res = json(post(BASE + "/jobs/" + orig.getId() + "/retry").cookie(admin), 202);
        UUID newId = UUID.fromString(res.get("jobId").asText());
        Job clone = jobRepository.findById(newId).orElseThrow();
        assertThat(clone.getUserId()).isEqualTo(userB.getId());
        assertThat(clone.getType()).isEqualTo(JobType.PRICE_REFRESH);
        assertThat(clone.getStatus()).isEqualTo(JobStatus.PENDING);
        assertThat(clone.getPayload()).isEqualTo("{\"x\":1}");

        Job after = jobRepository.findById(orig.getId()).orElseThrow();
        assertThat(after.getDismissedAt()).isNotNull();
        assertThat(after.getDismissedBy()).isEqualTo("failures-admin@example.test");
        assertThat(after.getDismissReason()).isEqualTo("Retried as " + newId);
    }

    @Test
    void jobRetryRejectsNonFailedExpiredAndUnknownJobs() throws Exception {
        Job pending = failedJob(userA, JobType.PRICE_REFRESH, daysAgo(1));
        pending.setStatus(JobStatus.SUCCEEDED);
        jobRepository.save(pending);
        Job expired = failedJob(userA, JobType.STATEMENT_INGEST, daysAgo(1));

        mockMvc.perform(post(BASE + "/jobs/" + pending.getId() + "/retry").cookie(admin)).andExpect(status().isConflict());
        mockMvc.perform(post(BASE + "/jobs/" + expired.getId() + "/retry").cookie(admin)).andExpect(status().isConflict());
        mockMvc.perform(post(BASE + "/jobs/" + UUID.randomUUID() + "/retry").cookie(admin)).andExpect(status().isNotFound());
        assertThat(jobRepository.findById(expired.getId()).orElseThrow().getDismissedAt()).isNull();
    }

    @Test
    void jobDismissSetsTheMarkAndValidates() throws Exception {
        Job job = failedJob(userA, JobType.PRICE_REFRESH, daysAgo(1));
        Job ok = failedJob(userA, JobType.PRICE_REFRESH, daysAgo(1));
        ok.setStatus(JobStatus.SUCCEEDED);
        jobRepository.save(ok);

        mockMvc.perform(post(BASE + "/jobs/" + job.getId() + "/dismiss").cookie(admin).contentType(MediaType.APPLICATION_JSON)
                .content("{\"reason\":\"\"}")).andExpect(status().isBadRequest());
        mockMvc.perform(post(BASE + "/jobs/" + job.getId() + "/dismiss").cookie(admin).contentType(MediaType.APPLICATION_JSON)
                .content("{\"reason\":\"known issue\"}")).andExpect(status().isNoContent());
        Job after = jobRepository.findById(job.getId()).orElseThrow();
        assertThat(after.getDismissedBy()).isEqualTo("failures-admin@example.test");
        assertThat(after.getDismissReason()).isEqualTo("known issue");
        assertThat(after.getDismissedAt()).isNotNull();
        assertThat(after.getStatus()).isEqualTo(JobStatus.FAILED);

        mockMvc.perform(post(BASE + "/jobs/" + ok.getId() + "/dismiss").cookie(admin).contentType(MediaType.APPLICATION_JSON)
                .content("{\"reason\":\"x\"}")).andExpect(status().isConflict());
        mockMvc.perform(post(BASE + "/jobs/" + UUID.randomUUID() + "/dismiss").cookie(admin).contentType(MediaType.APPLICATION_JSON)
                .content("{\"reason\":\"x\"}")).andExpect(status().isNotFound());
    }

    // ------------------------------------------------------------------ retry-all

    @Test
    void retryAllGmailResetsFailedRowsAndEnqueuesOneSyncPerUserAndConnection() throws Exception {
        GmailProcessedMessage a1 = gmail(connA, GmailProcessedStatus.FAILED_RETRYABLE);
        GmailProcessedMessage a2 = gmail(connA, GmailProcessedStatus.FAILED_PERMANENT);
        GmailProcessedMessage b1 = gmail(connB, GmailProcessedStatus.FAILED_PERMANENT);
        GmailProcessedMessage skipped = gmail(connB, GmailProcessedStatus.SKIPPED_BY_ADMIN);
        GmailProcessedMessage created = gmail(connB, GmailProcessedStatus.CREATED);

        mockMvc.perform(post(BASE + "/retry-all").cookie(admin).contentType(MediaType.APPLICATION_JSON).content("{\"kind\":\"gmail\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.affected").value(3))
                .andExpect(jsonPath("$.skipped").value(0))
                .andExpect(jsonPath("$.jobsEnqueued").value(2));

        for (GmailProcessedMessage g : List.of(a1, a2, b1)) {
            assertThat(gmailRepository.findById(g.getId()).orElseThrow().getStatus()).isEqualTo(GmailProcessedStatus.DISCOVERED);
        }
        assertThat(gmailRepository.findById(skipped.getId()).orElseThrow().getStatus()).isEqualTo(GmailProcessedStatus.SKIPPED_BY_ADMIN);
        assertThat(gmailRepository.findById(created.getId()).orElseThrow().getStatus()).isEqualTo(GmailProcessedStatus.CREATED);

        List<Job> syncs = jobRepository.findAll().stream().filter(j -> j.getType() == JobType.GMAIL_SYNC).toList();
        assertThat(syncs).hasSize(2);
        assertThat(syncs.stream().map(Job::getUserId)).containsExactlyInAnyOrder(userA.getId(), userB.getId());
    }

    @Test
    void retryAllJobsRetriesRetryableOnesAndSkipsExpiredAndDismissed() throws Exception {
        Job a = failedJob(userA, JobType.PRICE_REFRESH, daysAgo(1));
        Job b = failedJob(userB, JobType.RULE_APPLY, daysAgo(2));
        Job expired = failedJob(userA, JobType.STATEMENT_INGEST, daysAgo(1));
        Job withFile = failedJob(userB, JobType.STATEMENT_INGEST, daysAgo(1));
        addArtifact(withFile);
        Job dismissed = failedJob(userA, JobType.PRICE_REFRESH, daysAgo(1));
        dismissed.setDismissedAt(Instant.now());
        jobRepository.save(dismissed);
        failedJob(userA, JobType.PRICE_REFRESH, daysAgo(40)); // outside the window

        mockMvc.perform(post(BASE + "/retry-all").cookie(admin).contentType(MediaType.APPLICATION_JSON).content("{\"kind\":\"jobs\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.affected").value(3))
                .andExpect(jsonPath("$.skipped").value(1))
                .andExpect(jsonPath("$.jobsEnqueued").value(3));

        for (Job j : List.of(a, b, withFile)) {
            assertThat(jobRepository.findById(j.getId()).orElseThrow().getDismissReason()).startsWith("Retried as ");
        }
        assertThat(jobRepository.findById(expired.getId()).orElseThrow().getDismissedAt()).isNull();
        long pending = jobRepository.findAll().stream().filter(j -> j.getStatus() == JobStatus.PENDING).count();
        assertThat(pending).isEqualTo(3);
    }

    @Test
    void retryAllRejectsAMissingKind() throws Exception {
        mockMvc.perform(post(BASE + "/retry-all").cookie(admin).contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isBadRequest());
        mockMvc.perform(post(BASE + "/retry-all").cookie(admin).contentType(MediaType.APPLICATION_JSON).content("{\"kind\":\"other\"}"))
                .andExpect(status().isBadRequest());
    }

    // ------------------------------------------------------------------ helpers

    private JsonNode json(org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder req) throws Exception {
        return json(req, 200);
    }

    private JsonNode json(org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder req, int expected) throws Exception {
        String body = mockMvc.perform(req).andExpect(status().is(expected)).andReturn().getResponse().getContentAsString();
        return mapper.readTree(body);
    }

    private static boolean retryableOf(JsonNode list, Job job) {
        return rowOf(list, "id", job.getId()).get("retryable").asBoolean();
    }

    private static JsonNode rowOf(JsonNode list, String idField, UUID id) {
        for (JsonNode row : list.get("content")) {
            if (row.get(idField).asText().equals(id.toString())) {
                return row;
            }
        }
        throw new AssertionError("row not in list: " + id);
    }

    private static List<String> ids(JsonNode list, String idField) {
        List<String> out = new ArrayList<>();
        list.get("content").forEach(r -> out.add(r.get(idField).asText()));
        return out;
    }

    private static Instant daysAgo(int days) {
        return AppTime.nowInstant().minus(days, ChronoUnit.DAYS);
    }

    private Job failedJob(User owner, JobType type, Instant finishedAt) {
        Job j = new Job();
        j.setUserId(owner.getId());
        j.setType(type);
        j.setStatus(JobStatus.FAILED);
        j.setTriggerSource(JobTrigger.USER);
        j.setErrorCode("IllegalStateException");
        j.setErrorMessage("boom");
        j.setErrorDetail("java.lang.IllegalStateException: boom\n\tat Foo.bar(Foo.java:1)");
        j.setAttempt(1);
        j.setStartedAt(finishedAt.minusSeconds(5));
        j.setFinishedAt(finishedAt);
        return jobRepository.save(j);
    }

    private void addArtifact(Job job) {
        JobArtifact a = new JobArtifact();
        a.setJobId(job.getId());
        a.setFilename("s.pdf");
        a.setContentType("application/pdf");
        a.setSizeBytes(3);
        a.setData(new byte[] {1, 2, 3});
        artifactRepository.save(a);
    }

    private GmailProcessedMessage gmail(GmailConnection conn, GmailProcessedStatus status) {
        GmailProcessedMessage m = new GmailProcessedMessage();
        m.setConnection(conn);
        m.setUser(conn.getUser());
        m.setGmailMessageId("msg-" + UUID.randomUUID());
        m.setStatus(status);
        m.setSenderAddress("alerts@bank.test");
        m.setSubject("Alert");
        m.setError("parse failed");
        m.setErrorDetail("java.lang.IllegalStateException: boom\n\tat Foo.bar(Foo.java:1)");
        m.setAttemptCount(1);
        m.setProcessedAt(Instant.now());
        return gmailRepository.save(m);
    }

    private User saveUser(String prefix) {
        User u = new User();
        u.setDisplayName(prefix);
        u.setEmail(prefix + "-" + UUID.randomUUID() + "@example.test");
        u.setPasswordHash("hash");
        return userRepository.save(u);
    }

    private GmailConnection saveConnection(User owner) {
        GmailConnection c = new GmailConnection();
        c.setUser(owner);
        c.setEmail("mailbox-" + owner.getEmail());
        c.setEncryptedRefreshToken("refresh-token");
        return connectionRepository.save(c);
    }
}
