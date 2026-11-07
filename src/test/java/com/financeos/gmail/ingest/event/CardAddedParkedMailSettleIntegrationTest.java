package com.financeos.gmail.ingest.event;

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

/**
 * Adding a card (or any number the account answers to) publishes {@link AccountIngestChangedEvent}; its listener must settle
 * the parked mail the same way assigning a number does: inside the account's window the mail is reprocessed, older mail is
 * skipped with the account set, so nothing of that number is left in "Needs attention".
 */
@SpringBootTest
class CardAddedParkedMailSettleIntegrationTest {

    @Autowired private GmailIngestEventListener listener;
    @Autowired private UserRepository userRepository;
    @Autowired private AccountRepository accountRepository;
    @Autowired private GmailConnectionRepository connectionRepository;
    @Autowired private GmailProcessedMessageRepository messageRepository;
    @Autowired private JdbcTemplate jdbc;

    private User user;
    private GmailConnection connection;
    private Account account;

    @BeforeEach
    void setUp() {
        user = new User();
        user.setDisplayName("card-added");
        user.setEmail("card-added-" + UUID.randomUUID() + "@example.test");
        user.setPasswordHash("hash");
        user = userRepository.save(user);
        connection = new GmailConnection();
        connection.setUser(user);
        connection.setEmail(user.getEmail());
        connection.setEncryptedRefreshToken("refresh-token");
        connection = connectionRepository.save(connection);
        account = new Account("HDFC Savings", AccountType.bank_account);
        account.setUser(user);
        account.setIngestFromDate(LocalDate.of(2026, 10, 7));
        AccountBankDetails details_ = new AccountBankDetails(account, BigDecimal.ZERO, "3003");
        details_.setUser(user);
        account.setBankDetails(details_);
        account = accountRepository.save(account);
    }

    @AfterEach
    void tearDown() {
        UserDataCleanup.deleteUsers(jdbc, List.of(user.getId()));
    }

    @Test
    void cardAdded_reprocessesInWindowMail_andSkipsMailBeforeTheWindow() {
        GmailProcessedMessage before = parked("3003", "2021-05-10T00:00:00Z");
        GmailProcessedMessage inWindow = parked("3003", "2026-10-20T00:00:00Z");
        GmailProcessedMessage otherNumber = parked("7358", "2021-05-10T00:00:00Z");

        listener.handleAccountIngestChanged(new AccountIngestChangedEvent(user.getId(), "3003", account.getIngestFromDate()));

        GmailProcessedMessage skipped = messageRepository.findById(before.getId()).orElseThrow();
        assertThat(skipped.getStatus()).isEqualTo(GmailProcessedStatus.SKIPPED_BEFORE_WATERMARK);
        assertThat(skipped.getAccount().getId()).isEqualTo(account.getId());
        assertThat(messageRepository.findById(inWindow.getId()).orElseThrow().getStatus()).isEqualTo(GmailProcessedStatus.DISCOVERED);
        assertThat(messageRepository.findById(otherNumber.getId()).orElseThrow().getStatus()).isEqualTo(GmailProcessedStatus.UNRESOLVED_ACCOUNT);
    }

    private GmailProcessedMessage parked(String last4, String internalDate) {
        GmailProcessedMessage m = new GmailProcessedMessage();
        m.setConnection(connection);
        m.setUser(user);
        m.setGmailMessageId("msg-" + UUID.randomUUID());
        m.setStatus(GmailProcessedStatus.UNRESOLVED_ACCOUNT);
        m.setExtractedLast4(last4);
        m.setInternalDate(Instant.parse(internalDate));
        m.setError("No single account matches card/account ending " + last4);
        return messageRepository.save(m);
    }
}
