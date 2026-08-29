package com.financeos.domain.user;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * The database half of account deletion, kept in its own bean on purpose.
 *
 * {@link AccountDeletionService} used to hold {@link #deleteUserAndVerify} itself and
 * call it directly. Spring's {@code @Transactional} is proxy-based, so a call from one
 * method of a class to another never leaves the object and the annotation did nothing:
 * the DELETE ran on an autocommit connection and the verification below could not roll
 * it back. Crossing a bean boundary is what makes the transaction real.
 */
@Component
public class AccountDeletionExecutor {

    private static final Pattern VALID_TABLE_NAME = Pattern.compile("^[A-Z0-9_$#]+$");

    /**
     * Tables that must always come back from the dictionary. If they are missing, the
     * query returned something we do not understand and no deletion should be trusted.
     */
    private static final List<String> CANARY_TABLES = List.of("ACCOUNTS", "TRANSACTIONS");

    private final JdbcTemplate jdbcTemplate;

    public AccountDeletionExecutor(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /**
     * Every table carrying a {@code user_id}, straight from the data dictionary.
     *
     * This is the guard for the one thing {@code ON DELETE CASCADE} cannot see: a
     * {@code user_id} column with no foreign key behind it (which is exactly what
     * {@code fno_trades} was before V79). It therefore has to fail closed — an
     * unreadable dictionary means we cannot claim a deletion was complete, so we
     * refuse rather than verify nothing and report success.
     */
    public List<String> userScopedTables() {
        List<String> tables;
        try {
            tables = jdbcTemplate.query(
                    "SELECT c.table_name FROM user_tab_columns c "
                            + "JOIN user_tables t ON t.table_name = c.table_name "
                            + "WHERE c.column_name = 'USER_ID'",
                    (rs, rowNum) -> rs.getString("table_name"));
        } catch (RuntimeException e) {
            throw new IllegalStateException(
                    "Cannot enumerate user-scoped tables; refusing to delete account data", e);
        }

        List<String> valid = tables.stream()
                .filter(t -> t != null && VALID_TABLE_NAME.matcher(t).matches())
                .filter(t -> !"USERS".equals(t))
                .distinct()
                .toList();

        for (String canary : CANARY_TABLES) {
            if (!valid.contains(canary)) {
                throw new IllegalStateException(
                        "User-scoped table list is missing " + canary
                                + "; refusing to delete account data (got " + valid.size() + " tables)");
            }
        }
        return valid;
    }

    /** Per-table row counts for one user, non-zero entries only. */
    public Map<String, Long> countRowsForUser(UUID userId) {
        Map<String, Long> counts = new LinkedHashMap<>();
        for (String table : userScopedTables()) {
            Long count = countRows(table, userId);
            if (count != null && count > 0) {
                counts.put(table.toLowerCase(Locale.ROOT), count);
            }
        }
        return counts;
    }

    /**
     * Deletes the user and proves nothing of theirs survived, atomically.
     *
     * The single DELETE is all it takes: V79 rebuilt every {@code user_id} foreign key
     * as {@code ON DELETE CASCADE}, so Oracle walks the rest of the graph. The sweep
     * afterwards catches the case cascade is blind to, and because both statements share
     * one transaction, a leftover row rolls the deletion back instead of leaving a
     * half-deleted account behind.
     */
    @Transactional
    public void deleteUserAndVerify(UUID userId) {
        // Resolved before the DELETE so an unreadable dictionary aborts while the data
        // is still intact, rather than after it is gone.
        List<String> tables = userScopedTables();

        int deleted = jdbcTemplate.update("DELETE FROM users WHERE id = ?", userId.toString());
        if (deleted != 1) {
            throw new IllegalStateException(
                    "Account deletion aborted: expected to delete 1 users row for " + userId
                            + ", deleted " + deleted);
        }

        for (String table : tables) {
            Long count = countRows(table, userId);
            if (count != null && count > 0) {
                throw new IllegalStateException(
                        "Account deletion verification failed: table " + table
                                + " still contains " + count + " rows for user " + userId);
            }
        }
    }

    private Long countRows(String table, UUID userId) {
        // Safe to interpolate: the name came from the dictionary and matched VALID_TABLE_NAME.
        return jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM \"" + table + "\" WHERE user_id = ?",
                Long.class,
                userId.toString());
    }
}
