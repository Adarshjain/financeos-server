package com.financeos.domain.account.card;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.financeos.api.account.dto.CardSeenDatesResponse;
import com.financeos.core.exception.ValidationException;
import com.financeos.core.security.UserContext;
import com.financeos.domain.account.Account;
import com.financeos.domain.account.AccountBankDetails;
import com.financeos.domain.account.AccountRepository;
import com.financeos.domain.account.AccountType;
import com.financeos.domain.transaction.ReviewType;
import com.financeos.domain.transaction.Transaction;
import com.financeos.domain.transaction.TransactionRepository;
import com.financeos.domain.transaction.TransactionSource;
import com.financeos.domain.transaction.TransactionType;
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

/** The first and last day a card number was seen, from Gmail mail at the account's bank and from the account's own transactions. */
@SpringBootTest
class CardSeenDatesServiceIntegrationTest {

    @Autowired private CardSeenDatesService service;
    @Autowired private UserRepository userRepository;
    @Autowired private AccountRepository accountRepository;
    @Autowired private GmailConnectionRepository connectionRepository;
    @Autowired private GmailProcessedMessageRepository messageRepository;
    @Autowired private TransactionRepository transactionRepository;
    @Autowired private JdbcTemplate jdbc;

    private User user;
    private GmailConnection connection;
    private Account hdfc;

    @BeforeEach
    void setUp() {
        user = new User();
        user.setDisplayName("seen");
        user.setEmail("seen-" + UUID.randomUUID() + "@example.test");
        user.setPasswordHash("hash");
        user = userRepository.save(user);
        connection = new GmailConnection();
        connection.setUser(user);
        connection.setEmail(user.getEmail());
        connection.setEncryptedRefreshToken("refresh-token");
        connection = connectionRepository.save(connection);
        hdfc = new Account("HDFC Savings", AccountType.bank_account);
        hdfc.setUser(user);
        hdfc.setBank("hdfc");
        AccountBankDetails details_ = new AccountBankDetails(hdfc, BigDecimal.ZERO, "0438");
        details_.setUser(user);
        hdfc.setBankDetails(details_);
        hdfc = accountRepository.save(hdfc);
        UserContext.setCurrentUserId(user.getId()); // the service now requires a signed-in user
    }

    @AfterEach
    void tearDown() {
        UserContext.clear();
        UserDataCleanup.deleteUsers(jdbc, List.of(user.getId()));
    }

    @Test
    void mailsAtTheAccountsBank_andTransactionsMentioningTheNumber_giveTheSpan() {
        mail("3003", null, "alerts@hdfcbank.net", "2021-05-12T10:00:00Z");
        mail("3003", null, "alerts@hdfcbank.net", "2025-04-20T10:00:00Z");
        mail(null, "3003", "alerts@hdfcbank.net", "2023-01-01T10:00:00Z");         // the extractor's number counts too
        mail("3003", null, "alerts@icicibank.com", "2019-01-01T10:00:00Z");        // another bank's 3003: left out
        txn("2021-05-10", "UPI-X-HDFC CARD 3003-DINNER");
        txn("2025-05-02", "POS 3003 AMAZON");
        txn("2024-01-01", "POS 9999 SOMEWHERE");

        CardSeenDatesResponse seen = service.seenDates(hdfc.getId(), "3003");

        assertThat(seen.firstSeen()).isEqualTo(LocalDate.of(2021, 5, 10)); // the transaction is older than the first mail
        assertThat(seen.lastSeen()).isEqualTo(LocalDate.of(2025, 5, 2));
        assertThat(seen.mailCount()).isEqualTo(3);
        assertThat(seen.transactionCount()).isEqualTo(2);
    }

    @Test
    void nothingSeen_givesNullDatesAndZeroCounts() {
        CardSeenDatesResponse seen = service.seenDates(hdfc.getId(), "3003");

        assertThat(seen.firstSeen()).isNull();
        assertThat(seen.lastSeen()).isNull();
        assertThat(seen.mailCount()).isZero();
        assertThat(seen.transactionCount()).isZero();
    }

    @Test
    void notFourDigits_isRejected() {
        assertThatThrownBy(() -> service.seenDates(hdfc.getId(), "30x3")).isInstanceOf(ValidationException.class);
    }

    @Test
    void anotherUsersAccount_isRejected() {
        UserContext.setCurrentUserId(UUID.randomUUID());

        assertThatThrownBy(() -> service.seenDates(hdfc.getId(), "3003")).isInstanceOf(ValidationException.class);
    }

    @Test
    void noSignedInUser_isRejected() {
        UserContext.clear();

        assertThatThrownBy(() -> service.seenDates(hdfc.getId(), "3003")).isInstanceOf(ValidationException.class);
    }

    private void mail(String extractedLast4, String extractLast4, String sender, String internalDate) {
        GmailProcessedMessage m = new GmailProcessedMessage();
        m.setConnection(connection);
        m.setUser(user);
        m.setGmailMessageId("msg-" + UUID.randomUUID());
        m.setStatus(GmailProcessedStatus.SKIPPED_BEFORE_WATERMARK);
        m.setExtractedLast4(extractedLast4);
        m.setExtractLast4(extractLast4);
        m.setSenderAddress(sender);
        m.setInternalDate(Instant.parse(internalDate));
        messageRepository.save(m);
    }

    private void txn(String date, String sourced) {
        Transaction t = new Transaction();
        t.setUser(user);
        t.setAccount(hdfc);
        t.setDate(LocalDate.parse(date));
        t.setAmount(new BigDecimal("10"));
        t.setType(TransactionType.DEBIT);
        t.setSource(TransactionSource.manual);
        t.setSourcedDescription(sourced);
        t.setReviewType(ReviewType.AUTO_REVIEWED);
        t.setTransactionUnderMonitoring(false);
        t.setTransactionExcluded(false);
        transactionRepository.save(t);
    }
}
