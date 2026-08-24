package com.financeos.chat;

import com.financeos.chat.db.ChatSqlRejectedException;
import com.financeos.chat.db.ChatSqlValidator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.*;

class ChatSqlValidatorTest {

    private ChatSqlValidator validator;

    @BeforeEach
    void setUp() {
        validator = new ChatSqlValidator();
    }

    @Test
    @DisplayName("Accept valid SELECT queries over v_chat_* views")
    void acceptValidQueries() {
        assertDoesNotThrow(() -> validator.validate("SELECT * FROM v_chat_transactions"));
        assertDoesNotThrow(() -> validator.validate("SELECT t.id, c.name FROM v_chat_transactions t JOIN v_chat_categories c ON t.category_id = c.id WHERE t.amount > 100"));
        assertDoesNotThrow(() -> validator.validate("SELECT account_id, SUM(amount) AS total FROM v_chat_transactions GROUP BY account_id HAVING SUM(amount) > 500"));
    }

    @Test
    @DisplayName("Accept CTE queries over v_chat_* views")
    void acceptCteQueries() {
        String sql = """
                WITH summary AS (
                    SELECT account_id, SUM(amount) AS total_spend
                    FROM v_chat_transactions
                    WHERE direction = 'DEBIT'
                    GROUP BY account_id
                )
                SELECT s.account_id, a.name, s.total_spend
                FROM summary s
                JOIN v_chat_accounts a ON s.account_id = a.id
                """;
        assertDoesNotThrow(() -> validator.validate(sql));
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "DROP TABLE users",
            "INSERT INTO v_chat_transactions (id) VALUES ('123')",
            "UPDATE transactions SET amount = 0",
            "DELETE FROM accounts",
            "MERGE INTO accounts USING dual ON (1=1) WHEN MATCHED THEN UPDATE SET name = 'x'",
            "CREATE TABLE hack (id INT)",
            "GRANT ALL ON users TO PUBLIC"
    })
    @DisplayName("Reject non-SELECT DML and DDL statements")
    void rejectDmlAndDdl(String dml) {
        assertThrows(ChatSqlRejectedException.class, () -> validator.validate(dml));
    }

    @Test
    @DisplayName("Reject multi-statement queries")
    void rejectMultiStatements() {
        assertThrows(ChatSqlRejectedException.class, () -> validator.validate("SELECT * FROM v_chat_transactions; DROP TABLE users;"));
    }

    @Test
    @DisplayName("Reject SELECT ... INTO queries")
    void rejectSelectInto() {
        assertThrows(ChatSqlRejectedException.class, () -> validator.validate("SELECT amount INTO :var FROM v_chat_transactions"));
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "SELECT * FROM users",
            "SELECT * FROM transactions",
            "SELECT * FROM accounts",
            "SELECT * FROM v_chat_transactions t JOIN users u ON t.user_id = u.id"
    })
    @DisplayName("Reject queries accessing non-allowlisted base tables")
    void rejectBaseTables(String sql) {
        assertThrows(ChatSqlRejectedException.class, () -> validator.validate(sql));
    }

    @Test
    @DisplayName("Reject schema-qualified table references")
    void rejectSchemaQualified() {
        assertThrows(ChatSqlRejectedException.class, () -> validator.validate("SELECT * FROM admin.v_chat_transactions"));
    }

    @Test
    @DisplayName("Reject database link references")
    void rejectDbLinks() {
        assertThrows(ChatSqlRejectedException.class, () -> validator.validate("SELECT * FROM v_chat_transactions@remote_db"));
    }

    @Test
    @DisplayName("Reject optimizer hints")
    void rejectHints() {
        assertThrows(ChatSqlRejectedException.class, () -> validator.validate("SELECT /*+ FULL(t) */ * FROM v_chat_transactions t"));
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "SELECT DBMS_SESSION.SET_IDENTIFIER('x') FROM v_chat_transactions",
            "SELECT UTL_HTTP.REQUEST('http://evil.com') FROM v_chat_transactions",
            "SELECT * FROM SYS.USERS"
    })
    @DisplayName("Reject DBMS/UTL/SYS package calls")
    void rejectBannedPackages(String sql) {
        assertThrows(ChatSqlRejectedException.class, () -> validator.validate(sql));
    }

    @Test
    @DisplayName("Reject UNION branch touching raw table")
    void rejectUnionWithRawTable() {
        String sql = "SELECT id FROM v_chat_transactions UNION ALL SELECT id FROM users";
        assertThrows(ChatSqlRejectedException.class, () -> validator.validate(sql));
    }

    @Test
    @DisplayName("Reject CTE wrapping raw table")
    void rejectCteWithRawTable() {
        String sql = "WITH secret AS (SELECT * FROM users) SELECT * FROM secret";
        assertThrows(ChatSqlRejectedException.class, () -> validator.validate(sql));
    }

    @Test
    @DisplayName("Reject subquery touching raw table")
    void rejectSubqueryWithRawTable() {
        String sql = "SELECT * FROM v_chat_transactions WHERE id IN (SELECT id FROM users)";
        assertThrows(ChatSqlRejectedException.class, () -> validator.validate(sql));
    }
}
