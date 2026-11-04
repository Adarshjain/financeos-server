package com.financeos.domain.transaction.autolink;

import com.financeos.api.transaction.dto.TransactionSuggestion;
import com.financeos.api.transaction.dto.TransactionSuggestion.Candidate;
import com.financeos.core.security.UserContext;
import com.financeos.domain.account.AccountType;
import com.financeos.domain.inbox.collect.InboxRows;
import com.financeos.domain.notification.MessageFormat;
import com.financeos.domain.transaction.Transaction;
import com.financeos.domain.transaction.TransactionRepository;
import com.financeos.domain.transaction.TransactionType;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The open link suggestion each transaction of one page shows (subject, or for a transfer or card payment
 * also the credit side), built with a fixed number of queries per page: the suggestions, which of their
 * transactions are still free, the transactions, the accounts' last four digits and the lending entries.
 * Everything is filtered by the current user.
 */
@Service
public class TransactionSuggestionService {

    private static final int IN_CHUNK = 500;

    private final AutoLinkStore store;
    private final TransactionRepository transactionRepository;
    private final JdbcTemplate jdbc;

    public TransactionSuggestionService(AutoLinkStore store, TransactionRepository transactionRepository, JdbcTemplate jdbc) {
        this.store = store;
        this.transactionRepository = transactionRepository;
        this.jdbc = jdbc;
    }

    private record Lend(UUID id, LocalDate date, BigDecimal amount, String notes, String person) {
    }

    @Transactional(readOnly = true)
    public Map<UUID, TransactionSuggestion> suggestionsFor(Collection<UUID> pageIds) {
        UUID userId = UserContext.getCurrentUserId();
        if (userId == null || pageIds == null || pageIds.isEmpty()) {
            return Map.of();
        }
        Set<UUID> page = new HashSet<>(pageIds);
        List<AutoLinkStore.SuggestionRow> rows = store.listSuggestionsFor(userId, page);
        if (rows.isEmpty()) {
            return Map.of();
        }

        Set<UUID> txnIds = new LinkedHashSet<>();
        Set<UUID> lendingIds = new LinkedHashSet<>();
        for (AutoLinkStore.SuggestionRow row : rows) {
            txnIds.add(row.transactionId());
            if (row.kind() == SuggestionKind.LENDING) {
                lendingIds.addAll(row.candidateIds());
            } else {
                txnIds.addAll(row.candidateIds());
            }
        }
        Set<UUID> live = store.liveTransactionIds(userId, txnIds);
        Map<UUID, Transaction> txns = loadTransactions(userId, live);
        Map<UUID, String> last4 = loadLast4(userId, txns.values().stream().map(t -> t.getAccount().getId()).collect(java.util.stream.Collectors.toSet()));
        Map<UUID, Lend> lendings = loadLendings(userId, lendingIds);

        Map<UUID, TransactionSuggestion> out = new HashMap<>();
        for (AutoLinkStore.SuggestionRow row : rows) {
            Transaction subject = txns.get(row.transactionId());
            if (subject == null) {
                continue;
            }
            if (row.kind() == SuggestionKind.LENDING) {
                if (page.contains(subject.getId()) && !out.containsKey(subject.getId())) {
                    lendingSuggestion(row, subject, lendings).ifPresent(s -> out.put(subject.getId(), s));
                }
                continue;
            }
            List<Transaction> candidates = row.candidateIds().stream().map(txns::get).filter(java.util.Objects::nonNull).toList();
            if (candidates.isEmpty()) {
                continue;
            }
            if (page.contains(subject.getId())) {
                out.putIfAbsent(subject.getId(), subjectView(row, subject, candidates, last4));
            }
            if (row.kind() == SuggestionKind.TRANSFER || row.kind() == SuggestionKind.CC_PAYMENT) {
                for (Transaction credit : candidates) {
                    if (page.contains(credit.getId())) {
                        out.putIfAbsent(credit.getId(), creditView(row, subject, credit, last4));
                    }
                }
            }
        }
        return out;
    }

    // ---------------------------------------------------------------- views

    private static TransactionSuggestion subjectView(AutoLinkStore.SuggestionRow row, Transaction subject,
                                                     List<Transaction> candidates, Map<UUID, String> last4) {
        Transaction first = candidates.get(0);
        String label;
        switch (row.kind()) {
            case TRANSFER -> label = "Looks like a transfer to " + accountLabel(first, last4);
            case CC_PAYMENT -> label = "Looks like your " + first.getAccount().getName() + " bill";
            default -> label = "Looks like a refund of " + describe(first) + " " + MessageFormat.money(first.getAmount())
                    + " · " + MessageFormat.date(first.getDate());
        }
        return new TransactionSuggestion(row.id(), row.kind(), label,
                candidates.stream().map(t -> candidate(t, last4)).toList());
    }

    private static TransactionSuggestion creditView(AutoLinkStore.SuggestionRow row, Transaction debit,
                                                    Transaction credit, Map<UUID, String> last4) {
        String label = (credit.getAccount().getType() == AccountType.credit_card ? "Looks like a payment from "
                : "Looks like a transfer from ") + accountLabel(debit, last4);
        // The credit's counterpart is the debit that proposed it; accepting uses the credit's own id.
        return new TransactionSuggestion(row.id(), row.kind(), label, List.of(candidate(debit, last4)));
    }

    private static java.util.Optional<TransactionSuggestion> lendingSuggestion(AutoLinkStore.SuggestionRow row,
                                                                               Transaction credit, Map<UUID, Lend> lendings) {
        List<Candidate> candidates = new ArrayList<>();
        String person = null;
        for (UUID id : row.candidateIds()) {
            Lend l = lendings.get(id);
            if (l != null) {
                person = person == null ? l.person() : person;
                candidates.add(new Candidate(null, l.id(), l.date(), l.notes(), l.person(), l.amount()));
            }
        }
        if (candidates.isEmpty()) {
            return java.util.Optional.empty();
        }
        return java.util.Optional.of(new TransactionSuggestion(row.id(), row.kind(),
                "Looks like " + person + " paying back " + MessageFormat.money(credit.getAmount()), candidates));
    }

    private static Candidate candidate(Transaction t, Map<UUID, String> last4) {
        BigDecimal signed = t.getType() == TransactionType.DEBIT ? t.getAmount().negate() : t.getAmount();
        return new Candidate(t.getId(), null, t.getDate(), t.getDescription() != null ? t.getDescription() : t.getSourcedDescription(),
                t.getAccount().getName(), signed);
    }

    private static String accountLabel(Transaction t, Map<UUID, String> last4) {
        return InboxRows.cardLabel(t.getAccount().getName(), last4.get(t.getAccount().getId()));
    }

    private static String describe(Transaction t) {
        String d = t.getDescription() != null && !t.getDescription().isBlank() ? t.getDescription() : t.getSourcedDescription();
        return d == null || d.isBlank() ? "a purchase" : d.trim();
    }

    // ---------------------------------------------------------------- loading (all user-filtered, chunked)

    private Map<UUID, Transaction> loadTransactions(UUID userId, Collection<UUID> ids) {
        Map<UUID, Transaction> out = new HashMap<>();
        List<UUID> list = new ArrayList<>(ids);
        for (int i = 0; i < list.size(); i += IN_CHUNK) {
            for (Transaction t : transactionRepository.findAllByIdInAndUserId(list.subList(i, Math.min(i + IN_CHUNK, list.size())), userId)) {
                out.putIfAbsent(t.getId(), t);
            }
        }
        return out;
    }

    private Map<UUID, String> loadLast4(UUID userId, Collection<UUID> accountIds) {
        Map<UUID, String> out = new HashMap<>();
        List<UUID> list = new ArrayList<>(accountIds);
        for (int i = 0; i < list.size(); i += IN_CHUNK) {
            List<UUID> chunk = list.subList(i, Math.min(i + IN_CHUNK, list.size()));
            List<Object> args = new ArrayList<>();
            args.add(userId.toString());
            chunk.forEach(id -> args.add(id.toString()));
            jdbc.query("SELECT account_id, last4 FROM account_bank_details WHERE user_id = ? AND account_id IN ("
                    + String.join(",", java.util.Collections.nCopies(chunk.size(), "?")) + ")",
                    rs -> {
                        out.put(UUID.fromString(rs.getString(1)), rs.getString(2));
                    }, args.toArray());
        }
        return out;
    }

    private Map<UUID, Lend> loadLendings(UUID userId, Collection<UUID> ids) {
        Map<UUID, Lend> out = new HashMap<>();
        List<UUID> list = new ArrayList<>(ids);
        for (int i = 0; i < list.size(); i += IN_CHUNK) {
            List<UUID> chunk = list.subList(i, Math.min(i + IN_CHUNK, list.size()));
            List<Object> args = new ArrayList<>();
            args.add(userId.toString());
            chunk.forEach(id -> args.add(id.toString()));
            jdbc.query("SELECT l.id, l.entry_date, l.amount, l.notes, c.name FROM lendings l JOIN counterparties c ON c.id = l.counterparty_id "
                    + "WHERE l.user_id = ? AND c.user_id = l.user_id AND l.id IN ("
                    + String.join(",", java.util.Collections.nCopies(chunk.size(), "?")) + ")",
                    rs -> {
                        UUID id = UUID.fromString(rs.getString(1));
                        out.put(id, new Lend(id, rs.getDate(2).toLocalDate(), rs.getBigDecimal(3), rs.getString(4), rs.getString(5)));
                    }, args.toArray());
        }
        return out;
    }
}
