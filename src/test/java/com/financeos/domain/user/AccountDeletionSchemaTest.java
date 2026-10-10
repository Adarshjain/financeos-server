package com.financeos.domain.user;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

class AccountDeletionSchemaTest {

    private static final Pattern CREATE_TABLE_PATTERN = Pattern.compile(
            "CREATE\\s+TABLE\\s+([a-zA-Z0-9_]+)\\s*\\((.+?)\\);",
            Pattern.CASE_INSENSITIVE | Pattern.DOTALL
    );

    private static final Pattern DROP_TABLE_PATTERN = Pattern.compile(
            "DROP\\s+TABLE\\s+([a-zA-Z0-9_]+)",
            Pattern.CASE_INSENSITIVE
    );

    /** A user_id added to an existing table later (also inside an EXECUTE IMMEDIATE string). */
    private static final Pattern ADD_USER_ID_PATTERN = Pattern.compile(
            "ALTER\\s+TABLE\\s+([a-zA-Z0-9_]+)\\s+ADD\\s*\\(?\\s*user_id\\b",
            Pattern.CASE_INSENSITIVE
    );

    private static final Pattern USER_ID_COLUMN_PATTERN = Pattern.compile(
            "\\buser_id\\b",
            Pattern.CASE_INSENSITIVE
    );

    @Test
    void testAllUserScopedTablesAreKnownAndAccountedFor() throws IOException {
        Path migrationDir = Paths.get("src/main/resources/db/migration");
        if (!Files.exists(migrationDir)) {
            migrationDir = Paths.get("financeos-server/src/main/resources/db/migration");
        }
        assertTrue(Files.exists(migrationDir), "Flyway migrations directory must exist");

        Set<String> tablesWithUserId = new TreeSet<>(String.CASE_INSENSITIVE_ORDER);

        try (Stream<Path> paths = Files.list(migrationDir)) {
            List<Path> sqlFiles = paths
                    .filter(p -> p.toString().endsWith(".sql"))
                    .sorted(Comparator.comparing(Path::getFileName))
                    .toList();

            for (Path sqlFile : sqlFiles) {
                String content = Files.readString(sqlFile);
                Matcher dropMatcher = DROP_TABLE_PATTERN.matcher(content);
                while (dropMatcher.find()) {
                    String droppedTable = dropMatcher.group(1).trim().toLowerCase(Locale.ROOT);
                    tablesWithUserId.remove(droppedTable);
                }

                Matcher matcher = CREATE_TABLE_PATTERN.matcher(content);
                while (matcher.find()) {
                    String tableName = matcher.group(1).trim().toLowerCase(Locale.ROOT);
                    String tableBody = matcher.group(2);
                    if (USER_ID_COLUMN_PATTERN.matcher(tableBody).find()) {
                        tablesWithUserId.add(tableName);
                    }
                }
                Matcher addMatcher = ADD_USER_ID_PATTERN.matcher(content);
                while (addMatcher.find()) {
                    tablesWithUserId.add(addMatcher.group(1).trim().toLowerCase(Locale.ROOT));
                }
            }
        }

        // Java migrations (src/main/java/db/migration, e.g. V100) add user_id columns too.
        Path javaMigrationDir = migrationDir.resolve("../../../java/db/migration").normalize();
        assertTrue(Files.exists(javaMigrationDir), "Java migrations directory must exist: " + javaMigrationDir);
        try (Stream<Path> paths = Files.list(javaMigrationDir)) {
            for (Path javaFile : paths.filter(p -> p.toString().endsWith(".java")).sorted().toList()) {
                Matcher addMatcher = ADD_USER_ID_PATTERN.matcher(Files.readString(javaFile));
                while (addMatcher.find()) {
                    tablesWithUserId.add(addMatcher.group(1).trim().toLowerCase(Locale.ROOT));
                }
            }
        }
        assertTrue(tablesWithUserId.contains("corporate_actions"), "V100 makes corporate actions per user");
        String v100 = Files.readString(javaMigrationDir.resolve("V100__per_user_corporate_actions.java"));
        assertTrue(v100.contains("fk_corp_actions_user") && v100.contains("ON DELETE CASCADE"),
                "V100 must cascade corporate actions with their user");

        // Verify that every table with a user_id column is accounted for in the V1-V79 cascade design
        Set<String> expectedUserTables = Set.of(
                "accounts", "account_bank_details", "account_credit_card_details", "account_broker_details",
                "account_identifiers",
                "cardholders", "cards", "transactions", "categories", "statements", "statement_credit_card_details",
                "transaction_links", "holdings", "investment_transactions", "dividends", "sips",
                "trade_settlement_classifications", "loans", "loan_events", "loan_payments", "loan_charges",
                "counterparties", "lendings", "reward_rules", "reward_milestones", "reward_cap_buckets",
                "jobs", "gmail_processed_messages", "gmail_sync_cursors", "dashboards", "reports",
                "category_rules", "gmail_connections", "gmail_senders", "gmail_backfill_demand",
                "llm_api_keys", "llm_task_prefs", "fno_trades", "user_notification_settings",
                "inbox_item_state", "user_instrument_overrides", "instrument_prices", "instrument_aliases",
                "user_instrument_repoints", "corporate_actions"
        );

        for (String table : tablesWithUserId) {
            assertTrue(
                    expectedUserTables.contains(table.toLowerCase(Locale.ROOT)),
                    "Discovered table with user_id that is not in the account deletion cascade schema: " + table
            );
        }

        // Verify V79 migration exists and contains the required cascade rules
        Path v79Path = migrationDir.resolve("V79__user_cascade_and_fno_fk.sql");
        assertTrue(Files.exists(v79Path), "V79__user_cascade_and_fno_fk.sql must exist");
        String v79Content = Files.readString(v79Path);
        assertTrue(v79Content.contains("fk_fno_trades_user"), "V79 must define fk_fno_trades_user");
        assertTrue(v79Content.contains("ON DELETE CASCADE"), "V79 must enforce ON DELETE CASCADE");
    }
}
