package com.financeos.domain.report;

import com.financeos.domain.user.User;
import com.financeos.domain.user.UserRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link ReportRepository#findFirstByUser_IdAndNameAndDatasourceOrderByCreatedAtAsc} (how the Home
 * seeder and Restore reuse an earlier "Spend this month" report) on real rows: the user's oldest
 * exact name + datasource match, never another user's report or a near match. No UserContext is
 * set, so the Hibernate userFilter cannot mask a missing user predicate.
 */
@SpringBootTest
class ReportRepositoryFindFirstIntegrationTest {

    private static final String NAME = "Spend this month";
    private static final String DATASOURCE = "transactions";
    private static final String DEFINITION = "{\"chartType\":\"bar\"}";

    @Autowired private ReportRepository reportRepository;
    @Autowired private UserRepository userRepository;

    private User user;
    private User otherUser;
    private final List<UUID> reportIds = new ArrayList<>();

    @BeforeEach
    void setUp() {
        user = saveUser("report-first");
        otherUser = saveUser("report-first-other");
    }

    @AfterEach
    void tearDown() {
        reportRepository.deleteAllById(reportIds);
        userRepository.deleteAll(List.of(user, otherUser));
    }

    @Test
    void returnsTheOldestExactMatch_evenWhenSavedLast() {
        Instant now = Instant.now().truncatedTo(ChronoUnit.MILLIS);
        Report newer = save(user, NAME, DATASOURCE, now);
        Report oldest = save(user, NAME, DATASOURCE, now.minus(2, ChronoUnit.DAYS));

        Optional<Report> found = find(user);

        assertTrue(found.isPresent());
        assertEquals(oldest.getId(), found.get().getId());
        assertTrue(!newer.getId().equals(found.get().getId()));
    }

    @Test
    void ignoresOtherUsersOtherNamesAndOtherDatasources() {
        Instant now = Instant.now();
        save(otherUser, NAME, DATASOURCE, now.minus(5, ChronoUnit.DAYS));
        save(user, NAME + " ", DATASOURCE, now.minus(4, ChronoUnit.DAYS));
        save(user, "spend this month", DATASOURCE, now.minus(3, ChronoUnit.DAYS));
        save(user, NAME, "net_worth", now.minus(2, ChronoUnit.DAYS));

        assertTrue(find(user).isEmpty());

        Report mine = save(user, NAME, DATASOURCE, now);
        assertEquals(mine.getId(), find(user).orElseThrow().getId());
    }

    @Test
    void emptyWhenTheUserHasNoReports() {
        assertTrue(find(user).isEmpty());
    }

    private Optional<Report> find(User owner) {
        return reportRepository.findFirstByUser_IdAndNameAndDatasourceOrderByCreatedAtAsc(owner.getId(), NAME, DATASOURCE);
    }

    private Report save(User owner, String name, String datasource, Instant createdAt) {
        Report r = new Report(owner, name, ReportType.CHART, datasource, DEFINITION);
        r.setCreatedAt(createdAt);
        Report saved = reportRepository.save(r);
        reportIds.add(saved.getId());
        return saved;
    }

    private User saveUser(String prefix) {
        User u = new User();
        u.setDisplayName(prefix);
        u.setEmail(prefix + "-" + UUID.randomUUID() + "@example.test");
        u.setPasswordHash("hash");
        return userRepository.save(u);
    }
}
