package com.financeos.gmail.attention;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.financeos.api.gmail.dto.AssignNumberGroupResponse;
import com.financeos.api.gmail.dto.AttentionCountResponse;
import com.financeos.api.gmail.dto.AttentionGroupsResponse;
import com.financeos.api.gmail.dto.AttentionGroupsResponse.UnknownNumberGroup;
import com.financeos.core.exception.ResourceNotFoundException;
import com.financeos.core.exception.ValidationException;
import com.financeos.domain.account.Account;
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
import com.financeos.gmail.domain.GmailIgnoredNumberRepository;
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
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;

/** Review fixes of the grouped Needs-attention service: bank-scoped assign, ignore normalisation, unignore scope, tenancy, batched suggestions. */
@SpringBootTest
class UnresolvedNumberServiceReviewFixesIntegrationTest {

    @Autowired private UnresolvedNumberService service;
    @Autowired private UserRepository userRepository;
    @Autowired private AccountRepository accountRepository;
    @Autowired private GmailConnectionRepository connectionRepository;
    @Autowired private GmailProcessedMessageRepository messageRepository;
    @Autowired private GmailIgnoredNumberRepository ignoredRepository;
    @Autowired private TransactionRepository transactionRepository;
    @Autowired private JdbcTemplate jdbc;

    private User user;
    private User otherUser;
    private GmailConnection connection;
    private Account hdfc;

    @BeforeEach
    void setUp() {
        user = saveUser("fixes");
        otherUser = saveUser("fixes-other");
        connection = saveConnection(user);
        hdfc = saveAccount(user, "HDFC Savings", "hdfc", LocalDate.of(2026, 10, 7));
    }

    @AfterEach
    void tearDown() {
        UserDataCleanup.deleteUsers(jdbc, List.of(user.getId(), otherUser.getId()));
    }

    // ------------------------------------------------------------------ assign

    @Test
    void assign_withBank_touchesOnlyThatBanksGroup_anotherBanksSameDigitsStayParked() {
        GmailProcessedMessage hdfcOld = save("1234", "alerts@hdfcbank.net", "2021-05-10T00:00:00Z");
        GmailProcessedMessage hdfcIn = save("1234", "alerts@hdfcbank.net", "2026-10-20T00:00:00Z");
        GmailProcessedMessage iciciOld = save("1234", "alerts@icicibank.com", "2021-05-10T00:00:00Z");
        GmailProcessedMessage iciciIn = save("1234", "alerts@icicibank.com", "2026-10-20T00:00:00Z");

        AssignNumberGroupResponse response = service.assign(user, "hdfc", "1234", hdfc.getId(), null);

        assertThat(response.reactivatedCount()).isEqualTo(1);
        assertThat(response.skippedCount()).isEqualTo(1);
        assertThat(response.remainingCount()).isZero();
        assertThat(row(hdfcOld).getStatus()).isEqualTo(GmailProcessedStatus.SKIPPED_BEFORE_WATERMARK);
        assertThat(row(hdfcOld).getAccount().getId()).isEqualTo(hdfc.getId());
        assertThat(row(hdfcIn).getStatus()).isEqualTo(GmailProcessedStatus.DISCOVERED);
        for (GmailProcessedMessage icici : List.of(iciciOld, iciciIn)) {
            assertThat(row(icici).getStatus()).isEqualTo(GmailProcessedStatus.UNRESOLVED_ACCOUNT);
            assertThat(row(icici).getAccount()).isNull();
        }
        AttentionGroupsResponse groups = service.groups(user.getId());
        assertThat(groups.unknownNumbers()).extracting(UnknownNumberGroup::bank).containsExactly("icici");
        assertThat(groups.unknownNumbers().get(0).count()).isEqualTo(2);
    }

    @Test
    void assign_withBank_remainingCountsOnlyTheGroup() {
        save("1234", "alerts@hdfcbank.net", "2021-05-10T00:00:00Z");
        save("1234", "alerts@icicibank.com", "2021-05-10T00:00:00Z");
        Account closedElsewhere = saveAccount(user, "ICICI", "icici", null); // no ingest date: settle parks nothing for hdfc rows anyway

        AssignNumberGroupResponse response = service.assign(user, "icici", "1234", closedElsewhere.getId(), null);

        assertThat(response.remainingCount()).isZero(); // the hdfc row is not counted as the icici group's remainder
        assertThat(service.groups(user.getId()).unknownNumbers()).extracting(UnknownNumberGroup::bank).containsExactly("hdfc");
    }

    @Test
    void assign_withoutBank_neverAttachesAnotherBanksMailToTheAccount() {
        GmailProcessedMessage hdfcOld = save("1234", "alerts@hdfcbank.net", "2021-05-10T00:00:00Z");
        GmailProcessedMessage iciciOld = save("1234", "alerts@icicibank.com", "2021-05-10T00:00:00Z");

        service.assign(user, "1234", hdfc.getId(), null);

        assertThat(row(hdfcOld).getStatus()).isEqualTo(GmailProcessedStatus.SKIPPED_BEFORE_WATERMARK);
        assertThat(row(iciciOld).getStatus()).isEqualTo(GmailProcessedStatus.UNRESOLVED_ACCOUNT);
        assertThat(row(iciciOld).getAccount()).isNull();
    }

    @Test
    void assign_rejectsAnotherUsersAccount() {
        Account theirs = saveAccount(otherUser, "Their HDFC", "hdfc", LocalDate.of(2026, 10, 7));

        assertThatThrownBy(() -> service.assign(user, "hdfc", "1234", theirs.getId(), null))
                .isInstanceOf(ValidationException.class);
    }

    // ------------------------------------------------------------------ ignore / unignore

    @Test
    void ignore_normalisesTheBank_trimmedAndBlankToUnknown() {
        GmailProcessedMessage hdfcMail = save("3003", "alerts@hdfcbank.net", "2024-01-01T00:00:00Z");
        GmailProcessedMessage unknownMail = save("4004", "alerts@mystery.example", "2024-01-01T00:00:00Z");

        AttentionCountResponse padded = service.ignore(user.getId(), "  hdfc ", "3003");
        AttentionCountResponse blank = service.ignore(user.getId(), "   ", "4004");

        assertThat(padded.count()).isEqualTo(1);
        assertThat(blank.count()).isEqualTo(1);
        assertThat(row(hdfcMail).getArchivedAt()).isNotNull();
        assertThat(row(unknownMail).getArchivedAt()).isNotNull();
        assertThat(ignoredRepository.findRule(user.getId(), "hdfc", "3003")).isPresent();
        assertThat(ignoredRepository.findRule(user.getId(), "unknown", "4004")).isPresent();
        // the same group submitted again with different spacing returns the existing rule
        assertThat(service.ignore(user.getId(), "hdfc", "3003").id()).isEqualTo(padded.id());
        assertThat(ignoredRepository.findByUser(user.getId())).hasSize(2);
    }

    @Test
    void ignore_rejectsABankLongerThan40Chars() {
        assertThatThrownBy(() -> service.ignore(user.getId(), "x".repeat(41), "3003")).isInstanceOf(ValidationException.class);
        assertThat(ignoredRepository.findByUser(user.getId())).isEmpty();
        assertThat(service.ignore(user.getId(), "x".repeat(40), "3003").id()).isNotNull();
    }

    @Test
    void unignore_restoresOnlyMailTheRuleArchived_notMailArchivedByHandBefore() {
        GmailProcessedMessage byHand = save("3003", "alerts@hdfcbank.net", "2024-01-01T00:00:00Z");
        byHand.setArchivedAt(Instant.now().minus(2, ChronoUnit.DAYS));
        messageRepository.save(byHand);
        GmailProcessedMessage byRule = save("3003", "alerts@hdfcbank.net", "2024-02-01T00:00:00Z");
        UUID ruleId = service.ignore(user.getId(), "hdfc", "3003").id();

        // the ignored count counts only what the rule archived
        assertThat(service.groups(user.getId()).ignored().get(0).count()).isEqualTo(1);

        AttentionCountResponse result = service.unignore(user.getId(), ruleId);

        assertThat(result.count()).isEqualTo(1);
        assertThat(row(byRule).getArchivedAt()).isNull();
        assertThat(row(byHand).getArchivedAt()).isNotNull();
    }

    @Test
    void unignore_rejectsAnotherUsersRule() {
        UUID theirRule = service.ignore(otherUser.getId(), "hdfc", "3003").id();

        assertThatThrownBy(() -> service.unignore(user.getId(), theirRule)).isInstanceOf(ResourceNotFoundException.class);
        assertThat(ignoredRepository.findByUser(otherUser.getId())).hasSize(1);
    }

    // ------------------------------------------------------------------ suggestions across groups

    @Test
    void suggestions_forSeveralBanksAreEachMatchedAgainstTheirOwnBankOnly() {
        Account hdfcCard = saveAccount(user, "HDFC Regalia", "hdfc", LocalDate.of(2021, 1, 1));
        Account iciciCard = saveAccount(user, "ICICI Amazon", "icici", LocalDate.of(2021, 1, 1));
        txn(hdfcCard, "2024-05-10", "500.00", TransactionType.DEBIT);
        txn(iciciCard, "2024-05-10", "500.00", TransactionType.DEBIT); // same amount and day at the other bank
        alert("3003", "alerts@hdfcbank.net", "500", "2024-05-10");
        alert("4004", "alerts@icicibank.com", "500", "2024-05-10");

        List<UnknownNumberGroup> groups = service.groups(user.getId()).unknownNumbers();

        assertThat(groups).hasSize(2);
        for (UnknownNumberGroup g : groups) {
            UUID expected = "hdfc".equals(g.bank()) ? hdfcCard.getId() : iciciCard.getId();
            assertThat(g.suggestion()).isNotNull();
            assertThat(g.suggestion().accountId()).isEqualTo(expected);
        }
    }

    @Test
    void groups_withNoIgnoreRules_listsNoIgnoredNumbers_evenWithArchivedMail() {
        GmailProcessedMessage archived = save("3003", "alerts@hdfcbank.net", "2024-01-01T00:00:00Z");
        archived.setArchivedAt(Instant.now());
        messageRepository.save(archived);

        assertThat(service.groups(user.getId()).ignored()).isEmpty();
    }

    // ------------------------------------------------------------------ helpers

    private GmailProcessedMessage row(GmailProcessedMessage m) {
        return messageRepository.findById(m.getId()).orElseThrow();
    }

    private GmailProcessedMessage alert(String last4, String sender, String amount, String date) {
        GmailProcessedMessage m = save(last4, sender, date + "T05:00:00Z");
        m.setExtractAmount(new BigDecimal(amount));
        m.setExtractDirection("debit");
        m.setExtractDate(LocalDate.parse(date));
        return messageRepository.save(m);
    }

    private GmailProcessedMessage save(String last4, String sender, String internalDate) {
        GmailProcessedMessage m = new GmailProcessedMessage();
        m.setConnection(connection);
        m.setUser(user);
        m.setGmailMessageId("msg-" + UUID.randomUUID());
        m.setStatus(GmailProcessedStatus.UNRESOLVED_ACCOUNT);
        m.setExtractedLast4(last4);
        m.setSenderAddress(sender);
        m.setPipelineKind("ALERT");
        m.setInternalDate(Instant.parse(internalDate));
        return messageRepository.save(m);
    }

    private void txn(Account account, String date, String amount, TransactionType type) {
        Transaction t = new Transaction();
        t.setUser(user);
        t.setAccount(account);
        t.setDate(LocalDate.parse(date));
        t.setAmount(new BigDecimal(amount));
        t.setType(type);
        t.setSource(TransactionSource.manual);
        t.setDescription("X");
        t.setSourcedDescription("X");
        t.setReviewType(ReviewType.AUTO_REVIEWED);
        t.setTransactionUnderMonitoring(false);
        t.setTransactionExcluded(false);
        transactionRepository.save(t);
    }

    private Account saveAccount(User owner, String name, String bank, LocalDate ingestFrom) {
        Account account = new Account(name, AccountType.credit_card);
        account.setUser(owner);
        account.setBank(bank);
        account.setIngestFromDate(ingestFrom);
        return accountRepository.save(account);
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
