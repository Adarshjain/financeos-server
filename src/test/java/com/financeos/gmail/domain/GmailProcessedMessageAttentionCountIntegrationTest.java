package com.financeos.gmail.domain;

import com.financeos.domain.notification.gmail.GmailAttentionNotificationService;
import com.financeos.domain.user.User;
import com.financeos.domain.user.UserRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * {@link GmailProcessedMessageRepository#countByUserIdAndStatusIn} (the Inbox's Gmail attention
 * count) on real rows: every attention-status row of the user counts whether or not a digest
 * already told them, other statuses and other users never do. No UserContext is set, so the
 * Hibernate userFilter cannot mask a missing user predicate.
 */
@SpringBootTest
class GmailProcessedMessageAttentionCountIntegrationTest {

    @Autowired private UserRepository userRepository;
    @Autowired private GmailConnectionRepository connectionRepository;
    @Autowired private GmailProcessedMessageRepository messageRepository;

    private User user;
    private User otherUser;
    private GmailConnection connection;
    private GmailConnection otherConnection;
    private final List<UUID> messageIds = new ArrayList<>();

    @BeforeEach
    void setUp() {
        user = saveUser("gmail-count");
        otherUser = saveUser("gmail-count-other");
        connection = saveConnection(user);
        otherConnection = saveConnection(otherUser);
    }

    @AfterEach
    void tearDown() {
        messageRepository.deleteAllById(messageIds);
        connectionRepository.deleteAll(List.of(connection, otherConnection));
        userRepository.deleteAll(List.of(user, otherUser));
    }

    @Test
    void countsEveryAttentionStatus_notifiedOrNot() {
        save(connection, GmailProcessedStatus.UNRESOLVED_ACCOUNT, null);
        save(connection, GmailProcessedStatus.ACCOUNT_NOT_OPTED_IN, Instant.now());
        save(connection, GmailProcessedStatus.FAILED_PERMANENT, null);
        save(connection, GmailProcessedStatus.FAILED_PERMANENT, Instant.now());

        assertEquals(4, attentionCount(user));
    }

    @Test
    void ignoresEveryNonAttentionStatus() {
        for (GmailProcessedStatus status : EnumSet.complementOf(EnumSet.copyOf(GmailAttentionNotificationService.ATTENTION_STATUSES))) {
            save(connection, status, null);
        }
        save(connection, GmailProcessedStatus.UNRESOLVED_ACCOUNT, null);

        assertEquals(1, attentionCount(user));
    }

    @Test
    void neverCountsAnotherUsersRows() {
        save(otherConnection, GmailProcessedStatus.UNRESOLVED_ACCOUNT, null);
        save(otherConnection, GmailProcessedStatus.FAILED_PERMANENT, Instant.now());

        assertEquals(0, attentionCount(user));
        assertEquals(2, attentionCount(otherUser));
    }

    @Test
    void zeroWhenTheUserHasNoMessages() {
        assertEquals(0, attentionCount(user));
    }

    // ------------------------------------------------------------------ helpers

    private long attentionCount(User owner) {
        return messageRepository.countByUserIdAndStatusIn(owner.getId(), GmailAttentionNotificationService.ATTENTION_STATUSES);
    }

    private void save(GmailConnection conn, GmailProcessedStatus status, Instant notifiedAt) {
        GmailProcessedMessage m = new GmailProcessedMessage();
        m.setConnection(conn);
        m.setUser(conn.getUser());
        m.setGmailMessageId("msg-" + UUID.randomUUID());
        m.setStatus(status);
        m.setAttentionNotifiedAt(notifiedAt);
        messageIds.add(messageRepository.save(m).getId());
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
        c.setEmail(owner.getEmail());
        c.setEncryptedRefreshToken("refresh-token");
        return connectionRepository.save(c);
    }
}
