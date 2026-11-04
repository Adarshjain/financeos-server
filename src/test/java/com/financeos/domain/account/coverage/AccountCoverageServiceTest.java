package com.financeos.domain.account.coverage;

import com.financeos.api.account.dto.CoverageResponse;
import com.financeos.api.account.dto.CoverageResponse.State;
import com.financeos.core.exception.ResourceNotFoundException;
import com.financeos.core.exception.ValidationException;
import com.financeos.core.security.UserContext;
import com.financeos.core.time.AppTime;
import com.financeos.domain.account.Account;
import com.financeos.domain.account.AccountRepository;
import com.financeos.domain.account.AccountType;
import com.financeos.domain.account.GmailFetchProgress;
import com.financeos.domain.statement.StatementRepository;
import com.financeos.domain.statement.StatementVerdict;
import com.financeos.domain.transaction.TransactionRepository;
import com.financeos.domain.user.User;
import com.financeos.gmail.domain.GmailConnectionRepository;
import com.financeos.gmail.ingest.GmailSenderRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class AccountCoverageServiceTest {

    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");

    private AccountRepository accountRepository;
    private TransactionRepository transactionRepository;
    private StatementRepository statementRepository;
    private AccountEmptyMonthRepository emptyMonthRepository;
    private GmailSenderRepository senderRepository;
    private GmailConnectionRepository connectionRepository;
    private AccountCoverageService service;
    private UUID userId;
    private User user;

    @BeforeEach
    void setUp() {
        // "today" is 2026-05-20: the strip runs through 2026-05
        AppTime.useClock(Clock.fixed(LocalDate.of(2026, 5, 20).atStartOfDay(IST).toInstant(), IST));
        accountRepository = mock(AccountRepository.class);
        transactionRepository = mock(TransactionRepository.class);
        statementRepository = mock(StatementRepository.class);
        emptyMonthRepository = mock(AccountEmptyMonthRepository.class);
        senderRepository = mock(GmailSenderRepository.class);
        connectionRepository = mock(GmailConnectionRepository.class);
        service = new AccountCoverageService(accountRepository, transactionRepository, statementRepository,
                emptyMonthRepository, senderRepository, connectionRepository);
        userId = UUID.randomUUID();
        user = new User();
        user.setId(userId);
        UserContext.setCurrentUserId(userId);
        when(transactionRepository.findTransactionMonthsByAccountIds(any())).thenReturn(List.of());
        when(statementRepository.findPeriodsByAccountIdIn(any())).thenReturn(List.of());
        when(emptyMonthRepository.findMarkedByAccountIds(any())).thenReturn(List.of());
        when(senderRepository.findBankCoverage(userId)).thenReturn(List.of());
        when(connectionRepository.existsByUserIdAndIsConnectedTrue(userId)).thenReturn(false);
    }

    @AfterEach
    void tearDown() {
        UserContext.clear();
        AppTime.reset();
    }

    private Account account(LocalDate ingestFrom, String bank) {
        Account a = new Account("Savings", AccountType.bank_account);
        a.setId(UUID.randomUUID());
        a.setUser(user);
        a.setBank(bank);
        a.setIngestFromDate(ingestFrom);
        a.setCreatedAt(LocalDate.of(2026, 4, 3).atStartOfDay(IST).toInstant());
        when(accountRepository.findById(a.getId())).thenReturn(Optional.of(a));
        return a;
    }

    private record TxnMonth(UUID getAccountId, Integer getYr, Integer getMo) implements TransactionRepository.AccountMonthProjection {}

    private record Stmt(UUID getAccountId, LocalDate getPeriodStart, LocalDate getPeriodEnd, StatementVerdict getVerdict)
            implements StatementRepository.StatementPeriod {}

    private record Marked(UUID getAccountId, String getMonth) implements AccountEmptyMonthRepository.Marked {}

    private record Cover(String getBankId, Instant getCoveredFrom) implements GmailSenderRepository.BankCoverage {}

    private static TransactionRepository.AccountMonthProjection txnMonth(UUID accountId, int year, int month) {
        return new TxnMonth(accountId, year, month);
    }

    private static StatementRepository.StatementPeriod stmt(UUID accountId, LocalDate start, LocalDate end, StatementVerdict verdict) {
        return new Stmt(accountId, start, end, verdict);
    }

    private static AccountEmptyMonthRepository.Marked marked(UUID accountId, String month) {
        return new Marked(accountId, month);
    }

    private static GmailSenderRepository.BankCoverage cover(String bank, LocalDate coveredFrom) {
        return new Cover(bank, coveredFrom == null ? null : coveredFrom.atStartOfDay(IST).toInstant());
    }

    private static State stateOf(CoverageResponse r, String month) {
        return r.months().stream().filter(m -> m.month().equals(month)).findFirst().orElseThrow().state();
    }

    // ---- states ----

    @Test
    void states_dataViaTransaction_dataViaStatementOverlap_markedEmpty_empty() {
        Account a = account(null, null);
        when(transactionRepository.findTransactionMonthsByAccountIds(any())).thenReturn(List.of(txnMonth(a.getId(), 2026, 1)));
        when(statementRepository.findPeriodsByAccountIdIn(any())).thenReturn(List.of(
                stmt(a.getId(), LocalDate.of(2026, 2, 20), LocalDate.of(2026, 3, 19), StatementVerdict.AUTO_INGEST)));
        when(emptyMonthRepository.findMarkedByAccountIds(any())).thenReturn(List.of(marked(a.getId(), "2026-04")));

        CoverageResponse r = service.coverage(a.getId());

        assertThat(r.months()).extracting(m -> m.month()).containsExactly("2026-01", "2026-02", "2026-03", "2026-04", "2026-05");
        assertThat(stateOf(r, "2026-01")).isEqualTo(State.DATA);
        assertThat(stateOf(r, "2026-02")).isEqualTo(State.DATA);
        assertThat(stateOf(r, "2026-03")).isEqualTo(State.DATA);
        assertThat(stateOf(r, "2026-04")).isEqualTo(State.MARKED_EMPTY);
        assertThat(stateOf(r, "2026-05")).isEqualTo(State.EMPTY);
        assertThat(r.fetching()).isNull();
    }

    @Test
    void dataBeatsAMarkedEmptyMonth_andRejectedStatementsCountForNothing() {
        Account a = account(null, null);
        when(transactionRepository.findTransactionMonthsByAccountIds(any())).thenReturn(List.of(txnMonth(a.getId(), 2026, 4)));
        when(statementRepository.findPeriodsByAccountIdIn(any())).thenReturn(List.of(
                stmt(a.getId(), LocalDate.of(2025, 1, 1), LocalDate.of(2025, 1, 31), StatementVerdict.REJECTED)));
        when(emptyMonthRepository.findMarkedByAccountIds(any())).thenReturn(List.of(marked(a.getId(), "2026-04")));

        CoverageResponse r = service.coverage(a.getId());

        assertThat(r.months().get(0).month()).isEqualTo("2026-04");
        assertThat(stateOf(r, "2026-04")).isEqualTo(State.DATA);
    }

    @Test
    void fetching_monthsNotYetCoveredByGmailBackfill_andProgress() {
        Account a = account(LocalDate.of(2026, 2, 10), "hdfc");
        when(connectionRepository.existsByUserIdAndIsConnectedTrue(userId)).thenReturn(true);
        // the bank's senders cover from 2026-04-01: Feb and Mar are still being fetched
        when(senderRepository.findBankCoverage(userId)).thenReturn(List.of(cover("hdfc", LocalDate.of(2026, 4, 1))));
        when(transactionRepository.findTransactionMonthsByAccountIds(any())).thenReturn(List.of());

        CoverageResponse r = service.coverage(a.getId());

        assertThat(r.months()).extracting(m -> m.month()).containsExactly("2026-02", "2026-03", "2026-04", "2026-05");
        assertThat(stateOf(r, "2026-02")).isEqualTo(State.FETCHING);
        assertThat(stateOf(r, "2026-03")).isEqualTo(State.FETCHING);
        assertThat(stateOf(r, "2026-04")).isEqualTo(State.EMPTY);
        assertThat(stateOf(r, "2026-05")).isEqualTo(State.EMPTY);
        assertThat(r.fetching()).isEqualTo(new GmailFetchProgress(2, 4));
    }

    @Test
    void fetching_firstMonthIsCoveredOnceTheBackfillReachesTheIngestDate_notTheMonthStart() {
        Account a = account(LocalDate.of(2026, 3, 15), "hdfc");
        when(connectionRepository.existsByUserIdAndIsConnectedTrue(userId)).thenReturn(true);
        when(senderRepository.findBankCoverage(userId)).thenReturn(List.of(cover("hdfc", LocalDate.of(2026, 3, 15))));

        CoverageResponse r = service.coverage(a.getId());

        assertThat(r.months()).extracting(m -> m.state()).containsOnly(State.EMPTY);
        assertThat(r.fetching()).isNull();
    }

    @Test
    void fetching_markedEmptyAndDataMonthsAreNotFetching_andNoCursorYetMeansFetching() {
        Account a = account(LocalDate.of(2026, 3, 1), "hdfc");
        when(connectionRepository.existsByUserIdAndIsConnectedTrue(userId)).thenReturn(true);
        when(senderRepository.findBankCoverage(userId)).thenReturn(List.of(cover("hdfc", null)));
        when(transactionRepository.findTransactionMonthsByAccountIds(any())).thenReturn(List.of(txnMonth(a.getId(), 2026, 3)));
        when(emptyMonthRepository.findMarkedByAccountIds(any())).thenReturn(List.of(marked(a.getId(), "2026-04")));

        CoverageResponse r = service.coverage(a.getId());

        assertThat(stateOf(r, "2026-03")).isEqualTo(State.DATA);
        assertThat(stateOf(r, "2026-04")).isEqualTo(State.MARKED_EMPTY);
        assertThat(stateOf(r, "2026-05")).isEqualTo(State.FETCHING);
        assertThat(r.fetching()).isEqualTo(new GmailFetchProgress(2, 3));
    }

    @Test
    void fetching_accountWithoutItsBankSenders_usesEveryEnabledSender_andNotOptedInNeverFetches() {
        Account noBank = account(LocalDate.of(2026, 4, 1), null);
        when(connectionRepository.existsByUserIdAndIsConnectedTrue(userId)).thenReturn(true);
        when(senderRepository.findBankCoverage(userId)).thenReturn(List.of(
                cover("axis", LocalDate.of(2026, 5, 1)), cover(null, LocalDate.of(2026, 4, 1))));

        assertThat(service.coverage(noBank.getId()).fetching()).isNull(); // min over all = Apr 1: covered

        Account notOptedIn = account(null, "axis");
        assertThat(service.coverage(notOptedIn.getId()).months()).extracting(m -> m.state()).doesNotContain(State.FETCHING);
    }

    @Test
    void fetching_neverWithoutAConnectedMailboxOrWithoutSenders() {
        Account a = account(LocalDate.of(2026, 3, 1), "hdfc");
        // no connected mailbox
        when(senderRepository.findBankCoverage(userId)).thenReturn(List.of(cover("hdfc", null)));
        assertThat(service.coverage(a.getId()).fetching()).isNull();

        // connected but no enabled senders
        when(connectionRepository.existsByUserIdAndIsConnectedTrue(userId)).thenReturn(true);
        when(senderRepository.findBankCoverage(userId)).thenReturn(List.of());
        assertThat(service.coverage(a.getId()).fetching()).isNull();
    }

    // ---- range start ----

    @Test
    void rangeStart_isTheEarliestOfIngestDateFirstTransactionAndEarliestStatement() {
        Account a = account(LocalDate.of(2026, 3, 5), null);
        when(transactionRepository.findTransactionMonthsByAccountIds(any())).thenReturn(List.of(txnMonth(a.getId(), 2026, 2)));
        when(statementRepository.findPeriodsByAccountIdIn(any())).thenReturn(List.of(
                stmt(a.getId(), LocalDate.of(2025, 12, 28), LocalDate.of(2026, 1, 27), StatementVerdict.AUTO_INGEST)));
        assertThat(service.coverage(a.getId()).months().get(0).month()).isEqualTo("2025-12");

        when(statementRepository.findPeriodsByAccountIdIn(any())).thenReturn(List.of());
        assertThat(service.coverage(a.getId()).months().get(0).month()).isEqualTo("2026-02");

        when(transactionRepository.findTransactionMonthsByAccountIds(any())).thenReturn(List.of());
        assertThat(service.coverage(a.getId()).months().get(0).month()).isEqualTo("2026-03");
    }

    @Test
    void rangeStart_withNothingElse_isTheMonthTheAccountWasCreated() {
        Account a = account(null, null);
        CoverageResponse r = service.coverage(a.getId());
        assertThat(r.months()).extracting(m -> m.month()).containsExactly("2026-04", "2026-05");
        assertThat(r.months()).extracting(m -> m.state()).containsOnly(State.EMPTY);
    }

    // ---- mark / unmark ----

    @Test
    void markEmpty_savesOnce_andUnmarkDeletes() {
        Account a = account(null, null);
        service.markEmpty(a.getId(), "2026-04");
        verify(emptyMonthRepository).save(argThat(e -> e.getMonth().equals("2026-04")
                && e.getAccount() == a && e.getUser() == user));

        when(emptyMonthRepository.existsByAccountIdAndMonth(a.getId(), "2026-04")).thenReturn(true);
        service.markEmpty(a.getId(), "2026-04");
        verify(emptyMonthRepository, times(1)).save(any());

        service.unmarkEmpty(a.getId(), "2026-04");
        verify(emptyMonthRepository).deleteByAccountIdAndMonth(a.getId(), "2026-04");
    }

    @Test
    void mark_rejectsBadFormatAndFutureMonths_acceptsTheCurrentMonth() {
        Account a = account(null, null);
        for (String bad : new String[]{"2026-13", "2026-4", "26-04", "2026/04", "abcd-ef", ""}) {
            assertThrows(ValidationException.class, () -> service.markEmpty(a.getId(), bad), bad);
            assertThrows(ValidationException.class, () -> service.unmarkEmpty(a.getId(), bad), bad);
        }
        assertThrows(ValidationException.class, () -> service.markEmpty(a.getId(), "2026-06"));
        service.markEmpty(a.getId(), "2026-05");
        verify(emptyMonthRepository).save(any());
    }

    @Test
    void otherUsersAccount_isNotFound_forReadMarkAndUnmark() {
        User other = new User();
        other.setId(UUID.randomUUID());
        Account theirs = new Account("Theirs", AccountType.bank_account);
        theirs.setId(UUID.randomUUID());
        theirs.setUser(other);
        when(accountRepository.findById(theirs.getId())).thenReturn(Optional.of(theirs));

        assertThrows(ResourceNotFoundException.class, () -> service.coverage(theirs.getId()));
        assertThrows(ResourceNotFoundException.class, () -> service.markEmpty(theirs.getId(), "2026-04"));
        assertThrows(ResourceNotFoundException.class, () -> service.unmarkEmpty(theirs.getId(), "2026-04"));
        assertThrows(ResourceNotFoundException.class, () -> service.coverage(UUID.randomUUID()));
        verify(emptyMonthRepository, never()).save(any());
        verify(emptyMonthRepository, never()).deleteByAccountIdAndMonth(any(), any());
    }

    // ---- list: gmailFetch batching ----

    @Test
    void gmailFetch_forManyAccounts_isBatched_andNullWhenNotFetching() {
        Account fetching = account(LocalDate.of(2026, 3, 1), "hdfc");
        Account done = account(LocalDate.of(2026, 5, 1), "hdfc");
        Account notOptedIn = account(null, "hdfc");
        Account alsoFetching = account(LocalDate.of(2026, 4, 1), "hdfc");
        when(connectionRepository.existsByUserIdAndIsConnectedTrue(userId)).thenReturn(true);
        when(senderRepository.findBankCoverage(userId)).thenReturn(List.of(cover("hdfc", LocalDate.of(2026, 5, 1))));

        Map<UUID, GmailFetchProgress> result = service.gmailFetch(List.of(fetching, done, notOptedIn, alsoFetching));

        assertThat(result).containsOnlyKeys(fetching.getId(), alsoFetching.getId());
        assertThat(result.get(fetching.getId())).isEqualTo(new GmailFetchProgress(1, 3));
        assertThat(result.get(alsoFetching.getId())).isEqualTo(new GmailFetchProgress(1, 2));
        // one query per source for the whole list, one cursor query
        verify(transactionRepository, times(1)).findTransactionMonthsByAccountIds(any());
        verify(statementRepository, times(1)).findPeriodsByAccountIdIn(any());
        verify(emptyMonthRepository, times(1)).findMarkedByAccountIds(any());
        verify(senderRepository, times(1)).findBankCoverage(userId);
    }

    @Test
    void gmailFetch_withNoOptedInAccount_runsNoQuery() {
        Account a = account(null, "hdfc");
        assertThat(service.gmailFetch(List.of(a))).isEmpty();
        verifyNoInteractions(transactionRepository, statementRepository, emptyMonthRepository, senderRepository);
    }

    @Test
    void gmailFetch_matchesTheStripsFetchingBlock() {
        Account a = account(LocalDate.of(2026, 2, 10), "hdfc");
        when(connectionRepository.existsByUserIdAndIsConnectedTrue(userId)).thenReturn(true);
        when(senderRepository.findBankCoverage(userId)).thenReturn(List.of(cover("hdfc", LocalDate.of(2026, 4, 1))));
        assertThat(service.gmailFetch(List.of(a)).get(a.getId())).isEqualTo(service.coverage(a.getId()).fetching());
    }
}
