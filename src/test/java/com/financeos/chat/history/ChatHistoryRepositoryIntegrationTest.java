package com.financeos.chat.history;

import com.financeos.domain.user.User;
import com.financeos.domain.user.UserRepository;
import jakarta.persistence.EntityManager;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.domain.PageRequest;
import org.springframework.transaction.annotation.Transactional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The JPQL of {@link ChatConversationRepository} and {@link ChatMessageRepository} on real rows. Rolled back
 * after each test; no UserContext is set, so the userFilter cannot mask a missing user predicate.
 */
@SpringBootTest
@Transactional
class ChatHistoryRepositoryIntegrationTest {

    @Autowired private ChatConversationRepository conversations;
    @Autowired private ChatMessageRepository messages;
    @Autowired private UserRepository userRepository;
    @Autowired private EntityManager em;

    private User saveUser(String prefix) {
        User u = new User();
        u.setDisplayName(prefix);
        u.setEmail(prefix + "-" + UUID.randomUUID() + "@example.test");
        u.setPasswordHash("hash");
        return userRepository.save(u);
    }

    private ChatConversation conv(User user, Instant updatedAt, String title) {
        ChatConversation c = new ChatConversation();
        c.setUserId(user.getId());
        c.setTitle(title);
        c.setCreatedAt(updatedAt);
        c.setUpdatedAt(updatedAt);
        return conversations.saveAndFlush(c);
    }

    private void msg(ChatConversation c, int seq, String role) {
        ChatMessageRecord m = new ChatMessageRecord();
        m.setConversationId(c.getId());
        m.setSeq(seq);
        m.setRole(role);
        m.setContent("x");
        messages.saveAndFlush(m);
    }

    @Test
    void summariesAreNewestFirstWithQuestionCountsAndPaged() {
        User user = saveUser("chat-sum");
        User other = saveUser("chat-sum-other");
        ChatConversation old = conv(user, Instant.parse("2026-10-01T00:00:00Z"), "Old");
        ChatConversation recent = conv(user, Instant.parse("2026-10-02T00:00:00Z"), "Recent");
        conv(other, Instant.parse("2026-10-03T00:00:00Z"), "Theirs");
        msg(recent, 1, "USER");
        msg(recent, 2, "ASSISTANT");
        msg(recent, 3, "USER");
        msg(old, 1, "USER");

        List<Object[]> all = conversations.findSummaries(user.getId(), PageRequest.of(0, 10));
        assertEquals(2, all.size());
        assertEquals(recent.getId(), all.get(0)[0]);
        assertEquals("Recent", all.get(0)[1]);
        assertEquals(2L, ((Number) all.get(0)[4]).longValue());
        assertEquals(old.getId(), all.get(1)[0]);
        assertEquals(1L, ((Number) all.get(1)[4]).longValue());

        List<Object[]> page = conversations.findSummaries(user.getId(), PageRequest.of(0, 1));
        assertEquals(1, page.size());
        assertEquals(recent.getId(), page.get(0)[0]);
        assertEquals(List.of(recent.getId(), old.getId()), conversations.findIdsNewestFirst(user.getId()));
    }

    @Test
    void deleteByUserAndIdsRemovesOnlyThatUsersListedConversations() {
        User user = saveUser("chat-del");
        User other = saveUser("chat-del-other");
        ChatConversation a = conv(user, Instant.parse("2026-10-01T00:00:00Z"), "A");
        ChatConversation b = conv(user, Instant.parse("2026-10-02T00:00:00Z"), "B");
        ChatConversation c = conv(user, Instant.parse("2026-10-03T00:00:00Z"), "C");
        ChatConversation foreign = conv(other, Instant.parse("2026-10-04T00:00:00Z"), "F");

        int n = conversations.deleteByUserAndIds(user.getId(), List.of(a.getId(), b.getId(), foreign.getId()));
        em.flush();
        em.clear();

        assertEquals(2, n);
        assertFalse(conversations.findById(a.getId()).isPresent());
        assertFalse(conversations.findById(b.getId()).isPresent());
        assertTrue(conversations.findById(c.getId()).isPresent());
        assertTrue(conversations.findById(foreign.getId()).isPresent());
    }

    @Test
    void touchAndSetTitleIfNullUpdateTheRow() {
        User user = saveUser("chat-upd");
        ChatConversation untitled = conv(user, Instant.parse("2026-10-01T00:00:00Z"), null);
        ChatConversation titled = conv(user, Instant.parse("2026-10-01T00:00:00Z"), "Keep");
        Instant now = Instant.parse("2026-10-20T10:00:00Z");

        assertEquals(1, conversations.touch(untitled.getId(), now));
        assertEquals(1, conversations.setTitleIfNull(untitled.getId(), "Fresh"));
        assertEquals(0, conversations.setTitleIfNull(titled.getId(), "Other"));
        em.clear();

        ChatConversation u = conversations.findById(untitled.getId()).orElseThrow();
        assertEquals("Fresh", u.getTitle());
        assertEquals(now, u.getUpdatedAt());
        assertEquals("Keep", conversations.findById(titled.getId()).orElseThrow().getTitle());
    }

    @Test
    void maxSeqAndDeleteAfterSeq() {
        User user = saveUser("chat-seq");
        ChatConversation c = conv(user, Instant.parse("2026-10-01T00:00:00Z"), null);
        assertEquals(0, messages.maxSeq(c.getId()));

        msg(c, 1, "USER");
        msg(c, 2, "ASSISTANT");
        msg(c, 3, "USER");
        assertEquals(3, messages.maxSeq(c.getId()));

        assertEquals(2, messages.deleteAfterSeq(c.getId(), 1));
        em.clear();
        assertEquals(1, messages.maxSeq(c.getId()));
    }

    @Test
    void lockByIdReturnsTheConversationAndNothingForUnknownIds() {
        User user = saveUser("chat-lock");
        ChatConversation c = conv(user, Instant.parse("2026-10-01T00:00:00Z"), null);

        UUID run = UUID.randomUUID();
        conversations.lockById(c.getId()).orElseThrow().setActiveRunId(run);
        em.flush();
        em.clear();

        assertEquals(run, conversations.lockById(c.getId()).orElseThrow().getActiveRunId());
        assertTrue(conversations.lockById(UUID.randomUUID()).isEmpty());
    }
}
