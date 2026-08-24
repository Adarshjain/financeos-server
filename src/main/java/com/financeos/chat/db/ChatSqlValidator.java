package com.financeos.chat.db;

import net.sf.jsqlparser.parser.CCJSqlParserUtil;
import net.sf.jsqlparser.statement.Statement;
import net.sf.jsqlparser.statement.Statements;
import net.sf.jsqlparser.statement.select.*;
import net.sf.jsqlparser.schema.Table;
import net.sf.jsqlparser.util.TablesNamesFinder;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

@Component
public class ChatSqlValidator {

    public static final Set<String> ALLOWED_VIEWS = Set.of(
            "V_CHAT_TRANSACTIONS",
            "V_CHAT_TRANSACTION_CATEGORIES",
            "V_CHAT_ACCOUNTS",
            "V_CHAT_CATEGORIES",
            "V_CHAT_INVESTMENT_TRADES",
            "V_CHAT_HOLDINGS",
            "V_CHAT_DIVIDENDS",
            "V_CHAT_FNO_TRADES",
            "V_CHAT_LOANS",
            "V_CHAT_LOAN_PAYMENTS",
            "V_CHAT_LOAN_CHARGES",
            "V_CHAT_LENDINGS",
            "V_CHAT_INSTRUMENTS",
            "V_CHAT_INSTRUMENT_PRICES"
    );

    private static final Pattern BANNED_RAW_TEXT_PATTERN = Pattern.compile(
            "\\bDBMS_\\w+|\\bUTL_\\w+|\\bSYS\\.",
            Pattern.CASE_INSENSITIVE
    );

    private static final Pattern HINT_PATTERN = Pattern.compile("/\\*\\+");

    public String validate(String rawSql) {
        if (rawSql == null || rawSql.isBlank()) {
            throw new ChatSqlRejectedException("EMPTY_SQL", "SQL query cannot be empty");
        }

        String trimmedSql = rawSql.trim();

        // 1. Raw text safety checks
        if (trimmedSql.contains("@")) {
            throw new ChatSqlRejectedException("DBLINK_DISALLOWED", "Database links (@dblink) are not allowed");
        }

        if (HINT_PATTERN.matcher(trimmedSql).find()) {
            throw new ChatSqlRejectedException("HINT_DISALLOWED", "Optimizer hints /*+ ... */ are not allowed");
        }

        if (BANNED_RAW_TEXT_PATTERN.matcher(trimmedSql).find()) {
            throw new ChatSqlRejectedException("BANNED_PACKAGE", "SYS/DBMS/UTL references are strictly forbidden");
        }

        // Check for multiple statements separated by semicolon
        String sqlWithoutTrailingSemicolon = trimmedSql;
        if (trimmedSql.endsWith(";")) {
            sqlWithoutTrailingSemicolon = trimmedSql.substring(0, trimmedSql.length() - 1).trim();
        }
        if (sqlWithoutTrailingSemicolon.contains(";")) {
            throw new ChatSqlRejectedException("MULTIPLE_STATEMENTS", "Only a single SELECT statement is allowed");
        }

        // 2. Parse statement using JSqlParser
        Statements statements;
        try {
            statements = CCJSqlParserUtil.parseStatements(sqlWithoutTrailingSemicolon);
        } catch (Exception e) {
            throw new ChatSqlRejectedException("PARSE_ERROR", "Failed to parse SQL query: " + e.getMessage());
        }

        if (statements.getStatements().size() != 1) {
            throw new ChatSqlRejectedException("MULTIPLE_STATEMENTS", "Only a single SELECT statement is allowed");
        }

        Statement statement = statements.getStatements().get(0);
        if (!(statement instanceof Select select)) {
            throw new ChatSqlRejectedException("ONLY_SELECT_ALLOWED", "Only SELECT statements are permitted");
        }

        // 3. Inspect SELECT statement details
        Set<String> definedCtes = extractCteNames(select);

        // Disallow SELECT ... INTO
        if (select.getSelectBody() instanceof PlainSelect plainSelect) {
            if (plainSelect.getIntoTables() != null && !plainSelect.getIntoTables().isEmpty()) {
                throw new ChatSqlRejectedException("SELECT_INTO_DISALLOWED", "SELECT ... INTO statements are not allowed");
            }
        }

        // Extract and inspect all table objects
        CustomTablesFinder finder = new CustomTablesFinder();
        List<Table> tables = finder.getTables(select);

        for (Table table : tables) {
            String tableName = table.getName();
            if (tableName == null) {
                continue;
            }
            String upperName = tableName.toUpperCase(Locale.ROOT);

            // Check dblink or database reference
            boolean hasDbLink = (tableName != null && tableName.contains("@")) ||
                    (table.getDatabase() != null && (table.getDatabase().getServer() != null || table.getDatabase().getDatabaseName() != null));
            if (hasDbLink) {
                throw new ChatSqlRejectedException("DBLINK_DISALLOWED", "Database links (@dblink) are not allowed");
            }

            // Check schema qualification (x.y)
            if (table.getSchemaName() != null) {
                throw new ChatSqlRejectedException("SCHEMA_QUALIFIED_DISALLOWED", "Schema-qualified table names are not allowed");
            }

            // Validate against allowlist or defined CTEs
            if (!ALLOWED_VIEWS.contains(upperName) && !definedCtes.contains(upperName)) {
                throw new ChatSqlRejectedException("TABLE_NOT_ALLOWED", "Access to table/view '" + tableName + "' is not permitted");
            }
        }

        return sqlWithoutTrailingSemicolon;
    }

    private Set<String> extractCteNames(Select select) {
        Set<String> ctes = new HashSet<>();
        if (select.getWithItemsList() != null) {
            for (WithItem withItem : select.getWithItemsList()) {
                if (withItem.getAlias() != null && withItem.getAlias().getName() != null) {
                    ctes.add(withItem.getAlias().getName().toUpperCase(Locale.ROOT));
                }
            }
        }
        return ctes;
    }

    private static class CustomTablesFinder extends TablesNamesFinder {
        private final List<Table> tableObjects = new ArrayList<>();

        public List<Table> getTables(Select select) {
            tableObjects.clear();
            getTableList((Statement) select);
            return tableObjects;
        }

        @Override
        public void visit(Table tableName) {
            tableObjects.add(tableName);
            super.visit(tableName);
        }
    }
}
