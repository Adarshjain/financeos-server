package com.financeos.core.stamp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.financeos.domain.category.Tag;
import com.financeos.domain.category.TagRepository;
import com.financeos.domain.user.User;
import com.financeos.domain.user.UserRepository;
import com.financeos.support.UserDataCleanup;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** The change stamp moves once per user per committed transaction that writes the user's data, and never on rollback. */
@SpringBootTest
class UserChangeStampsIntegrationTest {

    @Autowired private UserChangeStamps stamps;
    @Autowired private UserRepository userRepository;
    @Autowired private TagRepository tagRepository;
    @Autowired private com.financeos.domain.dashboard.DashboardRepository dashboards;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private PlatformTransactionManager transactionManager;

    private TransactionTemplate tx;
    private User alice;
    private User bob;

    @BeforeEach
    void setUp() {
        tx = new TransactionTemplate(transactionManager);
        alice = user("stamp-a-" + UUID.randomUUID() + "@example.test");
        bob = user("stamp-b-" + UUID.randomUUID() + "@example.test");
    }

    @AfterEach
    void tearDown() {
        UserDataCleanup.deleteUsers(jdbc, List.of(alice.getId(), bob.getId()));
    }

    private User user(String email) {
        User u = new User();
        u.setEmail(email);
        u.setDisplayName("Stamp Test");
        u.setPasswordHash("x");
        return tx.execute(s -> userRepository.saveAndFlush(u));
    }

    private long version(User u) {
        return stamps.version(u.getId());
    }

    private UUID saveTag(User owner, String name) {
        return tx.execute(s -> tagRepository.saveAndFlush(new Tag(name, owner)).getId());
    }

    @Test
    void anInsertAnUpdateAndADeleteEachMoveTheOwnersStampOnce() {
        long start = version(alice);

        UUID tag = saveTag(alice, "one");
        assertEquals(start + 1, version(alice));

        tx.executeWithoutResult(s -> tagRepository.findById(tag).orElseThrow().setName("renamed"));
        assertEquals(start + 2, version(alice));

        tx.executeWithoutResult(s -> tagRepository.deleteById(tag));
        assertEquals(start + 3, version(alice));
    }

    @Test
    void severalWritesForTheSameUserInOneTransactionMoveItOnceAndOnlyThatUser() {
        long aliceStart = version(alice);
        long bobStart = version(bob);

        tx.executeWithoutResult(s -> {
            tagRepository.saveAndFlush(new Tag("a1", alice));
            tagRepository.saveAndFlush(new Tag("a2", alice));
            tagRepository.saveAndFlush(new Tag("a3", alice));
        });

        assertEquals(aliceStart + 1, version(alice));
        assertEquals(bobStart, version(bob));
    }

    @Test
    void aTransactionTouchingTwoUsersMovesBoth() {
        long aliceStart = version(alice);
        long bobStart = version(bob);

        tx.executeWithoutResult(s -> {
            tagRepository.saveAndFlush(new Tag("a", alice));
            tagRepository.saveAndFlush(new Tag("b", bob));
        });

        assertEquals(aliceStart + 1, version(alice));
        assertEquals(bobStart + 1, version(bob));
    }

    @Test
    void nothingMovesBeforeCommitAndNothingOnRollback() {
        long start = version(alice);

        tx.executeWithoutResult(s -> {
            tagRepository.saveAndFlush(new Tag("pending", alice));
            assertEquals(start, version(alice), "bumped before the commit");
        });
        assertEquals(start + 1, version(alice));

        assertThrows(IllegalStateException.class, () -> tx.executeWithoutResult(s -> {
            tagRepository.saveAndFlush(new Tag("doomed", alice));
            throw new IllegalStateException("boom");
        }));
        assertEquals(start + 1, version(alice));
    }

    @Test
    void aReadOnlyTransactionDoesNotMoveTheStamp() {
        saveTag(alice, "kept");
        long start = version(alice);

        tx.executeWithoutResult(s -> tagRepository.findByUserId(alice.getId()));

        assertEquals(start, version(alice));
    }

    @Test
    void theBumpFailingNeverFailsTheBusinessTransaction() {
        jdbc.execute("ALTER TABLE user_change_stamp RENAME TO user_change_stamp_off");
        try {
            UUID tag = saveTag(alice, "survives");
            assertEquals(true, tagRepository.findById(tag).isPresent());
        } finally {
            jdbc.execute("ALTER TABLE user_change_stamp_off RENAME TO user_change_stamp");
        }
    }

    @Test
    void aMissingStampReadsAsZeroAndAnUnknownUserIsNotStamped() {
        UUID ghost = UUID.randomUUID();
        assertEquals(0, stamps.version(ghost));
        stamps.bumpNow(ghost);
        assertEquals(0, stamps.version(ghost));
    }

    @Test
    void aBulkModifyingQueryTakingTheUserIdStampsThatUser() {
        saveTag(alice, "x");
        long start = version(alice);

        // Dashboard.clearDefaultForUser is a bulk @Modifying JPQL update with a userId parameter.
        tx.executeWithoutResult(s -> dashboards.clearDefaultForUser(alice.getId()));

        assertEquals(start + 1, version(alice));
    }

}
