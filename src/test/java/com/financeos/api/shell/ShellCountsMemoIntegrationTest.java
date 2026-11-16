package com.financeos.api.shell;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.financeos.core.stamp.UserChangeStamps;
import com.financeos.domain.category.Tag;
import com.financeos.domain.category.TagRepository;
import com.financeos.domain.job.JobWorker;
import com.financeos.domain.user.User;
import com.financeos.domain.user.UserRepository;
import com.financeos.support.ApiTestClient;
import com.financeos.support.UserDataCleanup;
import jakarta.persistence.EntityManagerFactory;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.hibernate.SessionFactory;
import org.hibernate.stat.Statistics;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.SpyBean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** GET /shell/counts is served from the memo while the user's change stamp is unchanged, in a handful of statements. */
@SpringBootTest
@AutoConfigureMockMvc
class ShellCountsMemoIntegrationTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper mapper;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private UserRepository userRepository;
    @Autowired private TagRepository tagRepository;
    @Autowired private EntityManagerFactory emf;
    @Autowired private JobWorker jobWorker;
    @Autowired private PlatformTransactionManager transactionManager;
    @SpyBean private UserChangeStamps stamps;

    private ApiTestClient api;
    private UUID userId;

    @BeforeEach
    void setUp() throws Exception {
        String email = "shell-memo-" + UUID.randomUUID() + "@example.test";
        api = ApiTestClient.signUp(mockMvc, mapper, email);
        userId = userRepository.findByEmail(email).orElseThrow().getId();
    }

    @AfterEach
    void tearDown() {
        UserDataCleanup.deleteUsers(jdbc, List.of(userId));
    }

    private record Run(JsonNode body, long statements) {}

    /** Hibernate statements plus the stamp reads (plain SQL, not in Hibernate's count) of one call. */
    private Run counts() throws Exception {
        Statistics stats = emf.unwrap(SessionFactory.class).getStatistics();
        stats.setStatisticsEnabled(true);
        jobWorker.pause();
        try {
            stats.clear();
            Mockito.clearInvocations(stamps);
            JsonNode body = api.getJson("/api/v1/shell/counts", 200);
            long reads = Mockito.mockingDetails(stamps).getInvocations().stream()
                    .filter(i -> i.getMethod().getName().equals("version")).count();
            return new Run(body, stats.getPrepareStatementCount() + reads);
        } finally {
            jobWorker.resume();
        }
    }

    @Test
    void aWarmCallIsAFewStatementsAndReturnsWhatTheColdCallDid() throws Exception {
        Run cold = counts();
        Run warm = counts();

        assertEquals(cold.body(), warm.body());
        assertTrue(warm.statements() <= 6, "warm shell/counts ran " + warm.statements() + " statements");
        assertTrue(cold.statements() > warm.statements() + 10,
                "cold " + cold.statements() + " vs warm " + warm.statements());
        System.out.println("SHELL cold=" + cold.statements() + " warm=" + warm.statements());
    }

    @Test
    void aChangeMadeOutsideAnyHttpRequestMakesTheNextCallRebuild() throws Exception {
        counts();
        Run warm = counts();

        // What the worker JVM does: write the user's data in its own transaction, no request, no memo eviction.
        User owner = userRepository.findById(userId).orElseThrow();
        new TransactionTemplate(transactionManager).executeWithoutResult(
                s -> tagRepository.save(new Tag("worker-made", owner)));

        Run afterChange = counts();
        assertTrue(afterChange.statements() > warm.statements() + 10,
                "expected a rebuild, ran " + afterChange.statements() + " statements");
        assertEquals(warm.body(), afterChange.body());
        assertTrue(counts().statements() <= 6);
    }

    @Test
    void aWriteOverHttpMakesTheNextCallRebuild() throws Exception {
        counts();
        Run warm = counts();

        api.create("/api/v1/tags", Map.of("name", "http-made"));

        assertTrue(counts().statements() > warm.statements() + 10);
    }
}
