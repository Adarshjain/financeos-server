package com.financeos.gmail.ingest;

import static org.assertj.core.api.Assertions.assertThat;

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
import com.financeos.support.UserDataCleanup;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/** Settling parked mail of an account with import off: closed accounts skip it quietly, open ones park it archived (one entry per account). */
@SpringBootTest
class ParkedMessageSettlerImportOffIntegrationTest {

    @Autowired private ParkedMessageSettler settler;
    @Autowired private UserRepository userRepository;
    @Autowired private AccountRepository accountRepository;
    @Autowired private GmailConnectionRepository connectionRepository;
    @Autowired private GmailProcessedMessageRepository messageRepository;
    @Autowired private JdbcTemplate jdbc;

    private User user;
    private GmailConnection connection;

    @BeforeEach
    void setUp() {
        user = new User();
        user.setDisplayName("import-off");
        user.setEmail("import-off-" + UUID.randomUUID() + "@example.test");
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
        UserDataCleanup.deleteUsers(jdbc, List.of(user.getId()));
    }

    @Test
    void closedAccountWithNoIngestDate_skipsParkedMail() {
        Account swiggy = account("Swiggy", "4444", LocalDate.of(2026, 4, 30));
        UUID id = parked("4444");

        assertThat(settler.settleAll(user.getId())).isEqualTo(1);

        GmailProcessedMessage row = messageRepository.findById(id).orElseThrow();
        assertThat(row.getStatus()).isEqualTo(GmailProcessedStatus.SKIPPED_BEFORE_WATERMARK);
        assertThat(row.getAccount().getId()).isEqualTo(swiggy.getId());
        assertThat(row.getError()).isNull();
        assertThat(row.getArchivedAt()).isNull();
    }

    @Test
    void openAccountWithNoIngestDate_parksParkedMailArchived() {
        Account wallet = account("Amazon Pay", "5555", null);
        UUID id = parked("5555");

        assertThat(settler.settleAll(user.getId())).isEqualTo(1);

        GmailProcessedMessage row = messageRepository.findById(id).orElseThrow();
        assertThat(row.getStatus()).isEqualTo(GmailProcessedStatus.ACCOUNT_NOT_OPTED_IN);
        assertThat(row.getAccount().getId()).isEqualTo(wallet.getId());
        assertThat(row.getArchivedAt()).isNotNull();
    }

    private Account account(String name, String last4, LocalDate closedOn) {
        Account account = new Account(name, AccountType.bank_account);
        account.setUser(user);
        account.setClosedOn(closedOn);
        AccountBankDetails details_ = new AccountBankDetails(account, BigDecimal.ZERO, last4);
        details_.setUser(user);
        account.setBankDetails(details_);
        return accountRepository.save(account);
    }

    private UUID parked(String last4) {
        GmailProcessedMessage m = new GmailProcessedMessage();
        m.setConnection(connection);
        m.setUser(user);
        m.setGmailMessageId("msg-" + UUID.randomUUID());
        m.setStatus(GmailProcessedStatus.UNRESOLVED_ACCOUNT);
        m.setExtractedLast4(last4);
        m.setInternalDate(Instant.parse("2026-03-01T00:00:00Z"));
        m.setError("No single account matches card/account ending " + last4);
        return messageRepository.save(m).getId();
    }
}
