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
            }
        }

        // Verify that every table with a user_id column is accounted for in the V1-V79 cascade design
        Set<String> expectedUserTables = Set.of(
                "accounts", "account_bank_details", "account_credit_card_details", "account_broker_details",
                "account_cards", "transactions", "categories", "statements", "statement_credit_card_details",
                "transaction_links", "holdings", "investment_transactions", "dividends", "sips",
                "trade_settlement_classifications", "loans", "loan_events", "loan_payments", "loan_charges",
                "counterparties", "lendings", "reward_rules", "reward_milestones", "reward_cap_buckets",
                "jobs", "gmail_processed_messages", "gmail_sync_cursors", "dashboards", "reports",
                "category_rules", "gmail_connections", "gmail_senders", "gmail_backfill_demand",
                "llm_api_keys", "llm_task_prefs", "fno_trades"
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
