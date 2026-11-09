package com.financeos.gmail.domain;

import com.financeos.domain.notification.gmail.GmailAttentionNotificationService;
import com.financeos.domain.user.User;
import com.financeos.domain.user.UserRepository;
import com.financeos.gmail.accountdetect.Vocab.Conf;
import com.financeos.gmail.accountdetect.Vocab.Kind;
import com.financeos.gmail.accountdetect.suggest.AccountSuggestionRepository;
import com.financeos.gmail.accountdetect.suggest.AccountSuggestionService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static com.financeos.gmail.accountdetect.DetectionFixtures.detection;
import static com.financeos.gmail.accountdetect.DetectionFixtures.entity;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Mail parked for an unknown account is hidden from the Inbox list and its count while a NEW account suggestion for the
 * same bank and last4 stands for it, and comes back when the suggestion is marked not mine. No UserContext is set, so the
 * Hibernate userFilter cannot mask a missing user predicate. Made-up data.
 */
@SpringBootTest
class InboxSuggestionFoldInIntegrationTest {

    @Autowired private UserRepository userRepository;
    @Autowired private GmailConnectionRepository connectionRepository;
    @Autowired private GmailProcessedMessageRepository messageRepository;
    @Autowired private AccountSuggestionService suggestions;
    @Autowired private AccountSuggestionRepository suggestionRepository;

    private User user;
    private User other;
    private GmailConnection connection;
    private GmailConnection otherConnection;
    private final List<UUID> messageIds = new ArrayList<>();

    @BeforeEach
    void setUp() {
        user = saveUser("fold");
        other = saveUser("fold-other");
        connection = saveConnection(user);
        otherConnection = saveConnection(other);
    }

    @AfterEach
    void tearDown() {
        messageRepository.deleteAllById(messageIds);
        connectionRepository.deleteAll(List.of(connection, otherConnection));
        userRepository.deleteAll(List.of(user, other));
    }

    private UUID park(GmailConnection conn, String last4, String bank, GmailProcessedStatus status) {
        GmailProcessedMessage m = new GmailProcessedMessage();
        m.setConnection(conn);
        m.setUser(conn.getUser());
        m.setGmailMessageId("msg-" + UUID.randomUUID());
        m.setStatus(status);
        m.setExtractedLast4(last4);
        m.setClassifiedBank(bank);
        UUID id = messageRepository.save(m).getId();
        messageIds.add(id);
        return id;
    }

    private List<UUID> listed() {
        return messageRepository.findAttentionItems(user.getId(), GmailAttentionNotificationService.ATTENTION_STATUSES,
                PageRequest.of(0, 50, Sort.by(Sort.Direction.DESC, "discoveredAt"))).getContent().stream()
                .map(r -> r.message().getId()).toList();
    }

    private long count() {
        return messageRepository.countByUserIdAndStatusIn(user.getId(), GmailAttentionNotificationService.ATTENTION_STATUSES);
    }

    private long pageTotal() {
        return messageRepository.findAttentionItems(user.getId(), GmailAttentionNotificationService.ATTENTION_STATUSES,
                PageRequest.of(0, 1)).getTotalElements();
    }

    private UUID suggestionId(String key) {
        return suggestionRepository.findByUserId(user.getId()).stream().filter(s -> s.getKey().equals(key)).findFirst().orElseThrow().getId();
    }

    @Test
    void hiddenWhileANewSuggestionStandsForItAndBackAfterNotMine() {
        UUID hidden = park(connection, "1111", "hdfc", GmailProcessedStatus.UNRESOLVED_ACCOUNT);
        UUID otherNumber = park(connection, "2222", "hdfc", GmailProcessedStatus.UNRESOLVED_ACCOUNT);
        UUID otherBank = park(connection, "1111", "icici", GmailProcessedStatus.UNRESOLVED_ACCOUNT);
        UUID failed = park(connection, "1111", "hdfc", GmailProcessedStatus.FAILED_PERMANENT);
        suggestions.apply(user.getId(), detection(entity("hdfc", "1111", Kind.CREDIT_CARD, Conf.HIGH)));

        assertThat(listed()).containsExactlyInAnyOrder(otherNumber, otherBank, failed).doesNotContain(hidden);
        assertThat(count()).isEqualTo(3).isEqualTo(pageTotal());

        suggestions.notMine(user.getId(), suggestionId("hdfc:CREDIT_CARD:1111"));

        assertThat(listed()).containsExactlyInAnyOrder(hidden, otherNumber, otherBank, failed);
        assertThat(count()).isEqualTo(4).isEqualTo(pageTotal());

        suggestions.undo(user.getId(), suggestionId("hdfc:CREDIT_CARD:1111"));
        assertThat(count()).isEqualTo(3);
    }

    @Test
    void aMailWithoutABankMatchesOnLast4OnlyWhenExactlyOneNewSuggestionHasIt() {
        UUID noBank = park(connection, "1111", null, GmailProcessedStatus.UNRESOLVED_ACCOUNT);
        suggestions.apply(user.getId(), detection(entity("hdfc", "1111", Kind.CREDIT_CARD, Conf.HIGH)));
        assertThat(listed()).doesNotContain(noBank);

        // every run reports the full detection: a NEW row it leaves out is retired
        suggestions.apply(user.getId(), detection(entity("hdfc", "1111", Kind.CREDIT_CARD, Conf.HIGH),
                entity("icici", "1111", Kind.CREDIT_CARD, Conf.HIGH)));
        assertThat(listed()).contains(noBank);
        assertThat(count()).isEqualTo(1).isEqualTo(pageTotal());

        suggestions.notMine(user.getId(), suggestionId("icici:CREDIT_CARD:1111"));
        assertThat(listed()).doesNotContain(noBank);
        assertThat(count()).isZero();
    }

    @Test
    void theDigestSkipsMailANewSuggestionStandsForAndSeesItAgainAfterNotMine() {
        UUID covered = park(connection, "1111", "hdfc", GmailProcessedStatus.UNRESOLVED_ACCOUNT);
        UUID plain = park(connection, "2222", "hdfc", GmailProcessedStatus.UNRESOLVED_ACCOUNT);
        suggestions.apply(user.getId(), detection(entity("hdfc", "1111", Kind.CREDIT_CARD, Conf.HIGH)));

        List<UUID> digest = messageRepository.findUnnotifiedAttentionItems(user.getId(), GmailAttentionNotificationService.ATTENTION_STATUSES)
                .stream().map(GmailProcessedMessage::getId).toList();
        assertThat(digest).containsExactly(plain);

        suggestions.notMine(user.getId(), suggestionId("hdfc:CREDIT_CARD:1111"));
        digest = messageRepository.findUnnotifiedAttentionItems(user.getId(), GmailAttentionNotificationService.ATTENTION_STATUSES)
                .stream().map(GmailProcessedMessage::getId).toList();
        assertThat(digest).containsExactlyInAnyOrder(covered, plain);
    }

    @Test
    void anotherUsersSuggestionHidesNothing() {
        UUID mine = park(connection, "1111", "hdfc", GmailProcessedStatus.UNRESOLVED_ACCOUNT);
        park(otherConnection, "1111", "hdfc", GmailProcessedStatus.UNRESOLVED_ACCOUNT);
        suggestions.apply(other.getId(), detection(entity("hdfc", "1111", Kind.CREDIT_CARD, Conf.HIGH)));

        assertThat(listed()).containsExactly(mine);
        assertThat(count()).isEqualTo(1);
    }

    @Test
    void onlyUnresolvedAccountMailIsFoldedIn() {
        park(connection, "1111", "hdfc", GmailProcessedStatus.ACCOUNT_NOT_OPTED_IN);
        suggestions.apply(user.getId(), detection(entity("hdfc", "1111", Kind.CREDIT_CARD, Conf.HIGH)));

        assertThat(count()).isEqualTo(1);
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
