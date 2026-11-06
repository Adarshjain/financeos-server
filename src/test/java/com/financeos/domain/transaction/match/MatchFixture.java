package com.financeos.domain.transaction.match;

import com.financeos.domain.account.AccountRepository;
import com.financeos.domain.statement.StatementCoverage;
import com.financeos.domain.statement.StatementLineProjection;
import com.financeos.domain.statement.StatementTransaction;
import com.financeos.domain.statement.StatementTransactionRepository;
import com.financeos.domain.transaction.ReviewStatusManager;
import com.financeos.domain.transaction.Transaction;
import com.financeos.domain.transaction.TransactionRepository;
import com.financeos.gmail.domain.GmailProcessedMessageRepository;

import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyIterable;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * An in-memory ledger behind mocked repositories, so the real {@link StatementBatchMatcher} can be run against
 * transactions and statement links in tests: {@link #txns} and {@link #links} are the tables.
 */
public class MatchFixture {

    public final List<Transaction> txns = new ArrayList<>();
    public final List<StatementTransaction> links = new ArrayList<>();
    public final TransactionRepository transactionRepository;
    public final StatementTransactionRepository statementTransactionRepository = mock(StatementTransactionRepository.class);
    public final AccountRepository accountRepository = mock(AccountRepository.class);
    public final StatementCoverage coverage = mock(StatementCoverage.class);
    public final StatementBatchMatcher matcher;
    /** Transactions an alert mail points at (gmail_processed_messages.transaction_id). */
    public final Set<UUID> alertMailTxnIds = new HashSet<>();
    public final GmailProcessedMessageRepository processedMessageRepository = mock(GmailProcessedMessageRepository.class);
    /** Alert mails with their sender and send time (gmail_processed_messages rows that point at a transaction). */
    public final List<AlertMailRow> alertMails = new ArrayList<>();
    /** Non-rejected statement periods by account, as {@link StatementCoverage} reports them. */
    public final Map<UUID, List<StatementCoverage.Period>> periods = new HashMap<>();
    public final AlertMatcher alertMatcher;
    private long clock = 1_000_000L;

    /** One alert mail row. */
    public record AlertMailRow(UUID transactionId, String gmailMessageId, String senderAddress, Instant internalDate)
            implements GmailProcessedMessageRepository.AlertMail {
        public UUID getTransactionId() { return transactionId; }
        public String getGmailMessageId() { return gmailMessageId; }
        public String getSenderAddress() { return senderAddress; }
        public Instant getInternalDate() { return internalDate; }
    }

    /** Registers the statement's period on the account (coverage). */
    public void period(UUID accountId, com.financeos.domain.statement.Statement s) {
        periods.computeIfAbsent(accountId, k -> new ArrayList<>())
                .add(new StatementCoverage.Period(s.getId(), s.getPeriodStart(), s.getPeriodEnd()));
    }

    private List<StatementCoverage.Period> periodsOf(UUID accountId) {
        List<StatementCoverage.Period> out = new ArrayList<>(periods.getOrDefault(accountId, List.of()));
        out.sort(Comparator.comparing(StatementCoverage.Period::start).thenComparing(StatementCoverage.Period::end));
        return out;
    }

    public MatchFixture() {
        this(mock(TransactionRepository.class));
    }

    /** Wires the ledger into a caller's (mocked) transaction repository. */
    public MatchFixture(TransactionRepository transactionRepository) {
        this.transactionRepository = transactionRepository;
        when(accountRepository.lockById(any())).thenReturn(Optional.empty());
        when(transactionRepository.findByAccountIdAndDateRange(any(), any(), any())).thenAnswer(inv -> {
            UUID accountId = inv.getArgument(0);
            LocalDate lo = inv.getArgument(1);
            LocalDate hi = inv.getArgument(2);
            return txns.stream().filter(t -> onAccountInRange(t, accountId, lo, hi)).toList();
        });
        when(transactionRepository.findLedgerByAccountIdAndDateRange(any(), any(), any())).thenAnswer(inv -> {
            UUID accountId = inv.getArgument(0);
            LocalDate lo = inv.getArgument(1);
            LocalDate hi = inv.getArgument(2);
            return txns.stream().filter(t -> onAccountInRange(t, accountId, lo, hi)).toList();
        });
        when(transactionRepository.save(any(Transaction.class))).thenAnswer(inv -> store(inv.getArgument(0)));
        when(transactionRepository.saveAll(anyIterable())).thenAnswer(inv -> {
            List<Transaction> out = new ArrayList<>();
            for (Transaction t : (Iterable<Transaction>) inv.getArgument(0)) out.add(store(t));
            return out;
        });
        when(transactionRepository.existsBySourceMessageId(any())).thenAnswer(inv ->
                txns.stream().anyMatch(t -> inv.getArgument(0).equals(t.getSourceMessageId())));
        when(statementTransactionRepository.findByIdStatementId(any())).thenAnswer(inv ->
                links.stream().filter(l -> l.getId().getStatementId().equals(inv.getArgument(0))).toList());
        when(statementTransactionRepository.findLinksForAccountInRange(any(), any(), any())).thenAnswer(inv -> {
            UUID accountId = inv.getArgument(0);
            LocalDate lo = inv.getArgument(1);
            LocalDate hi = inv.getArgument(2);
            return links.stream().filter(l -> find(l.getId().getTransactionId())
                    .map(t -> onAccountInRange(t, accountId, lo, hi)).orElse(false)).toList();
        });
        when(statementTransactionRepository.saveAll(anyIterable())).thenAnswer(inv -> {
            List<StatementTransaction> out = new ArrayList<>();
            for (StatementTransaction st : (Iterable<StatementTransaction>) inv.getArgument(0)) {
                links.removeIf(l -> l.getId().equals(st.getId()));
                links.add(st);
                out.add(st);
            }
            return out;
        });
        when(statementTransactionRepository.findLinesByStatementId(any())).thenAnswer(inv -> {
            UUID statementId = inv.getArgument(0);
            List<StatementLineProjection> out = new ArrayList<>();
            links.stream().filter(l -> l.getId().getStatementId().equals(statementId))
                    .sorted(Comparator.comparing(StatementTransaction::getLineIndex, Comparator.nullsFirst(Comparator.naturalOrder())))
                    .forEach(l -> find(l.getId().getTransactionId()).ifPresent(t -> out.add(new StatementLineProjection(
                            t.getId(), l.getLineIndex(), t.getDate(),
                            t.getSourcedDescription() != null ? t.getSourcedDescription() : t.getDescription(),
                            t.getAmount(), t.getType(), t.getReviewType(), l.getBalanceAfter(), l.getChainValid(),
                            t.getSource(), t.getSourceMessageId()))));
            return out;
        });
        when(statementTransactionRepository.findLinkedToOtherStatements(any(), any())).thenAnswer(inv -> {
            Collection<UUID> ids = inv.getArgument(0);
            UUID statementId = inv.getArgument(1);
            Set<UUID> out = new HashSet<>();
            links.stream().filter(l -> ids.contains(l.getId().getTransactionId()) && !l.getId().getStatementId().equals(statementId))
                    .forEach(l -> out.add(l.getId().getTransactionId()));
            return out;
        });
        doAnswer(inv -> {
            for (StatementTransaction st : (Iterable<StatementTransaction>) inv.getArgument(0)) {
                links.removeIf(l -> l.getId().equals(st.getId()));
            }
            return null;
        }).when(statementTransactionRepository).deleteAll(anyIterable());
        when(statementTransactionRepository.findByIdTransactionId(any())).thenAnswer(inv ->
                links.stream().filter(l -> l.getId().getTransactionId().equals(inv.getArgument(0))).toList());
        when(transactionRepository.findAllById(anyIterable())).thenAnswer(inv -> {
            List<Transaction> out = new ArrayList<>();
            for (UUID id : (Iterable<UUID>) inv.getArgument(0)) find(id).ifPresent(out::add);
            return out;
        });
        when(transactionRepository.findById(any())).thenAnswer(inv -> find(inv.getArgument(0)));
        doAnswer(inv -> {
            Transaction t = inv.getArgument(0);
            txns.removeIf(x -> x.getId().equals(t.getId()));
            return null;
        }).when(transactionRepository).delete(any(Transaction.class));
        when(processedMessageRepository.findTransactionIdsIn(any())).thenAnswer(inv -> {
            Collection<UUID> ids = inv.getArgument(0);
            Set<UUID> out = new HashSet<>(alertMailTxnIds);
            alertMails.forEach(m -> out.add(m.transactionId()));
            return out.stream().filter(ids::contains).toList();
        });
        when(processedMessageRepository.findAlertMailsByTransactionIdIn(any())).thenAnswer(inv -> {
            Collection<UUID> ids = inv.getArgument(0);
            List<GmailProcessedMessageRepository.AlertMail> out = new ArrayList<>();
            alertMails.stream().filter(m -> ids.contains(m.transactionId())).forEach(out::add);
            alertMailTxnIds.stream().filter(ids::contains).forEach(id -> out.add(new AlertMailRow(id, null, null, null)));
            return out;
        });
        when(coverage.statements(any())).thenAnswer(inv -> periodsOf(inv.getArgument(0)));
        when(coverage.statementsCovering(any(), any())).thenAnswer(inv -> {
            LocalDate date = inv.getArgument(1);
            return periodsOf(inv.getArgument(0)).stream().filter(p -> p.contains(date)).toList();
        });
        when(coverage.nextAfter(any(), any())).thenAnswer(inv -> {
            LocalDate end = inv.getArgument(1);
            return periodsOf(inv.getArgument(0)).stream().filter(p -> p.start().isAfter(end)).findFirst();
        });
        matcher = new StatementBatchMatcher(accountRepository, transactionRepository, statementTransactionRepository,
                new ReviewStatusManager(), coverage, new MatchWindow());
        matcher.setProcessedMessageRepository(processedMessageRepository);
        alertMatcher = new AlertMatcher(accountRepository, transactionRepository, statementTransactionRepository,
                processedMessageRepository, coverage, new ReviewStatusManager(), new MatchWindow());
    }

    /** Stores a row (assigning id and creation time like persist does). */
    public Transaction store(Transaction t) {
        if (t.getId() == null) t.setId(UUID.randomUUID());
        if (t.getCreatedAt() == null) t.setCreatedAt(Instant.ofEpochSecond(clock++));
        if (txns.stream().noneMatch(x -> x == t)) txns.add(t);
        return t;
    }

    public Optional<Transaction> find(UUID id) {
        return txns.stream().filter(t -> t.getId().equals(id)).findFirst();
    }

    /** Statement ids that link this transaction. */
    public List<UUID> statementsOf(Transaction t) {
        return links.stream().filter(l -> l.getId().getTransactionId().equals(t.getId()))
                .map(l -> l.getId().getStatementId()).toList();
    }

    private static boolean onAccountInRange(Transaction t, UUID accountId, LocalDate lo, LocalDate hi) {
        return t.getAccount() != null && accountId.equals(t.getAccount().getId())
                && t.getDate() != null && !t.getDate().isBefore(lo) && !t.getDate().isAfter(hi);
    }
}
