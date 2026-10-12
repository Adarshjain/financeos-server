package com.financeos.gmail.ingest;

import com.financeos.domain.account.Account;
import com.financeos.domain.account.AccountBankDetails;
import com.financeos.domain.account.AccountRepository;
import com.financeos.domain.account.AccountType;
import com.financeos.domain.user.User;
import com.financeos.domain.user.UserRepository;
import com.financeos.gmail.domain.GmailConnection;
import com.financeos.gmail.domain.GmailConnectionRepository;
import com.financeos.gmail.domain.GmailProcessedMessage;
import com.financeos.gmail.domain.GmailProcessedMessageRepository;
import com.financeos.gmail.domain.GmailProcessedStatus;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The sync sweep on real rows and the real resolver: email parked before its account existed
 * settles once the last 4 resolves to exactly one of the user's accounts; everything else stays
 * parked. Another user's account with the same digits must not make the match ambiguous.
 */
@SpringBootTest
class ParkedMessageSettlerIntegrationTest {

    @Autowired private ParkedMessageSettler settler;
    @Autowired private UserRepository userRepository;
    @Autowired private AccountRepository accountRepository;
    @Autowired private GmailConnectionRepository connectionRepository;
    @Autowired private GmailProcessedMessageRepository messageRepository;

    private User user;
    private User otherUser;
    private GmailConnection connection;
    private GmailConnection otherConnection;
    private Account savings;
    private final List<Account> accounts = new ArrayList<>();
    private final List<UUID> messageIds = new ArrayList<>();

    @BeforeEach
    void setUp() {
        user = saveUser("settler");
        otherUser = saveUser("settler-other");
        connection = saveConnection(user);
        otherConnection = saveConnection(otherUser);
        savings = saveBankAccount(user, "HDFC Savings", "0438", LocalDate.of(2026, 10, 7));
        saveBankAccount(otherUser, "Other HDFC", "0438", LocalDate.of(2026, 1, 1));
    }

    @AfterEach
    void tearDown() {
        messageRepository.deleteAllById(messageIds);
        accountRepository.deleteAll(accounts);
        connectionRepository.deleteAll(List.of(connection, otherConnection));
        userRepository.deleteAll(List.of(user, otherUser));
    }

    @Test
    void settleAll_skipsOnlyTheUsersEmailOlderThanTheWindow() {
        UUID old = save(connection, GmailProcessedStatus.UNRESOLVED_ACCOUNT, "0438", "2026-07-21T07:34:17Z");
        UUID justBeforeWindow = save(connection, GmailProcessedStatus.UNRESOLVED_ACCOUNT, "0438", "2026-10-03T18:29:59Z");
        UUID insideWindow = save(connection, GmailProcessedStatus.UNRESOLVED_ACCOUNT, "0438", "2026-10-03T18:30:00Z");
        UUID noMailDate = save(connection, GmailProcessedStatus.UNRESOLVED_ACCOUNT, "0438", null);
        UUID otherUsers = save(otherConnection, GmailProcessedStatus.UNRESOLVED_ACCOUNT, "0438", "2025-12-01T00:00:00Z");
        UUID noAccount = save(connection, GmailProcessedStatus.UNRESOLVED_ACCOUNT, "7358", "2026-07-20T13:26:24Z");

        int settled = settler.settleAll(user.getId());

        assertThat(settled).isEqualTo(2);
        for (UUID id : List.of(old, justBeforeWindow)) {
            GmailProcessedMessage row = messageRepository.findById(id).orElseThrow();
            assertThat(row.getStatus()).isEqualTo(GmailProcessedStatus.SKIPPED_BEFORE_WATERMARK);
            assertThat(row.getAccount().getId()).isEqualTo(savings.getId());
            assertThat(row.getError()).isNull();
        }
        for (UUID id : List.of(insideWindow, noMailDate, otherUsers, noAccount)) {
            assertThat(messageRepository.findById(id).orElseThrow().getStatus()).isEqualTo(GmailProcessedStatus.UNRESOLVED_ACCOUNT);
        }
    }

    @Test
    void settleAll_leavesEmailParkedWhenTwoOfTheUsersAccountsShareTheDigits() {
        saveBankAccount(user, "Second HDFC", "0438", LocalDate.of(2026, 10, 7));
        UUID old = save(connection, GmailProcessedStatus.UNRESOLVED_ACCOUNT, "0438", "2026-07-21T07:34:17Z");

        assertThat(settler.settleAll(user.getId())).isZero();

        assertThat(messageRepository.findById(old).orElseThrow().getStatus()).isEqualTo(GmailProcessedStatus.UNRESOLVED_ACCOUNT);
    }

    @Test
    void settleAll_reparksUnresolvedEmailAsNotOptedInWhenTheAccountHasNoIngestDate() {
        Account wallet = saveBankAccount(user, "Amazon Pay", "5555", null);
        UUID row = save(connection, GmailProcessedStatus.UNRESOLVED_ACCOUNT, "5555", "2026-09-01T00:00:00Z");

        assertThat(settler.settleAll(user.getId())).isEqualTo(1);
        assertThat(settler.settleAll(user.getId())).as("a second sweep finds nothing left").isZero();

        GmailProcessedMessage settled = messageRepository.findById(row).orElseThrow();
        assertThat(settled.getStatus()).isEqualTo(GmailProcessedStatus.ACCOUNT_NOT_OPTED_IN);
        assertThat(settled.getAccount().getId()).isEqualTo(wallet.getId());
    }

    // ------------------------------------------------------------------ helpers

    private UUID save(GmailConnection conn, GmailProcessedStatus status, String last4, String internalDate) {
        GmailProcessedMessage m = new GmailProcessedMessage();
        m.setConnection(conn);
        m.setUser(conn.getUser());
        m.setGmailMessageId("msg-" + UUID.randomUUID());
        m.setStatus(status);
        m.setExtractedLast4(last4);
        m.setInternalDate(internalDate == null ? null : Instant.parse(internalDate));
        m.setError("No single account matches card/account ending " + last4);
        UUID id = messageRepository.save(m).getId();
        messageIds.add(id);
        return id;
    }

    private Account saveBankAccount(User owner, String name, String last4, LocalDate ingestFromDate) {
        Account account = new Account(name, AccountType.bank_account);
        account.setUser(owner);
        account.setIngestFromDate(ingestFromDate);
        account.setBankDetails(new AccountBankDetails(account, BigDecimal.ZERO, last4));
        account = accountRepository.save(account);
        accounts.add(account);
        return account;
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
