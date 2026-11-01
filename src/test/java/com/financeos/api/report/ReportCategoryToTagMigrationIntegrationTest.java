package com.financeos.api.report;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.financeos.domain.report.Report;
import com.financeos.domain.report.ReportRepository;
import com.financeos.domain.report.ReportType;
import com.financeos.domain.user.User;
import com.financeos.domain.user.UserRepository;
import com.financeos.support.ApiTestClient;
import com.financeos.support.UserDataCleanup;
import db.migration.V121__report_category_to_tag;
import org.flywaydb.core.api.migration.Context;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

import javax.sql.DataSource;
import java.sql.Connection;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** V121 over real rows: only transactions reports that used the old category field move to tag, and a re-run is a no-op. */
@SpringBootTest
@AutoConfigureMockMvc
class ReportCategoryToTagMigrationIntegrationTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper mapper;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private DataSource dataSource;
    @Autowired private UserRepository userRepository;
    @Autowired private ReportRepository reportRepository;

    private UUID userId;

    @AfterEach
    void tearDown() {
        if (userId != null) {
            UserDataCleanup.deleteUsers(jdbc, List.of(userId));
        }
    }

    @Test
    void rewritesOldCategoryReportsToTagLeavesTheRestAndIsIdempotent() throws Exception {
        String email = "v121-" + UUID.randomUUID() + "@example.test";
        ApiTestClient.signUp(mockMvc, mapper, email);
        User user = userRepository.findByEmail(email).orElseThrow();
        userId = user.getId();
        String old = "{\"chartType\":\"bar\",\"dimension\":{\"field\":\"category\"},"
                + "\"measure\":{\"field\":\"spend\",\"aggregation\":\"sum\"},"
                + "\"filters\":[{\"field\":\"category\",\"operator\":\"in\",\"value\":[\"Dining\"]}]}";
        String fresh = "{\"chartType\":\"bar\",\"dimension\":{\"field\":\"account\"},"
                + "\"measure\":{\"field\":\"spend\",\"aggregation\":\"sum\"},\"filters\":[]}";
        UUID oldId = reportRepository.save(new Report(user, "Old", ReportType.CHART, "transactions", old)).getId();
        UUID freshId = reportRepository.save(new Report(user, "Fresh", ReportType.CHART, "transactions", fresh)).getId();
        UUID rewardId = reportRepository.save(new Report(user, "Reward", ReportType.CHART, "reward_earnings", old)).getId();

        migrate();

        String rewritten = definition(oldId);
        assertTrue(rewritten.contains("\"dimension\":{\"field\":\"tag\"}"), rewritten);
        assertTrue(rewritten.contains("{\"field\":\"tag\",\"operator\":\"in\",\"value\":[\"Dining\"]}"), rewritten);
        assertEquals(fresh, definition(freshId));
        assertEquals(old, definition(rewardId), "other datasources keep their own category field");

        migrate();

        assertEquals(rewritten, definition(oldId), "a second run changes nothing");
        assertEquals(fresh, definition(freshId));
    }

    private void migrate() throws Exception {
        try (Connection c = dataSource.getConnection()) {
            Context context = mock(Context.class);
            when(context.getConnection()).thenReturn(c);
            new V121__report_category_to_tag().migrate(context);
        }
    }

    private String definition(UUID id) {
        return jdbc.queryForObject("SELECT definition FROM reports WHERE id = ?", String.class, id.toString());
    }
}
