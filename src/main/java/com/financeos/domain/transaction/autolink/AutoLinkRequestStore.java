package com.financeos.domain.transaction.autolink;

import java.sql.Date;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Pending auto-link requests, one row per user, written by api nodes and claimed by the worker (see V168). A request
 * widens an existing row (scope union, ALL wins) and bumps its version; a claim deletes only the version it read.
 */
@Component
public class AutoLinkRequestStore {

    public record Pending(UUID userId, LinkScope scope, long version) {
    }

    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;

    public AutoLinkRequestStore(JdbcTemplate jdbc, PlatformTransactionManager transactionManager) {
        this.jdbc = jdbc;
        this.tx = new TransactionTemplate(transactionManager);
        this.tx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    /** Stores the request, merged with the user's existing row, in its own transaction. */
    public void request(UUID userId, LinkScope scope) {
        try {
            tx.executeWithoutResult(s -> upsert(userId, scope));
        } catch (DuplicateKeyException e) {
            tx.executeWithoutResult(s -> upsert(userId, scope));   // another node inserted first; widen its row
        }
    }

    private void upsert(UUID userId, LinkScope scope) {
        List<LinkScope> existing = jdbc.query(
                "SELECT from_date, to_date FROM autolink_requests WHERE user_id = ? FOR UPDATE",
                (rs, i) -> scopeOf(rs.getDate(1), rs.getDate(2)), userId.toString());
        if (existing.isEmpty()) {
            jdbc.update("INSERT INTO autolink_requests (user_id, from_date, to_date, version) VALUES (?, ?, ?, 1)",
                    userId.toString(), sql(scope.from()), sql(scope.to()));
            return;
        }
        LinkScope merged = existing.get(0).union(scope);
        jdbc.update("UPDATE autolink_requests SET from_date = ?, to_date = ?, version = version + 1, "
                        + "requested_at = CURRENT_TIMESTAMP WHERE user_id = ?",
                sql(merged.from()), sql(merged.to()), userId.toString());
    }

    public List<Pending> pending() {
        return jdbc.query("SELECT user_id, from_date, to_date, version FROM autolink_requests ORDER BY requested_at",
                (rs, i) -> new Pending(UUID.fromString(rs.getString(1)), scopeOf(rs.getDate(2), rs.getDate(3)),
                        rs.getLong(4)));
    }

    /** Deletes the row if it is still the version that was read; false when it was widened (or taken) meanwhile. */
    public boolean claim(Pending p) {
        return jdbc.update("DELETE FROM autolink_requests WHERE user_id = ? AND version = ?",
                p.userId().toString(), p.version()) == 1;
    }

    private static Date sql(LocalDate d) {
        return d == null ? null : Date.valueOf(d);
    }

    private static LinkScope scopeOf(Date from, Date to) {
        return from == null || to == null ? LinkScope.ALL : LinkScope.of(from.toLocalDate(), to.toLocalDate());
    }
}
