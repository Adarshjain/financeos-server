package com.financeos.domain.inbox;

import com.financeos.domain.user.User;
import com.financeos.domain.user.UserRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;

import java.time.LocalDate;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link InboxItemStateRepository} on real rows: the finders are scoped to the user and the exact
 * item key, and the unique (user_id, item_key) constraint allows one state row per user and key
 * while letting two users hold the same key. No UserContext is set, so the Hibernate userFilter
 * cannot mask a missing user predicate.
 */
@SpringBootTest
class InboxItemStateRepositoryIntegrationTest {

    @Autowired private InboxItemStateRepository repository;
    @Autowired private UserRepository userRepository;

    private User user;
    private User otherUser;

    @BeforeEach
    void setUp() {
        user = saveUser("inbox-state");
        otherUser = saveUser("inbox-state-other");
    }

    @AfterEach
    void tearDown() {
        repository.deleteAll(repository.findByUserId(user.getId()));
        repository.deleteAll(repository.findByUserId(otherUser.getId()));
        userRepository.deleteAll(List.of(user, otherUser));
    }

    @Test
    void findByUserId_returnsOnlyThatUsersStates() {
        repository.save(new InboxItemState(user.getId(), "bill:1"));
        repository.save(new InboxItemState(user.getId(), "emi:2"));
        repository.save(new InboxItemState(otherUser.getId(), "bill:9"));

        Set<String> keys = repository.findByUserId(user.getId()).stream()
                .map(InboxItemState::getItemKey).collect(Collectors.toSet());

        assertEquals(Set.of("bill:1", "emi:2"), keys);
        assertTrue(repository.findByUserId(UUID.randomUUID()).isEmpty());
    }

    @Test
    void findByUserIdAndItemKey_matchesTheExactKeyOfThatUserOnly() {
        InboxItemState mine = new InboxItemState(user.getId(), "bill:1");
        mine.setSnoozedUntil(LocalDate.of(2026, 10, 20));
        repository.save(mine);
        repository.save(new InboxItemState(otherUser.getId(), "bill:2"));

        InboxItemState found = repository.findByUserIdAndItemKey(user.getId(), "bill:1").orElseThrow();
        assertEquals(LocalDate.of(2026, 10, 20), found.getSnoozedUntil());
        assertTrue(repository.findByUserIdAndItemKey(user.getId(), "bill:12").isEmpty());
        assertTrue(repository.findByUserIdAndItemKey(user.getId(), "BILL:1").isEmpty());
        assertTrue(repository.findByUserIdAndItemKey(user.getId(), "bill:2").isEmpty(), "another user's key");
    }

    @Test
    void uniqueConstraint_rejectsASecondRowForTheSameUserAndKey() {
        repository.saveAndFlush(new InboxItemState(user.getId(), "bill:1"));

        assertThrows(DataIntegrityViolationException.class,
                () -> repository.saveAndFlush(new InboxItemState(user.getId(), "bill:1")));
        assertEquals(1, repository.findByUserId(user.getId()).size());
    }

    @Test
    void uniqueConstraint_letsTwoUsersHoldTheSameKey() {
        repository.saveAndFlush(new InboxItemState(user.getId(), "bill:1"));
        repository.saveAndFlush(new InboxItemState(otherUser.getId(), "bill:1"));

        assertTrue(repository.findByUserIdAndItemKey(user.getId(), "bill:1").isPresent());
        assertTrue(repository.findByUserIdAndItemKey(otherUser.getId(), "bill:1").isPresent());
    }

    private User saveUser(String prefix) {
        User u = new User();
        u.setDisplayName(prefix);
        u.setEmail(prefix + "-" + UUID.randomUUID() + "@example.test");
        u.setPasswordHash("hash");
        return userRepository.save(u);
    }
}
