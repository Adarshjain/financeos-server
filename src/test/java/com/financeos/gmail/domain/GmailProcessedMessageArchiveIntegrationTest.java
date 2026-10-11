package com.financeos.gmail.domain;

import com.financeos.api.gmail.dto.GmailAttentionItemResponse;
import com.financeos.domain.account.Account;
import com.financeos.domain.account.AccountRepository;
import com.financeos.domain.account.AccountType;
import com.financeos.domain.notification.gmail.GmailAttentionNotificationService;
import com.financeos.domain.user.User;
import com.financeos.domain.user.UserRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Archived attention items on real rows: hidden from the active list, the Inbox count and the push
 * digest; listed (and only they) by the archived query. Both list queries select the account name
 * and mailbox email as columns, so the response maps outside a transaction (open-in-view is off)
 * without decrypting the mailbox's refresh token.
 */
@SpringBootTest
class GmailProcessedMessageArchiveIntegrationTest {

    private static final List<GmailProcessedStatus> STATUSES = GmailAttentionNotificationService.ATTENTION_STATUSES;

    @Autowired private UserRepository userRepository;
    @Autowired private AccountRepository accountRepository;
    @Autowired private GmailConnectionRepository connectionRepository;
    @Autowired private GmailProcessedMessageRepository messageRepository;
    @Autowired private JdbcTemplate jdbcTemplate;

    private User user;
    private User otherUser;
    private Account account;
    private GmailConnection connection;
    private GmailConnection otherConnection;
    private final List<UUID> messageIds = new ArrayList<>();

    @BeforeEach
    void setUp() {
        user = saveUser("gmail-archive");
        otherUser = saveUser("gmail-archive-other");
        connection = saveConnection(user);
        otherConnection = saveConnection(otherUser);
        account = new Account();
        account.setName("HSBC Live+");
        account.setUser(user);
        account.setType(AccountType.bank_account);
        account = accountRepository.save(account);
    }

    @AfterEach
    void tearDown() {
        messageRepository.deleteAllById(messageIds);
        accountRepository.delete(account);
        connectionRepository.deleteAll(List.of(connection, otherConnection));
        userRepository.deleteAll(List.of(user, otherUser));
    }

    @Test
    void activeList_excludesArchived_archivedList_hasOnlyArchived() {
        UUID active = save(connection, GmailProcessedStatus.FAILED_PERMANENT, null, null);
        UUID archived = save(connection, GmailProcessedStatus.UNRESOLVED_ACCOUNT, null, Instant.now());

        assertThat(ids(messageRepository.findAttentionItems(user.getId(), STATUSES, page("discoveredAt")))).containsExactly(active);
        assertThat(ids(messageRepository.findArchivedAttentionItems(user.getId(), STATUSES, page("archivedAt")))).containsExactly(archived);
    }

    @Test
    void archivedList_isNewestArchivedFirst_andCountsCorrectly() {
        UUID older = save(connection, GmailProcessedStatus.FAILED_PERMANENT, null, Instant.parse("2026-10-01T00:00:00Z"));
        UUID newer = save(connection, GmailProcessedStatus.FAILED_PERMANENT, null, Instant.parse("2026-10-05T00:00:00Z"));
        save(connection, GmailProcessedStatus.FAILED_PERMANENT, null, null);

        Page<GmailAttentionRow> result = messageRepository.findArchivedAttentionItems(user.getId(), STATUSES, page("archivedAt"));

        assertThat(ids(result)).containsExactly(newer, older);
        assertThat(result.getTotalElements()).isEqualTo(2);
    }

    @Test
    void archivedList_ignoresNonAttentionStatusesAndOtherUsers() {
        save(connection, GmailProcessedStatus.CREATED, null, Instant.now());
        save(otherConnection, GmailProcessedStatus.FAILED_PERMANENT, null, Instant.now());

        assertThat(messageRepository.findArchivedAttentionItems(user.getId(), STATUSES, page("archivedAt")).getTotalElements()).isZero();
    }

    @Test
    void inboxCount_excludesArchived() {
        save(connection, GmailProcessedStatus.FAILED_PERMANENT, null, null);
        save(connection, GmailProcessedStatus.FAILED_PERMANENT, null, Instant.now());

        assertThat(messageRepository.countByUserIdAndStatusIn(user.getId(), STATUSES)).isEqualTo(1);
    }

    @Test
    void digestInput_excludesArchived() {
        UUID active = save(connection, GmailProcessedStatus.ACCOUNT_NOT_OPTED_IN, null, null);
        save(connection, GmailProcessedStatus.ACCOUNT_NOT_OPTED_IN, null, Instant.now());

        assertThat(messageRepository.findUnnotifiedAttentionItems(user.getId(), STATUSES))
                .extracting(GmailProcessedMessage::getId).containsExactly(active);
    }

    @Test
    void bothLists_mapToResponseOutsideATransaction() {
        save(connection, GmailProcessedStatus.ACCOUNT_NOT_OPTED_IN, account, null);
        save(connection, GmailProcessedStatus.ACCOUNT_NOT_OPTED_IN, account, Instant.now());

        GmailAttentionItemResponse active = messageRepository.findAttentionItems(user.getId(), STATUSES, page("discoveredAt"))
                .map(GmailAttentionItemResponse::from).getContent().get(0);
        GmailAttentionItemResponse archived = messageRepository.findArchivedAttentionItems(user.getId(), STATUSES, page("archivedAt"))
                .map(GmailAttentionItemResponse::from).getContent().get(0);

        for (GmailAttentionItemResponse row : List.of(active, archived)) {
            assertThat(row.accountName()).isEqualTo("HSBC Live+");
            assertThat(row.accountId()).isEqualTo(account.getId());
            assertThat(row.connectionEmail()).isEqualTo(user.getEmail());
        }
    }

    @Test
    void lists_neverDecryptTheMailboxRefreshToken() {
        save(connection, GmailProcessedStatus.FAILED_PERMANENT, null, null);
        save(connection, GmailProcessedStatus.FAILED_PERMANENT, null, Instant.now());
        String id = connection.getId().toString();
        String token = jdbcTemplate.queryForObject("SELECT encrypted_refresh_token FROM gmail_connections WHERE id = ?", String.class, id);
        jdbcTemplate.update("UPDATE gmail_connections SET encrypted_refresh_token = 'x' WHERE id = ?", id);
        try {
            assertThat(messageRepository.findAttentionItems(user.getId(), STATUSES, page("discoveredAt")).getContent())
                    .extracting(GmailAttentionRow::connectionEmail).containsExactly(user.getEmail());
            assertThat(messageRepository.findArchivedAttentionItems(user.getId(), STATUSES, page("archivedAt")).getContent())
                    .extracting(GmailAttentionRow::connectionEmail).containsExactly(user.getEmail());
        } finally {
            // tearDown deletes the connection through JPA, which decrypts the token.
            jdbcTemplate.update("UPDATE gmail_connections SET encrypted_refresh_token = ? WHERE id = ?", token, id);
        }
    }

    @Test
    void retriedAndReparkedItem_returnsToTheActiveList() {
        UUID id = save(connection, GmailProcessedStatus.FAILED_PERMANENT, null, Instant.now());
        GmailProcessedMessage gpm = messageRepository.findById(id).orElseThrow();
        gpm.setStatus(GmailProcessedStatus.DISCOVERED);
        messageRepository.save(gpm);
        gpm = messageRepository.findById(id).orElseThrow();
        gpm.setStatus(GmailProcessedStatus.FAILED_PERMANENT);
        messageRepository.save(gpm);

        assertThat(ids(messageRepository.findAttentionItems(user.getId(), STATUSES, page("discoveredAt")))).containsExactly(id);
    }

    // ------------------------------------------------------------------ helpers

    private static PageRequest page(String sortField) {
        return PageRequest.of(0, 10, Sort.by(Sort.Direction.DESC, sortField));
    }

    private static List<UUID> ids(Page<GmailAttentionRow> page) {
        return page.getContent().stream().map(r -> r.message().getId()).toList();
    }

    private UUID save(GmailConnection conn, GmailProcessedStatus status, Account acc, Instant archivedAt) {
        GmailProcessedMessage m = new GmailProcessedMessage();
        m.setConnection(conn);
        m.setUser(conn.getUser());
        m.setGmailMessageId("msg-" + UUID.randomUUID());
        m.setStatus(status);
        m.setAccount(acc);
        m.setArchivedAt(archivedAt);
        UUID id = messageRepository.save(m).getId();
        messageIds.add(id);
        return id;
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
