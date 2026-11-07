package com.financeos.gmail.attention;

import static org.assertj.core.api.Assertions.assertThat;

import com.financeos.api.gmail.dto.AssignNumberGroupResponse;
import com.financeos.api.gmail.dto.AttentionCountResponse;
import com.financeos.api.gmail.dto.AttentionGroupsResponse;
import com.financeos.api.gmail.dto.AttentionGroupsResponse.ImportOffGroup;
import com.financeos.api.gmail.dto.AttentionGroupsResponse.UnknownNumberGroup;
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
import org.springframework.data.domain.PageRequest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/** Gmail "Needs attention" grouped, on real rows: unknown numbers, import-off accounts, assign, ignore and the suggestion. */
@SpringBootTest
class UnresolvedNumberServiceIntegrationTest {

    @Autowired private UnresolvedNumberService service;
    @Autowired private GmailAttentionService attention;
    @Autowired private IgnoredNumberRules rules;
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
    private GmailConnection otherConnection;
    private Account hdfc;

    @BeforeEach
    void setUp() {
        user = saveUser("numbers");
        otherUser = saveUser("numbers-other");
        connection = saveConnection(user);
        otherConnection = saveConnection(otherUser);
        hdfc = saveAccount(user, "HDFC Savings", "hdfc", "0438", LocalDate.of(2026, 10, 7), null);
    }

    @AfterEach
    void tearDown() {
        UserDataCleanup.deleteUsers(jdbc, List.of(user.getId(), otherUser.getId()));
    }

    // ------------------------------------------------------------------ grouping

    @Test
    void groups_byBankAndNumber_withCountsKindsAndSpan() {
        save(connection, "3003", "alerts@hdfcbank.net", null, "alert", "2021-05-10T00:00:00Z");
        save(connection, "3003", "alerts@hdfcbank.net", null, "alert", "2025-04-10T00:00:00Z");
        GmailProcessedMessage statement = save(connection, "3003", "estatement@hdfcbank.net", null, "statement", "2023-01-10T00:00:00Z");
        statement.setError("Failed to resolve account for statement accountNumber: 3003");
        messageRepository.save(statement);
        save(connection, "3003", "alerts@icicibank.com", null, "alert", "2024-01-01T00:00:00Z"); // same digits, another bank
        save(connection, "3003", "alerts@somewhere.example", "icici", "alert", "2024-02-01T00:00:00Z"); // classifier names the bank
        save(connection, null, "alerts@hdfcbank.net", null, "alert", "2024-03-01T00:00:00Z"); // no number: stays individual
        GmailProcessedMessage archived = save(connection, "3003", "alerts@hdfcbank.net", null, "alert", "2022-01-01T00:00:00Z");
        archived.setArchivedAt(Instant.now());
        messageRepository.save(archived);
        save(otherConnection, "3003", "alerts@hdfcbank.net", null, "alert", "2024-01-01T00:00:00Z"); // another user

        AttentionGroupsResponse groups = service.groups(user.getId());

        assertThat(groups.unknownNumbers()).hasSize(2);
        UnknownNumberGroup hdfcGroup = groups.unknownNumbers().stream().filter(g -> "hdfc".equals(g.bank())).findFirst().orElseThrow();
        assertThat(hdfcGroup.last4()).isEqualTo("3003");
        assertThat(hdfcGroup.count()).isEqualTo(3);
        assertThat(hdfcGroup.kinds()).containsExactly("ALERT", "STATEMENT");
        assertThat(hdfcGroup.firstMail()).isEqualTo(Instant.parse("2021-05-10T00:00:00Z"));
        assertThat(hdfcGroup.lastMail()).isEqualTo(Instant.parse("2025-04-10T00:00:00Z"));
        UnknownNumberGroup iciciGroup = groups.unknownNumbers().stream().filter(g -> "icici".equals(g.bank())).findFirst().orElseThrow();
        assertThat(iciciGroup.count()).isEqualTo(2);
        assertThat(iciciGroup.kinds()).containsExactly("ALERT");
    }

    @Test
    void ungrouped_leavesOutNumberedUnresolvedMail_andImportOffMail() {
        GmailProcessedMessage numbered = save(connection, "3003", "alerts@hdfcbank.net", null, "alert", "2024-03-01T00:00:00Z");
        GmailProcessedMessage noNumber = save(connection, null, "alerts@hdfcbank.net", null, "alert", "2024-03-02T00:00:00Z");
        GmailProcessedMessage failed = save(connection, null, "alerts@hdfcbank.net", null, "alert", "2024-03-03T00:00:00Z");
        failed.setStatus(GmailProcessedStatus.FAILED_PERMANENT);
        messageRepository.save(failed);

        List<UUID> ids = messageRepository.findUngroupedAttentionItems(user.getId(),
                        List.of(GmailProcessedStatus.UNRESOLVED_ACCOUNT, GmailProcessedStatus.FAILED_PERMANENT), PageRequest.of(0, 20))
                .map(r -> r.message().getId()).getContent();

        assertThat(ids).containsExactlyInAnyOrder(noNumber.getId(), failed.getId()).doesNotContain(numbered.getId());
    }

    @Test
    void importOff_isOneEntryPerOpenAccount_closedAccountsAreNotListed() {
        Account wallet = saveAccount(user, "Amazon Pay", "icici", "5555", null, null);
        Account swiggy = saveAccount(user, "Swiggy", "hdfc", "7777", null, LocalDate.of(2026, 4, 30));
        notOptedIn(wallet, "2026-09-01T00:00:00Z");
        notOptedIn(wallet, "2026-09-20T00:00:00Z");
        notOptedIn(wallet, "2026-09-10T00:00:00Z");
        notOptedIn(swiggy, "2026-03-01T00:00:00Z");

        List<ImportOffGroup> importOff = service.groups(user.getId()).importOff();

        assertThat(importOff).hasSize(1);
        assertThat(importOff.get(0).accountId()).isEqualTo(wallet.getId());
        assertThat(importOff.get(0).accountName()).isEqualTo("Amazon Pay");
        assertThat(importOff.get(0).count()).isEqualTo(3);
        assertThat(importOff.get(0).firstMail()).isEqualTo(Instant.parse("2026-09-01T00:00:00Z"));
        assertThat(importOff.get(0).lastMail()).isEqualTo(Instant.parse("2026-09-20T00:00:00Z"));
    }

    // ------------------------------------------------------------------ assign

    @Test
    void assign_addsTheIdentifier_reprocessesInWindowMail_andSkipsMailBeforeTheWindow() {
        GmailProcessedMessage old1 = save(connection, "3003", "alerts@hdfcbank.net", null, "alert", "2021-05-10T00:00:00Z");
        GmailProcessedMessage old2 = save(connection, "3003", "alerts@hdfcbank.net", null, "alert", "2025-04-10T00:00:00Z");
        GmailProcessedMessage inWindow = save(connection, "3003", "alerts@hdfcbank.net", null, "alert", "2026-10-20T00:00:00Z");
        GmailProcessedMessage other = save(connection, "7358", "alerts@hdfcbank.net", null, "alert", "2021-05-10T00:00:00Z");

        AssignNumberGroupResponse response = service.assign(user, "3003", hdfc.getId(), null);

        assertThat(response.identifierId()).isNotNull();
        assertThat(response.reactivatedCount()).isEqualTo(1);
        assertThat(response.skippedCount()).isEqualTo(2);
        assertThat(response.remainingCount()).isZero();
        assertThat(status(inWindow)).isEqualTo(GmailProcessedStatus.DISCOVERED);
        for (GmailProcessedMessage old : List.of(old1, old2)) {
            GmailProcessedMessage row = messageRepository.findById(old.getId()).orElseThrow();
            assertThat(row.getStatus()).isEqualTo(GmailProcessedStatus.SKIPPED_BEFORE_WATERMARK);
            assertThat(row.getAccount().getId()).isEqualTo(hdfc.getId());
        }
        assertThat(status(other)).isEqualTo(GmailProcessedStatus.UNRESOLVED_ACCOUNT);
        assertThat(service.groups(user.getId()).unknownNumbers()).extracting(UnknownNumberGroup::last4).containsExactly("7358");
    }

    // ------------------------------------------------------------------ ignore

    @Test
    void ignore_archivesTheGroup_andLaterMailWithItIsArchivedAtOnce() {
        GmailProcessedMessage a = save(connection, "3003", "alerts@hdfcbank.net", null, "alert", "2024-01-01T00:00:00Z");
        GmailProcessedMessage b = save(connection, "3003", "alerts@hdfcbank.net", null, "alert", "2024-02-01T00:00:00Z");
        GmailProcessedMessage otherBank = save(connection, "3003", "alerts@icicibank.com", null, "alert", "2024-02-01T00:00:00Z");

        AttentionCountResponse result = service.ignore(user.getId(), "hdfc", "3003");

        assertThat(result.id()).isNotNull();
        assertThat(result.count()).isEqualTo(2);
        assertThat(messageRepository.findById(a.getId()).orElseThrow().getArchivedAt()).isNotNull();
        assertThat(messageRepository.findById(b.getId()).orElseThrow().getArchivedAt()).isNotNull();
        assertThat(messageRepository.findById(otherBank.getId()).orElseThrow().getArchivedAt()).isNull();
        AttentionGroupsResponse groups = service.groups(user.getId());
        assertThat(groups.unknownNumbers()).extracting(UnknownNumberGroup::bank).containsExactly("icici");
        assertThat(groups.ignored()).hasSize(1);
        assertThat(groups.ignored().get(0).bank()).isEqualTo("hdfc");
        assertThat(groups.ignored().get(0).last4()).isEqualTo("3003");
        assertThat(groups.ignored().get(0).count()).isEqualTo(2);

        // A mail that parks later with the same bank and number is archived at once; others are not.
        GmailProcessedMessage later = save(connection, "3003", "alerts@hdfcbank.net", null, "alert", "2026-11-01T00:00:00Z");
        GmailProcessedMessage laterOtherBank = save(connection, "3003", "alerts@icicibank.com", null, "alert", "2026-11-01T00:00:00Z");
        GmailProcessedMessage laterOtherNumber = save(connection, "4004", "alerts@hdfcbank.net", null, "alert", "2026-11-01T00:00:00Z");
        GmailProcessedMessage laterFailed = save(connection, "3003", "alerts@hdfcbank.net", null, "alert", "2026-11-01T00:00:00Z");
        laterFailed.setStatus(GmailProcessedStatus.FAILED_PERMANENT);
        messageRepository.save(laterFailed);

        int archived = rules.archiveIfIgnored(user.getId(),
                List.of(later.getId(), laterOtherBank.getId(), laterOtherNumber.getId(), laterFailed.getId()));

        assertThat(archived).isEqualTo(1);
        assertThat(messageRepository.findById(later.getId()).orElseThrow().getArchivedAt()).isNotNull();
        assertThat(messageRepository.findById(laterOtherBank.getId()).orElseThrow().getArchivedAt()).isNull();
        assertThat(messageRepository.findById(laterOtherNumber.getId()).orElseThrow().getArchivedAt()).isNull();
        assertThat(messageRepository.findById(laterFailed.getId()).orElseThrow().getArchivedAt()).isNull();
    }

    @Test
    void ignore_isIdempotent_andIsPerUser() {
        service.ignore(user.getId(), "hdfc", "3003");
        service.ignore(user.getId(), "hdfc", "3003");
        GmailProcessedMessage theirs = save(otherConnection, "3003", "alerts@hdfcbank.net", null, "alert", "2024-01-01T00:00:00Z");

        assertThat(ignoredRepository.findByUser(user.getId())).hasSize(1);
        assertThat(rules.archiveIfIgnored(otherUser.getId(), List.of(theirs.getId()))).isZero();
    }

    @Test
    void ignore_withNoBank_matchesMailWhoseBankIsUnknown() {
        GmailProcessedMessage unknown = save(connection, "3003", "alerts@mystery.example", null, "alert", "2024-01-01T00:00:00Z");

        AttentionCountResponse result = service.ignore(user.getId(), null, "3003");

        assertThat(result.count()).isEqualTo(1);
        assertThat(messageRepository.findById(unknown.getId()).orElseThrow().getArchivedAt()).isNotNull();
        GmailProcessedMessage later = save(connection, "3003", "alerts@mystery.example", null, "alert", "2026-11-01T00:00:00Z");
        assertThat(rules.archiveIfIgnored(user.getId(), List.of(later.getId()))).isEqualTo(1);
    }

    @Test
    void unignore_dropsTheRule_andPutsTheGroupBack() {
        GmailProcessedMessage a = save(connection, "3003", "alerts@hdfcbank.net", null, "alert", "2024-01-01T00:00:00Z");
        UUID ruleId = service.ignore(user.getId(), "hdfc", "3003").id();

        AttentionCountResponse result = service.unignore(user.getId(), ruleId);

        assertThat(result.count()).isEqualTo(1);
        assertThat(messageRepository.findById(a.getId()).orElseThrow().getArchivedAt()).isNull();
        assertThat(ignoredRepository.findByUser(user.getId())).isEmpty();
        assertThat(service.groups(user.getId()).unknownNumbers()).hasSize(1);
        GmailProcessedMessage later = save(connection, "3003", "alerts@hdfcbank.net", null, "alert", "2026-11-01T00:00:00Z");
        assertThat(rules.archiveIfIgnored(user.getId(), List.of(later.getId()))).isZero();
    }

    // ------------------------------------------------------------------ suggestion

    @Test
    void suggestion_whenEveryMatchedMailPointsAtOneAccount() {
        Account card = saveAccount(user, "HDFC Regalia", "hdfc", null, LocalDate.of(2021, 1, 1), null);
        txn(card, "2024-05-10", "500.00", TransactionType.DEBIT, "SWIGGY");
        txn(card, "2024-06-10", "1200.00", TransactionType.CREDIT, "REFUND");
        alert("3003", "alerts@hdfcbank.net", "500", "debit", "2024-05-11");       // a day apart still matches
        alert("3003", "alerts@hdfcbank.net", "1200", "credit", "2024-06-10");
        alert("3003", "alerts@hdfcbank.net", "99", "debit", "2024-06-10");         // no such transaction: says nothing

        UnknownNumberGroup group = service.groups(user.getId()).unknownNumbers().get(0);

        assertThat(group.suggestion()).isNotNull();
        assertThat(group.suggestion().accountId()).isEqualTo(card.getId());
        assertThat(group.suggestion().accountName()).isEqualTo("HDFC Regalia");
        assertThat(group.suggestion().matched()).isEqualTo(2);
        assertThat(group.suggestion().total()).isEqualTo(3);
        assertThat(group.suggestion().example().amount()).isEqualByComparingTo("500");
        assertThat(group.suggestion().example().date()).isEqualTo(LocalDate.of(2024, 5, 10));
        assertThat(group.suggestion().example().description()).isEqualTo("SWIGGY");
    }

    @Test
    void suggestion_noneWhenNothingMatches_orDirectionOrDateOrBankDiffers() {
        Account card = saveAccount(user, "HDFC Regalia", "hdfc", null, LocalDate.of(2021, 1, 1), null);
        Account icici = saveAccount(user, "ICICI Amazon", "icici", null, LocalDate.of(2021, 1, 1), null);
        txn(card, "2024-05-10", "500.00", TransactionType.CREDIT, "A");   // direction differs
        txn(card, "2024-05-20", "700.00", TransactionType.DEBIT, "B");    // two days away
        txn(icici, "2024-05-10", "300.00", TransactionType.DEBIT, "C");   // another bank
        alert("3003", "alerts@hdfcbank.net", "500", "debit", "2024-05-10");
        alert("3003", "alerts@hdfcbank.net", "700", "debit", "2024-05-23");
        alert("3003", "alerts@hdfcbank.net", "300", "debit", "2024-05-10");

        assertThat(service.groups(user.getId()).unknownNumbers().get(0).suggestion()).isNull();
    }

    @Test
    void suggestion_noneWhenAMailMatchesSeveralTransactions_orMatchesPointAtTwoAccounts() {
        Account a = saveAccount(user, "HDFC Regalia", "hdfc", null, LocalDate.of(2021, 1, 1), null);
        Account b = saveAccount(user, "HDFC Millennia", "hdfc", null, LocalDate.of(2021, 1, 1), null);
        txn(a, "2024-05-10", "500.00", TransactionType.DEBIT, "A1");
        txn(a, "2024-05-10", "500.00", TransactionType.DEBIT, "A2");      // ambiguous inside one account
        txn(a, "2024-06-10", "800.00", TransactionType.DEBIT, "A3");
        txn(b, "2024-07-10", "900.00", TransactionType.DEBIT, "B1");
        alert("3003", "alerts@hdfcbank.net", "500", "debit", "2024-05-10");
        alert("3003", "alerts@hdfcbank.net", "800", "debit", "2024-06-10");  // matches only a
        alert("3003", "alerts@hdfcbank.net", "900", "debit", "2024-07-10");  // matches only b

        assertThat(service.groups(user.getId()).unknownNumbers().get(0).suggestion()).isNull();
    }

    @Test
    void suggestion_ignoresAnotherUsersTransactions_andGroupsWithNoKnownBank() {
        Account theirs = saveAccount(otherUser, "Their HDFC", "hdfc", null, LocalDate.of(2021, 1, 1), null);
        txn(theirs, otherUser, "2024-05-10", "500.00", TransactionType.DEBIT, "THEIRS");
        alert("3003", "alerts@hdfcbank.net", "500", "debit", "2024-05-10");
        GmailProcessedMessage unknownBank = alert("4004", "alerts@mystery.example", "500", "debit", "2024-05-10");
        assertThat(unknownBank.getId()).isNotNull();

        assertThat(service.groups(user.getId()).unknownNumbers()).allSatisfy(g -> assertThat(g.suggestion()).isNull());
    }

    // ------------------------------------------------------------------ total, ungrouped summary and bulk

    private GmailProcessedMessage failed(String error) {
        GmailProcessedMessage m = save(connection, null, "alerts@hdfcbank.net", null, "alert", "2026-10-10T00:00:00Z");
        m.setStatus(GmailProcessedStatus.FAILED_PERMANENT);
        m.setError(error);
        return messageRepository.save(m);
    }

    @Test
    void total_isUnknownGroupsPlusImportOffAccountsPlusUngroupedEmails_andAttentionTotalAgrees() {
        Account wallet = saveAccount(user, "Amazon Pay", "icici", "5555", null, null);
        save(connection, "3003", "alerts@hdfcbank.net", null, "alert", "2024-01-01T00:00:00Z");
        save(connection, "3003", "alerts@hdfcbank.net", null, "alert", "2024-02-01T00:00:00Z"); // same group
        save(connection, "4004", "alerts@hdfcbank.net", null, "alert", "2024-02-01T00:00:00Z");
        notOptedIn(wallet, "2026-09-01T00:00:00Z");
        notOptedIn(wallet, "2026-09-02T00:00:00Z"); // same account
        save(connection, null, "alerts@hdfcbank.net", null, "alert", "2024-02-01T00:00:00Z"); // no number: ungrouped
        failed("Extraction failed: x");

        assertThat(service.groups(user.getId()).total()).isEqualTo(2 + 1 + 2);
        assertThat(service.attentionTotal(user.getId())).isEqualTo(5);
        assertThat(service.attentionTotal(otherUser.getId())).isZero();
    }

    @Test
    void ungroupedSummaryAndBulkActions_neverTouchGroupedMail() {
        GmailProcessedMessage grouped = save(connection, "3003", "alerts@hdfcbank.net", null, "alert", "2024-01-01T00:00:00Z");
        GmailProcessedMessage noNumber = save(connection, null, "alerts@hdfcbank.net", null, "alert", "2024-02-01T00:00:00Z");
        Account wallet = saveAccount(user, "Amazon Pay", "icici", "5555", null, null);
        notOptedIn(wallet, "2026-09-01T00:00:00Z");
        GmailProcessedMessage importOff = messageRepository.findAll().stream()
                .filter(m -> m.getStatus() == GmailProcessedStatus.ACCOUNT_NOT_OPTED_IN && user.getId().equals(m.getUser().getId())).findFirst().orElseThrow();
        importOff.setArchivedAt(null); // an active import-off row would otherwise be hit by the unfiltered bulk
        messageRepository.save(importOff);

        assertThat(attention.summary(user.getId(), false, false)).extracting(c -> c.reason().name() + ":" + c.count())
                .containsExactlyInAnyOrder("NO_ACCOUNT:2", "IMPORT_OFF:1");
        assertThat(attention.summary(user.getId(), false, true)).extracting(c -> c.reason().name() + ":" + c.count())
                .containsExactly("NO_ACCOUNT:1");

        assertThat(attention.archiveAll(user.getId(), com.financeos.gmail.domain.AttentionReason.NO_ACCOUNT, true)).isEqualTo(1);
        assertThat(messageRepository.findById(noNumber.getId()).orElseThrow().getArchivedAt()).isNotNull();
        assertThat(messageRepository.findById(grouped.getId()).orElseThrow().getArchivedAt()).isNull();
        assertThat(attention.archiveAll(user.getId(), com.financeos.gmail.domain.AttentionReason.IMPORT_OFF, true)).isZero();
        assertThat(messageRepository.findById(importOff.getId()).orElseThrow().getArchivedAt()).isNull();
    }

    @Test
    void ungroupedRetryAll_leavesGroupedMailParked() {
        GmailProcessedMessage grouped = save(connection, "3003", "alerts@hdfcbank.net", null, "alert", "2024-01-01T00:00:00Z");
        GmailProcessedMessage noNumber = save(connection, null, "alerts@hdfcbank.net", null, "alert", "2024-02-01T00:00:00Z");

        assertThat(attention.retryAll(user.getId(), com.financeos.gmail.domain.AttentionReason.NO_ACCOUNT, true)).isEqualTo(1);

        assertThat(status(noNumber)).isEqualTo(GmailProcessedStatus.DISCOVERED);
        assertThat(status(grouped)).isEqualTo(GmailProcessedStatus.UNRESOLVED_ACCOUNT);
    }

    // ------------------------------------------------------------------ helpers

    private GmailProcessedStatus status(GmailProcessedMessage m) {
        return messageRepository.findById(m.getId()).orElseThrow().getStatus();
    }

    private GmailProcessedMessage alert(String last4, String sender, String amount, String direction, String date) {
        GmailProcessedMessage m = save(connection, last4, sender, null, "alert", date + "T05:00:00Z");
        m.setExtractAmount(new BigDecimal(amount));
        m.setExtractDirection(direction);
        m.setExtractDate(LocalDate.parse(date));
        return messageRepository.save(m);
    }

    private GmailProcessedMessage save(GmailConnection conn, String last4, String sender, String classifiedBank, String pipelineKind, String internalDate) {
        GmailProcessedMessage m = new GmailProcessedMessage();
        m.setConnection(conn);
        m.setUser(conn.getUser());
        m.setGmailMessageId("msg-" + UUID.randomUUID());
        m.setStatus(GmailProcessedStatus.UNRESOLVED_ACCOUNT);
        m.setExtractedLast4(last4);
        m.setSenderAddress(sender);
        m.setClassifiedBank(classifiedBank);
        m.setPipelineKind(pipelineKind.toUpperCase());
        m.setInternalDate(Instant.parse(internalDate));
        m.setError("No single account matches card/account ending " + last4);
        return messageRepository.save(m);
    }

    private void notOptedIn(Account account, String internalDate) {
        GmailProcessedMessage m = save(connection, null, "alerts@bank.example", null, "alert", internalDate);
        m.setAccount(account);
        m.setStatus(GmailProcessedStatus.ACCOUNT_NOT_OPTED_IN);
        m.setArchivedAt(Instant.now());
        messageRepository.save(m);
    }

    private void txn(Account account, String date, String amount, TransactionType type, String description) {
        txn(account, user, date, amount, type, description);
    }

    private void txn(Account account, User owner, String date, String amount, TransactionType type, String description) {
        Transaction t = new Transaction();
        t.setUser(owner);
        t.setAccount(account);
        t.setDate(LocalDate.parse(date));
        t.setAmount(new BigDecimal(amount));
        t.setType(type);
        t.setSource(TransactionSource.manual);
        t.setDescription(description);
        t.setSourcedDescription(description);
        t.setReviewType(ReviewType.AUTO_REVIEWED);
        t.setTransactionUnderMonitoring(false);
        t.setTransactionExcluded(false);
        transactionRepository.save(t);
    }

    private Account saveAccount(User owner, String name, String bank, String last4, LocalDate ingestFrom, LocalDate closedOn) {
        Account account = new Account(name, last4 == null ? AccountType.credit_card : AccountType.bank_account);
        account.setUser(owner);
        account.setBank(bank);
        account.setIngestFromDate(ingestFrom);
        account.setClosedOn(closedOn);
        if (last4 != null) {
            AccountBankDetails details_ = new AccountBankDetails(account, BigDecimal.ZERO, last4);
            details_.setUser(owner);
            account.setBankDetails(details_);
        }
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
