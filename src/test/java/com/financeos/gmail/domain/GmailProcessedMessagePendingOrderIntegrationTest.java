package com.financeos.gmail.domain;

import com.financeos.domain.user.User;
import com.financeos.domain.user.UserRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.domain.PageRequest;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link GmailProcessedMessageRepository#findPendingForDrain} on real rows: every mail with an attachment (or a known
 * statement) comes before any alert across the whole backlog, not just within one page; then by mail date, nulls last.
 */
@SpringBootTest
class GmailProcessedMessagePendingOrderIntegrationTest {

    @Autowired private UserRepository userRepository;
    @Autowired private GmailConnectionRepository connectionRepository;
    @Autowired private GmailProcessedMessageRepository messageRepository;

    private User user;
    private GmailConnection connection;
    private final List<UUID> messageIds = new ArrayList<>();
    private final Instant t0 = Instant.parse("2026-10-01T04:30:00Z");

    @BeforeEach
    void setUp() {
        user = new User();
        user.setDisplayName("pending-order");
        user.setEmail("pending-order-" + UUID.randomUUID() + "@example.test");
        user.setPasswordHash("hash");
        user = userRepository.save(user);
        connection = new GmailConnection();
        connection.setUser(user);
        connection.setEmail(user.getEmail());
        connection.setEncryptedRefreshToken("refresh-token");
        connection = connectionRepository.save(connection);
    }

    @AfterEach
    void tearDown() {
        messageRepository.deleteAllById(messageIds);
        connectionRepository.delete(connection);
        userRepository.delete(user);
    }

    @Test
    void attachmentAndStatementMailsComeBeforeEveryAlertAcrossTheBacklog() {
        save("alert-old", false, null, t0);
        save("alert-new", false, null, t0.plusSeconds(3600));
        save("alert-undated", false, null, null);
        save("statement-late", true, null, t0.plusSeconds(86400 * 30));
        save("statement-known", false, "STATEMENT", t0.plusSeconds(86400 * 10));

        List<String> firstPage = ids(PageRequest.of(0, 2));
        assertThat(firstPage).containsExactly("statement-known", "statement-late");

        assertThat(ids(PageRequest.of(0, 10)))
                .containsExactly("statement-known", "statement-late", "alert-old", "alert-new", "alert-undated");
    }

    private List<String> ids(PageRequest page) {
        return messageRepository.findPendingForDrain(connection.getId(), Instant.now(), page).stream()
                .map(GmailProcessedMessage::getGmailMessageId).toList();
    }

    private void save(String id, boolean hasAttachment, String pipelineKind, Instant internalDate) {
        GmailProcessedMessage m = new GmailProcessedMessage();
        m.setConnection(connection);
        m.setUser(user);
        m.setGmailMessageId(id);
        m.setStatus(GmailProcessedStatus.DISCOVERED);
        m.setHasAttachment(hasAttachment);
        m.setPipelineKind(pipelineKind);
        m.setInternalDate(internalDate);
        messageIds.add(messageRepository.save(m).getId());
    }
}
