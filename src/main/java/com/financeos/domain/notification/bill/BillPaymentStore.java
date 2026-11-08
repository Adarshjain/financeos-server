package com.financeos.domain.notification.bill;

import com.financeos.core.time.AppTime;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** Plain-SQL access to {@code bill_payments}. Every statement filters by {@code user_id}; joins the caller's transaction. */
@Repository
public class BillPaymentStore {

    /** One payment of a bill. {@code bankTxnId} and {@code linkId} are null for a card-side-only payment. */
    public record Row(UUID id, UUID userId, UUID statementId, UUID cardTxnId, UUID bankTxnId, boolean bankTxnCreated,
                      BillPaymentOrigin origin, UUID linkId, OffsetDateTime createdAt) {
    }

    /** A card credit of a payment dated inside a later statement's period but not on any statement (duplicate risk). */
    public record Unmerged(UUID userId, UUID paymentId, UUID cardTxnId, UUID statementId, UUID laterStatementId) {
    }

    private static final String COLUMNS = "id, user_id, statement_id, card_txn_id, bank_txn_id, bank_txn_created, origin, link_id, created_at";

    private final JdbcTemplate jdbc;

    public BillPaymentStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public void insert(Row r) {
        jdbc.update("INSERT INTO bill_payments (" + COLUMNS + ") VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)",
                r.id().toString(), r.userId().toString(), r.statementId().toString(), r.cardTxnId().toString(),
                r.bankTxnId() == null ? null : r.bankTxnId().toString(), r.bankTxnCreated() ? 1 : 0, r.origin().name(),
                r.linkId() == null ? null : r.linkId().toString(), r.createdAt() != null ? r.createdAt() : OffsetDateTime.now(AppTime.zone()));
    }

    public List<Row> byStatement(UUID userId, UUID statementId) {
        return jdbc.query("SELECT " + COLUMNS + " FROM bill_payments WHERE user_id = ? AND statement_id = ? ORDER BY created_at, id",
                (rs, i) -> row(rs), userId.toString(), statementId.toString());
    }

    public List<Row> byStatements(UUID userId, Collection<UUID> statementIds) {
        List<Row> out = new ArrayList<>();
        List<UUID> list = new ArrayList<>(statementIds);
        for (int i = 0; i < list.size(); i += 500) {
            List<UUID> chunk = list.subList(i, Math.min(i + 500, list.size()));
            List<Object> args = new ArrayList<>();
            args.add(userId.toString());
            chunk.forEach(id -> args.add(id.toString()));
            out.addAll(jdbc.query("SELECT " + COLUMNS + " FROM bill_payments WHERE user_id = ? AND statement_id IN ("
                    + marks(chunk.size()) + ") ORDER BY created_at, id", (rs, n) -> row(rs), args.toArray()));
        }
        return out;
    }

    /** The payment whose CC_PAYMENT link is {@code linkId}, else the one whose card credit or bank debit is {@code transactionId}. */
    public Optional<Row> findByLinkOrTransactions(UUID userId, UUID linkId, Collection<UUID> transactionIds) {
        Optional<Row> byLink = jdbc.query("SELECT " + COLUMNS + " FROM bill_payments WHERE user_id = ? AND link_id = ?",
                (rs, i) -> row(rs), userId.toString(), linkId.toString()).stream().findFirst();
        if (byLink.isPresent() || transactionIds.isEmpty()) {
            return byLink;
        }
        List<Object> args = new ArrayList<>();
        args.add(userId.toString());
        transactionIds.forEach(id -> args.add(id.toString()));
        transactionIds.forEach(id -> args.add(id.toString()));
        return jdbc.query("SELECT " + COLUMNS + " FROM bill_payments WHERE user_id = ? AND (card_txn_id IN ("
                + marks(transactionIds.size()) + ") OR bank_txn_id IN (" + marks(transactionIds.size()) + "))",
                (rs, i) -> row(rs), args.toArray()).stream().findFirst();
    }

    /** Remembers that this bank debit must not pay this bill again (auto_link_rejections, kind BILL_PAYMENT; idempotent). */
    public void addRejection(UUID userId, UUID bankTxnId, UUID statementId) {
        jdbc.update("INSERT INTO auto_link_rejections (user_id, kind, transaction_id, other_ref, created_at) "
                + "SELECT ?, 'BILL_PAYMENT', ?, ?, ? FROM dual WHERE NOT EXISTS (SELECT 1 FROM auto_link_rejections "
                + "WHERE user_id = ? AND kind = 'BILL_PAYMENT' AND transaction_id = ? AND other_ref = ?)",
                userId.toString(), bankTxnId.toString(), statementId.toString(), OffsetDateTime.now(AppTime.zone()),
                userId.toString(), bankTxnId.toString(), statementId.toString());
    }

    /** The user's remembered (debit, bill) pairs, as {@code debitId|statementId}. */
    public Set<String> rejectedPairs(UUID userId) {
        Set<String> out = new HashSet<>();
        jdbc.query("SELECT transaction_id, other_ref FROM auto_link_rejections WHERE user_id = ? AND kind = 'BILL_PAYMENT'",
                rs -> {
                    out.add(rs.getString(1) + "|" + rs.getString(2));
                }, userId.toString());
        return out;
    }

    /** The statement's manual paid mark date, null when none. */
    public java.time.LocalDate paidMarkedOn(UUID statementId) {
        List<java.sql.Date> rows = jdbc.query("SELECT paid_marked_on FROM statement_credit_card_details WHERE statement_id = ?",
                (rs, i) -> rs.getDate(1), statementId.toString());
        return rows.isEmpty() || rows.get(0) == null ? null : rows.get(0).toLocalDate();
    }

    public int delete(UUID userId, UUID id) {
        return jdbc.update("DELETE FROM bill_payments WHERE user_id = ? AND id = ?", userId.toString(), id.toString());
    }

    /** Which of {@code ids} already pay a bill from the bank side. */
    public Set<UUID> bankTxnIdsUsed(UUID userId, Collection<UUID> ids) {
        Set<UUID> out = new HashSet<>();
        List<UUID> list = new ArrayList<>(ids);
        for (int i = 0; i < list.size(); i += 500) {
            List<UUID> chunk = list.subList(i, Math.min(i + 500, list.size()));
            List<Object> args = new ArrayList<>();
            args.add(userId.toString());
            chunk.forEach(id -> args.add(id.toString()));
            jdbc.query("SELECT bank_txn_id FROM bill_payments WHERE user_id = ? AND bank_txn_id IN (" + marks(chunk.size()) + ")",
                    rs -> {
                        out.add(UUID.fromString(rs.getString(1)));
                    }, args.toArray());
        }
        return out;
    }

    /** Which of {@code ids} are the card credit of a payment (the statement merge widens its window for these). */
    public Set<UUID> cardTxnIdsAmong(Collection<UUID> ids) {
        Set<UUID> out = new HashSet<>();
        List<UUID> list = new ArrayList<>(ids);
        for (int i = 0; i < list.size(); i += 500) {
            List<UUID> chunk = list.subList(i, Math.min(i + 500, list.size()));
            jdbc.query("SELECT card_txn_id FROM bill_payments WHERE card_txn_id IN (" + marks(chunk.size()) + ")",
                    rs -> {
                        out.add(UUID.fromString(rs.getString(1)));
                    }, chunk.stream().map(UUID::toString).toArray());
        }
        return out;
    }

    /** Which of {@code ids} a statement lists as a line. */
    public Set<UUID> statementAttached(Collection<UUID> ids) {
        Set<UUID> out = new HashSet<>();
        List<UUID> list = new ArrayList<>(ids);
        for (int i = 0; i < list.size(); i += 500) {
            List<UUID> chunk = list.subList(i, Math.min(i + 500, list.size()));
            jdbc.query("SELECT DISTINCT transaction_id FROM statement_transactions WHERE transaction_id IN (" + marks(chunk.size()) + ")",
                    rs -> {
                        out.add(UUID.fromString(rs.getString(1)));
                    }, chunk.stream().map(UUID::toString).toArray());
        }
        return out;
    }

    /**
     * Payments whose card credit sits on no statement although a statement of the same card, imported after
     * the payment was recorded, covers its date: the credit was probably paid twice (it and the statement's line).
     */
    public List<Unmerged> unmerged() {
        return jdbc.query("SELECT bp.user_id, bp.id, bp.card_txn_id, bp.statement_id, s2.id FROM bill_payments bp "
                + "JOIN transactions t ON t.id = bp.card_txn_id "
                + "JOIN statements s2 ON s2.account_id = t.account_id AND s2.id <> bp.statement_id "
                + "AND s2.period_start <= t.transaction_date AND s2.period_end >= t.transaction_date "
                + "AND s2.created_at > bp.created_at "
                + "WHERE NOT EXISTS (SELECT 1 FROM statement_transactions st WHERE st.transaction_id = bp.card_txn_id) "
                + "ORDER BY bp.created_at, bp.id",
                (rs, i) -> new Unmerged(UUID.fromString(rs.getString(1)), UUID.fromString(rs.getString(2)),
                        UUID.fromString(rs.getString(3)), UUID.fromString(rs.getString(4)), UUID.fromString(rs.getString(5))));
    }

    /** Statement ids of the user's payments grouped (for the bill response). */
    public Map<UUID, List<Row>> groupedByStatement(UUID userId, Collection<UUID> statementIds) {
        Map<UUID, List<Row>> out = new java.util.LinkedHashMap<>();
        for (Row r : byStatements(userId, statementIds)) {
            out.computeIfAbsent(r.statementId(), k -> new ArrayList<>()).add(r);
        }
        return out;
    }

    private static String marks(int n) {
        return String.join(",", java.util.Collections.nCopies(n, "?"));
    }

    private static Row row(java.sql.ResultSet rs) throws java.sql.SQLException {
        String bank = rs.getString(5);
        String link = rs.getString(8);
        java.sql.Timestamp created = rs.getTimestamp(9);
        return new Row(UUID.fromString(rs.getString(1)), UUID.fromString(rs.getString(2)), UUID.fromString(rs.getString(3)),
                UUID.fromString(rs.getString(4)), bank == null ? null : UUID.fromString(bank), rs.getInt(6) != 0,
                BillPaymentOrigin.valueOf(rs.getString(7)), link == null ? null : UUID.fromString(link),
                created == null ? null : created.toInstant().atZone(AppTime.zone()).toOffsetDateTime());
    }
}
