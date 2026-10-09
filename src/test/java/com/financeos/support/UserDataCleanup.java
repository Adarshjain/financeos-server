package com.financeos.support;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.UUID;

/**
 * Removes every row integration tests created for their users from the shared in-memory H2
 * (one database for all {@code @SpringBootTest} classes, so leftovers leak into other classes).
 *
 * <p>Whole-account deletion ({@code /auth/me/delete}) reads Oracle's data dictionary and cannot run
 * on H2, so this walks H2's own schema instead: rows of tables without a {@code user_id} that hang
 * off a user-owned parent (transaction categories, review reasons, statement lines, …) go first,
 * then every table with a {@code user_id} is emptied of the users' rows, repeating while foreign
 * keys between those tables still block a delete, and the users last.
 */
public final class UserDataCleanup {

    /** Parent tables whose children may lack a user_id: child column → parent table. */
    private static final List<String[]> PARENT_KEYS = List.of(
            new String[]{"transaction_id", "transactions"},
            new String[]{"statement_id", "statements"},
            new String[]{"account_id", "accounts"},
            new String[]{"loan_id", "loans"},
            new String[]{"holding_id", "holdings"},
            new String[]{"report_id", "reports"},
            new String[]{"dashboard_id", "dashboards"});

    private static final int MAX_PASSES = 12;

    private UserDataCleanup() {
    }

    /** Deletes everything the users own, then the users. */
    public static void deleteUsers(JdbcTemplate jdbc, Collection<UUID> userIds) {
        List<UUID> ids = userIds.stream().filter(java.util.Objects::nonNull).toList();
        if (ids.isEmpty()) {
            return;
        }
        String in = String.join(",", ids.stream().map(id -> "'" + id + "'").toList());

        List<String> ownedTables = jdbc.queryForList(
                "SELECT TABLE_NAME FROM INFORMATION_SCHEMA.COLUMNS WHERE TABLE_SCHEMA = 'PUBLIC' "
                        + "AND COLUMN_NAME = 'user_id' AND TABLE_NAME <> 'users'", String.class);

        for (String[] parentKey : PARENT_KEYS) {
            List<String> children = jdbc.queryForList(
                    "SELECT c.TABLE_NAME FROM INFORMATION_SCHEMA.COLUMNS c WHERE c.TABLE_SCHEMA = 'PUBLIC' "
                            + "AND c.COLUMN_NAME = ? AND NOT EXISTS (SELECT 1 FROM INFORMATION_SCHEMA.COLUMNS u "
                            + "WHERE u.TABLE_SCHEMA = 'PUBLIC' AND u.TABLE_NAME = c.TABLE_NAME AND u.COLUMN_NAME = 'user_id')",
                    String.class, parentKey[0]);
            for (String child : children) {
                deleteQuietly(jdbc, "DELETE FROM " + child + " WHERE " + parentKey[0] + " IN (SELECT id FROM "
                        + parentKey[1] + " WHERE user_id IN (" + in + "))");
            }
        }

        List<String> remaining = new ArrayList<>(ownedTables);
        for (int pass = 0; pass < MAX_PASSES && !remaining.isEmpty(); pass++) {
            List<String> blocked = new ArrayList<>();
            for (String table : remaining) {
                if (!deleteQuietly(jdbc, "DELETE FROM " + table + " WHERE user_id IN (" + in + ")")) {
                    blocked.add(table);
                }
            }
            remaining = blocked;
        }
        if (!remaining.isEmpty()) {
            throw new IllegalStateException("Could not clean up the test users' rows in " + remaining);
        }
        jdbc.update("DELETE FROM users WHERE id IN (" + in + ")");
    }

    /** Deletes instruments the test created (instruments are a shared catalog, not user-owned). */
    public static void deleteInstruments(JdbcTemplate jdbc, Collection<UUID> instrumentIds) {
        for (UUID id : instrumentIds) {
            List<String> children = jdbc.queryForList(
                    "SELECT TABLE_NAME FROM INFORMATION_SCHEMA.COLUMNS WHERE TABLE_SCHEMA = 'PUBLIC' "
                            + "AND COLUMN_NAME = 'instrument_id'", String.class);
            for (String child : children) {
                jdbc.update("DELETE FROM " + child + " WHERE instrument_id = ?", id.toString());
            }
            jdbc.update("DELETE FROM instruments WHERE id = ?", id.toString());
        }
    }

    private static boolean deleteQuietly(JdbcTemplate jdbc, String sql) {
        try {
            jdbc.update(sql);
            return true;
        } catch (DataIntegrityViolationException e) {
            return false;
        }
    }
}
