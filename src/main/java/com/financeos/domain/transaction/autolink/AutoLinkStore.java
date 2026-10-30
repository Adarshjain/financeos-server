package com.financeos.domain.transaction.autolink;

import com.financeos.core.time.AppTime;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * Plain-SQL access to {@code link_suggestions} and {@code auto_link_rejections}. Every statement filters
 * by {@code user_id}; writes are batched. Joins the caller's transaction.
 */
@Repository
public class AutoLinkStore {

    /** Candidate ids are stored comma separated in a 4000 character column (36 + 1 per id). */
    static final int MAX_CANDIDATES = 50;

    public record SuggestionRow(UUID id, SuggestionKind kind, UUID transactionId, List<UUID> candidateIds) {
    }

    /** A pairing (kind, transaction, other transaction) the user does not want proposed. */
    public record Rejection(SuggestionKind kind, UUID transactionId, UUID otherId) {
    }

    /** True for a transaction a loan event/payment/charge, lending, dividend or EPF row owns (mirrors AutoLinkCandidateLoader.FREE). */
    private static final String OWNED = "(EXISTS (SELECT 1 FROM loan_events x WHERE x.transaction_id = t.id) "
            + "OR EXISTS (SELECT 1 FROM loan_payments x WHERE x.transaction_id = t.id) "
            + "OR EXISTS (SELECT 1 FROM loan_charges x WHERE x.transaction_id = t.id) "
            + "OR EXISTS (SELECT 1 FROM lendings x WHERE x.transaction_id = t.id) "
            + "OR EXISTS (SELECT 1 FROM dividends x WHERE x.transaction_id = t.id) "
            + "OR EXISTS (SELECT 1 FROM epf_entry x WHERE x.transaction_id = t.id))";

    private final JdbcTemplate jdbc;

    public AutoLinkStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    // ---------------------------------------------------------------- rejections

    public static String pairKey(SuggestionKind kind, UUID a, UUID b) {
        return kind.name() + "|" + a + "|" + b;
    }

    /** Every rejected pairing of the user, as {@link #pairKey} strings. Check both directions with {@link #isRejected}. */
    public Set<String> loadRejectionKeys(UUID userId) {
        Set<String> out = new HashSet<>();
        jdbc.query("SELECT kind, transaction_id, other_ref FROM auto_link_rejections WHERE user_id = ?",
                rs -> {
                    out.add(rs.getString(1) + "|" + rs.getString(2) + "|" + rs.getString(3));
                }, userId.toString());
        return out;
    }

    public static boolean isRejected(Set<String> keys, SuggestionKind kind, UUID a, UUID b) {
        return keys.contains(pairKey(kind, a, b)) || keys.contains(pairKey(kind, b, a));
    }

    /** Idempotent: a pairing already recorded is left as it is. */
    public void addRejections(UUID userId, Collection<Rejection> rejections) {
        if (rejections.isEmpty()) {
            return;
        }
        OffsetDateTime now = OffsetDateTime.now(AppTime.zone());
        List<Object[]> args = new ArrayList<>();
        for (Rejection r : rejections) {
            args.add(new Object[] { userId.toString(), r.kind().name(), r.transactionId().toString(),
                    r.otherId().toString(), now,
                    userId.toString(), r.kind().name(), r.transactionId().toString(), r.otherId().toString() });
        }
        jdbc.batchUpdate("INSERT INTO auto_link_rejections (user_id, kind, transaction_id, other_ref, created_at) "
                + "SELECT ?, ?, ?, ?, ? FROM dual WHERE NOT EXISTS (SELECT 1 FROM auto_link_rejections "
                + "WHERE user_id = ? AND kind = ? AND transaction_id = ? AND other_ref = ?)", args);
    }

    // ---------------------------------------------------------------- suggestions

    public List<SuggestionRow> listSuggestions(UUID userId) {
        return jdbc.query("SELECT id, kind, transaction_id, candidate_ids FROM link_suggestions WHERE user_id = ? "
                + "ORDER BY created_at DESC, id", (rs, i) -> row(rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4)),
                userId.toString());
    }

    public Optional<SuggestionRow> findSuggestion(UUID userId, UUID id) {
        return jdbc.query("SELECT id, kind, transaction_id, candidate_ids FROM link_suggestions WHERE user_id = ? AND id = ?",
                (rs, i) -> row(rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4)),
                userId.toString(), id.toString()).stream().findFirst();
    }

    public int deleteSuggestion(UUID userId, UUID id) {
        return jdbc.update("DELETE FROM link_suggestions WHERE user_id = ? AND id = ?", userId.toString(), id.toString());
    }

    /** Which of {@code ids} are the user's transactions that sit in no link and are owned by no loan, lending, dividend or EPF row (same filter as {@code AutoLinkCandidateLoader}); chunked, one query per chunk. */
    public Set<UUID> liveTransactionIds(UUID userId, Collection<UUID> ids) {
        Set<UUID> out = new HashSet<>();
        List<UUID> list = new ArrayList<>(ids);
        for (int i = 0; i < list.size(); i += 500) {
            List<UUID> chunk = list.subList(i, Math.min(i + 500, list.size()));
            String marks = String.join(",", java.util.Collections.nCopies(chunk.size(), "?"));
            List<Object> args = new ArrayList<>();
            args.add(userId.toString());
            chunk.forEach(id -> args.add(id.toString()));
            jdbc.query("SELECT t.id FROM transactions t WHERE t.user_id = ? AND t.id IN (" + marks + ") "
                    + "AND NOT EXISTS (SELECT 1 FROM transaction_link_members m WHERE m.transaction_id = t.id) "
                    + "AND NOT " + OWNED,
                    rs -> {
                        out.add(UUID.fromString(rs.getString(1)));
                    }, args.toArray());
        }
        return out;
    }

    /** Which of {@code ids} are owned by a loan, lending, dividend or EPF row (so no longer free to link). */
    public Set<UUID> ownedTransactionIds(UUID userId, Collection<UUID> ids) {
        Set<UUID> out = new HashSet<>();
        if (ids.isEmpty()) {
            return out;
        }
        String marks = String.join(",", java.util.Collections.nCopies(ids.size(), "?"));
        List<Object> args = new ArrayList<>();
        args.add(userId.toString());
        ids.forEach(id -> args.add(id.toString()));
        jdbc.query("SELECT t.id FROM transactions t WHERE t.user_id = ? AND t.id IN (" + marks + ") AND " + OWNED,
                rs -> {
                    out.add(UUID.fromString(rs.getString(1)));
                }, args.toArray());
        return out;
    }

    /**
     * Makes the stored suggestions of {@code kinds} equal {@code desired} (transaction id to best-first
     * candidates, per kind): stale rows are deleted, new ones inserted, changed candidate lists updated,
     * each as one batch.
     */
    public void syncSuggestions(UUID userId, Set<SuggestionKind> kinds, Map<SuggestionKind, Map<UUID, List<UUID>>> desired) {
        String in = kinds.stream().map(k -> "'" + k.name() + "'").collect(Collectors.joining(","));
        Map<String, SuggestionRow> existing = new HashMap<>();
        jdbc.query("SELECT id, kind, transaction_id, candidate_ids FROM link_suggestions WHERE user_id = ? AND kind IN (" + in + ")",
                rs -> {
                    SuggestionRow r = row(rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4));
                    existing.put(r.kind() + "|" + r.transactionId(), r);
                }, userId.toString());

        List<Object[]> inserts = new ArrayList<>();
        List<Object[]> updates = new ArrayList<>();
        Set<String> wanted = new HashSet<>();
        OffsetDateTime now = OffsetDateTime.now(AppTime.zone());
        for (SuggestionKind kind : kinds) {
            for (Map.Entry<UUID, List<UUID>> e : desired.getOrDefault(kind, Map.of()).entrySet()) {
                List<UUID> candidates = e.getValue().size() > MAX_CANDIDATES ? e.getValue().subList(0, MAX_CANDIDATES) : e.getValue();
                String key = kind + "|" + e.getKey();
                wanted.add(key);
                String joined = candidates.stream().map(UUID::toString).collect(Collectors.joining(","));
                SuggestionRow old = existing.get(key);
                if (old == null) {
                    inserts.add(new Object[] { UUID.randomUUID().toString(), userId.toString(), kind.name(), e.getKey().toString(), joined, now });
                } else if (!old.candidateIds().equals(candidates)) {
                    updates.add(new Object[] { joined, old.id().toString(), userId.toString() });
                }
            }
        }
        List<Object[]> deletes = existing.entrySet().stream()
                .filter(e -> !wanted.contains(e.getKey()))
                .map(e -> new Object[] { e.getValue().id().toString(), userId.toString() })
                .toList();

        if (!deletes.isEmpty()) {
            jdbc.batchUpdate("DELETE FROM link_suggestions WHERE id = ? AND user_id = ?", deletes);
        }
        if (!updates.isEmpty()) {
            jdbc.batchUpdate("UPDATE link_suggestions SET candidate_ids = ? WHERE id = ? AND user_id = ?", updates);
        }
        if (!inserts.isEmpty()) {
            jdbc.batchUpdate("INSERT INTO link_suggestions (id, user_id, kind, transaction_id, candidate_ids, created_at) "
                    + "VALUES (?, ?, ?, ?, ?, ?)", inserts);
        }
    }

    private static SuggestionRow row(String id, String kind, String transactionId, String candidateIds) {
        List<UUID> candidates = candidateIds == null || candidateIds.isBlank() ? List.of()
                : Arrays.stream(candidateIds.split(",")).map(String::trim).filter(s -> !s.isEmpty()).map(UUID::fromString).toList();
        return new SuggestionRow(UUID.fromString(id), SuggestionKind.valueOf(kind), UUID.fromString(transactionId), candidates);
    }
}
