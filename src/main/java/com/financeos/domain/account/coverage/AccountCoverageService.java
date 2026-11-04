package com.financeos.domain.account.coverage;

import com.financeos.api.account.dto.CoverageResponse;
import com.financeos.api.account.dto.CoverageResponse.MonthCoverage;
import com.financeos.api.account.dto.CoverageResponse.State;
import com.financeos.core.exception.ResourceNotFoundException;
import com.financeos.core.exception.ValidationException;
import com.financeos.core.security.UserContext;
import com.financeos.core.time.AppTime;
import com.financeos.domain.account.Account;
import com.financeos.domain.account.AccountRepository;
import com.financeos.domain.account.GmailFetchProgress;
import com.financeos.domain.statement.StatementRepository;
import com.financeos.domain.statement.StatementVerdict;
import com.financeos.domain.transaction.TransactionRepository;
import com.financeos.gmail.domain.GmailConnectionRepository;
import com.financeos.gmail.ingest.GmailSenderRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * The month strip of an account's Statements tab: for each month since the account began, whether it has data, was
 * marked empty by the owner, is still being fetched from Gmail, or is empty. All reads are aggregate queries (one
 * per source, for all accounts asked about at once).
 */
@Service
@Transactional(readOnly = true)
public class AccountCoverageService {

    private static final int IN_LIST_CHUNK = 900;
    private static final Pattern MONTH = Pattern.compile("^\\d{4}-(0[1-9]|1[0-2])$");

    private final AccountRepository accountRepository;
    private final TransactionRepository transactionRepository;
    private final StatementRepository statementRepository;
    private final AccountEmptyMonthRepository emptyMonthRepository;
    private final GmailSenderRepository senderRepository;
    private final GmailConnectionRepository connectionRepository;

    public AccountCoverageService(AccountRepository accountRepository,
                                  TransactionRepository transactionRepository,
                                  StatementRepository statementRepository,
                                  AccountEmptyMonthRepository emptyMonthRepository,
                                  GmailSenderRepository senderRepository,
                                  GmailConnectionRepository connectionRepository) {
        this.accountRepository = accountRepository;
        this.transactionRepository = transactionRepository;
        this.statementRepository = statementRepository;
        this.emptyMonthRepository = emptyMonthRepository;
        this.senderRepository = senderRepository;
        this.connectionRepository = connectionRepository;
    }

    /** What the Gmail cursors cover for the user: {@code active} = a connected mailbox and enabled senders exist. */
    record GmailCover(boolean active, Map<String, Instant> coveredByBank, Instant coveredOverall) {
        static final GmailCover NONE = new GmailCover(false, Map.of(), null);

        /** The cover relevant to an account: its bank's senders if it has any, else every enabled sender. */
        Instant forBank(String bank) {
            return bank != null && coveredByBank.containsKey(bank) ? coveredByBank.get(bank) : coveredOverall;
        }
    }

    /** The months of one account that have data (a transaction or a statement period) or an owner mark. */
    record AccountMonths(Set<YearMonth> data, Set<YearMonth> marked) {}

    public CoverageResponse coverage(UUID accountId) {
        Account account = ownedAccount(accountId);
        Map<UUID, AccountMonths> months = loadMonths(List.of(account.getId()));
        GmailCover cover = loadCover(account.getUser().getId());
        return compute(account, months.get(account.getId()), cover, YearMonth.from(AppTime.today()));
    }

    @Transactional
    public void markEmpty(UUID accountId, String month) {
        Account account = ownedAccount(accountId);
        validateMonth(month);
        if (emptyMonthRepository.existsByAccountIdAndMonth(account.getId(), month)) {
            return;
        }
        AccountEmptyMonth row = new AccountEmptyMonth();
        row.setUser(account.getUser());
        row.setAccount(account);
        row.setMonth(month);
        emptyMonthRepository.save(row);
    }

    @Transactional
    public void unmarkEmpty(UUID accountId, String month) {
        Account account = ownedAccount(accountId);
        validateMonth(month);
        emptyMonthRepository.deleteByAccountIdAndMonth(account.getId(), month);
    }

    /**
     * The Gmail fetch progress of each opted-in account that has months still being fetched (others are absent), for
     * a whole list: one cursor query and one query per month source, whatever the number of accounts.
     */
    public Map<UUID, GmailFetchProgress> gmailFetch(Collection<Account> accounts) {
        List<Account> optedIn = accounts.stream().filter(a -> a.getIngestFromDate() != null).toList();
        if (optedIn.isEmpty()) {
            return Map.of();
        }
        UUID userId = optedIn.get(0).getUser().getId();
        Map<UUID, AccountMonths> months = loadMonths(optedIn.stream().map(Account::getId).toList());
        GmailCover cover = loadCover(userId);
        YearMonth now = YearMonth.from(AppTime.today());
        Map<UUID, GmailFetchProgress> result = new HashMap<>();
        for (Account a : optedIn) {
            GmailFetchProgress p = fetching(a, months.get(a.getId()), cover, now);
            if (p != null) {
                result.put(a.getId(), p);
            }
        }
        return result;
    }

    private Account ownedAccount(UUID accountId) {
        UUID userId = UserContext.getCurrentUserId();
        return accountRepository.findById(accountId)
                .filter(a -> a.getUser() != null && a.getUser().getId().equals(userId))
                .orElseThrow(() -> new ResourceNotFoundException("Account", accountId));
    }

    private static void validateMonth(String month) {
        if (month == null || !MONTH.matcher(month).matches()) {
            throw new ValidationException("Month must look like 2026-03");
        }
        if (YearMonth.parse(month).isAfter(YearMonth.from(AppTime.today()))) {
            throw new ValidationException("A future month cannot be marked");
        }
    }

    // ---- loading (aggregate queries) ----

    private Map<UUID, AccountMonths> loadMonths(List<UUID> accountIds) {
        Map<UUID, AccountMonths> result = new HashMap<>();
        for (UUID id : accountIds) {
            result.put(id, new AccountMonths(new TreeSet<>(), new HashSet<>()));
        }
        for (int i = 0; i < accountIds.size(); i += IN_LIST_CHUNK) {
            List<UUID> chunk = accountIds.subList(i, Math.min(accountIds.size(), i + IN_LIST_CHUNK));
            for (TransactionRepository.AccountMonthProjection row : transactionRepository.findTransactionMonthsByAccountIds(chunk)) {
                result.get(row.getAccountId()).data().add(YearMonth.of(row.getYr(), row.getMo()));
            }
            for (StatementRepository.StatementPeriod p : statementRepository.findPeriodsByAccountIdIn(chunk)) {
                if (p.getVerdict() == StatementVerdict.REJECTED) {
                    continue;
                }
                LocalDate start = p.getPeriodStart() != null ? p.getPeriodStart() : p.getPeriodEnd();
                LocalDate end = p.getPeriodEnd() != null ? p.getPeriodEnd() : p.getPeriodStart();
                if (start == null) {
                    continue;
                }
                YearMonth last = YearMonth.from(end.isBefore(start) ? start : end);
                for (YearMonth m = YearMonth.from(start); !m.isAfter(last); m = m.plusMonths(1)) {
                    result.get(p.getAccountId()).data().add(m);
                }
            }
            for (AccountEmptyMonthRepository.Marked row : emptyMonthRepository.findMarkedByAccountIds(chunk)) {
                try {
                    result.get(row.getAccountId()).marked().add(YearMonth.parse(row.getMonth().trim()));
                } catch (DateTimeParseException ignored) {
                    // a malformed stored value marks nothing
                }
            }
        }
        return result;
    }

    private GmailCover loadCover(UUID userId) {
        if (!connectionRepository.existsByUserIdAndIsConnectedTrue(userId)) {
            return GmailCover.NONE;
        }
        List<GmailSenderRepository.BankCoverage> rows = senderRepository.findBankCoverage(userId);
        if (rows.isEmpty()) {
            return GmailCover.NONE;
        }
        Map<String, Instant> byBank = new HashMap<>();
        Instant overall = null;
        boolean anyCursor = false;
        for (GmailSenderRepository.BankCoverage r : rows) {
            if (r.getBankId() != null) {
                byBank.put(r.getBankId(), r.getCoveredFrom());
            }
            if (r.getCoveredFrom() != null) {
                anyCursor = true;
                if (overall == null || r.getCoveredFrom().isBefore(overall)) {
                    overall = r.getCoveredFrom();
                }
            }
        }
        return new GmailCover(true, byBank, anyCursor ? overall : null);
    }

    // ---- computing (pure) ----

    static CoverageResponse compute(Account account, AccountMonths months, GmailCover cover, YearMonth now) {
        YearMonth start = rangeStart(account, months, now);
        List<MonthCoverage> list = new ArrayList<>();
        int fetchingCount = 0;
        for (YearMonth m = start; !m.isAfter(now); m = m.plusMonths(1)) {
            State state = stateOf(account, months, cover, m);
            if (state == State.FETCHING) {
                fetchingCount++;
            }
            list.add(new MonthCoverage(m.toString(), state));
        }
        return new CoverageResponse(list, progress(account, fetchingCount, now));
    }

    private static GmailFetchProgress fetching(Account account, AccountMonths months, GmailCover cover, YearMonth now) {
        int count = 0;
        YearMonth from = YearMonth.from(account.getIngestFromDate());
        for (YearMonth m = from; !m.isAfter(now); m = m.plusMonths(1)) {
            if (stateOf(account, months, cover, m) == State.FETCHING) {
                count++;
            }
        }
        return progress(account, count, now);
    }

    private static GmailFetchProgress progress(Account account, int fetchingCount, YearMonth now) {
        if (fetchingCount == 0 || account.getIngestFromDate() == null) {
            return null;
        }
        int total = (int) (YearMonth.from(account.getIngestFromDate()).until(now, java.time.temporal.ChronoUnit.MONTHS)) + 1;
        return new GmailFetchProgress(Math.max(0, total - fetchingCount), total);
    }

    /** Earliest of the ingest-from date, the first transaction, the earliest statement; else the month the account was created. */
    private static YearMonth rangeStart(Account account, AccountMonths months, YearMonth now) {
        YearMonth start = null;
        if (account.getIngestFromDate() != null) {
            start = YearMonth.from(account.getIngestFromDate());
        }
        if (!months.data().isEmpty()) {
            YearMonth firstData = java.util.Collections.min(months.data());
            start = start == null || firstData.isBefore(start) ? firstData : start;
        }
        if (start == null) {
            start = account.getCreatedAt() != null
                    ? YearMonth.from(account.getCreatedAt().atZone(AppTime.zone()).toLocalDate())
                    : now;
        }
        return start.isAfter(now) ? now : start;
    }

    private static State stateOf(Account account, AccountMonths months, GmailCover cover, YearMonth m) {
        if (months.data().contains(m)) {
            return State.DATA;
        }
        if (months.marked().contains(m)) {
            return State.MARKED_EMPTY;
        }
        LocalDate from = account.getIngestFromDate();
        if (from != null && cover.active() && !m.isBefore(YearMonth.from(from))) {
            LocalDate needed = m.atDay(1).isBefore(from) ? from : m.atDay(1);
            Instant covered = cover.forBank(account.getBank());
            if (covered == null || covered.isAfter(needed.atStartOfDay(AppTime.zone()).toInstant())) {
                return State.FETCHING;
            }
        }
        return State.EMPTY;
    }
}
