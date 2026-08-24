package com.financeos.chat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.financeos.chat.db.ChatProperties;
import com.financeos.chat.db.ChatSqlExecutor;
import com.financeos.chat.db.ChatSqlFailedException;
import com.financeos.chat.db.ChatSqlValidator;
import com.financeos.core.security.UserContext;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.Statement;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class ChatSqlExecutorTest {

    private JdbcDataSource dataSource;
    private ChatSqlExecutor executor;
    private ObjectMapper objectMapper;
    private UUID userId;

    public static void setIdentifierStub(String id) {}
    public static void clearIdentifierStub() {}

    @BeforeEach
    void setUp() throws Exception {
        userId = UUID.randomUUID();
        UserContext.setCurrentUserId(userId);

        dataSource = new JdbcDataSource();
        dataSource.setURL("jdbc:h2:mem:chattest_" + userId.toString().replace("-", "") + ";MODE=Oracle;DB_CLOSE_DELAY=-1");

        try (Connection conn = dataSource.getConnection(); Statement stmt = conn.createStatement()) {
            stmt.execute("CREATE SCHEMA IF NOT EXISTS DBMS_SESSION");
            stmt.execute("CREATE ALIAS IF NOT EXISTS \"DBMS_SESSION.SET_IDENTIFIER\" FOR \"com.financeos.chat.ChatSqlExecutorTest.setIdentifierStub\"");
            stmt.execute("CREATE ALIAS IF NOT EXISTS \"DBMS_SESSION.CLEAR_IDENTIFIER\" FOR \"com.financeos.chat.ChatSqlExecutorTest.clearIdentifierStub\"");

            stmt.execute("CREATE TABLE v_chat_transactions (id VARCHAR(36), amount DECIMAL(19,4), transaction_date DATE, description VARCHAR(255))");
            stmt.execute("INSERT INTO v_chat_transactions VALUES ('t1', 100.50, DATE '2026-08-01', 'Grocery')");
            stmt.execute("INSERT INTO v_chat_transactions VALUES ('t2', 250.00, DATE '2026-08-02', 'Dinner')");
        }

        ChatProperties properties = new ChatProperties();
        properties.setAppSchema("PUBLIC");
        properties.getLoop().setSqlRowCap(5);
        properties.getLoop().setSqlTimeoutSeconds(5);
        properties.getLoop().setResultCharCap(1000);

        ChatSqlValidator validator = new ChatSqlValidator();
        objectMapper = new ObjectMapper();
        executor = new ChatSqlExecutor(dataSource, validator, properties, objectMapper);
    }

    @AfterEach
    void tearDown() {
        UserContext.clear();
    }

    @Test
    @DisplayName("Execute query and return compact JSON result")
    void executeQuerySuccess() throws Exception {
        String json = executor.execute("SELECT id, amount, description FROM v_chat_transactions");
        JsonNode root = objectMapper.readTree(json);

        assertEquals(2, root.get("rowCount").asInt());
        assertFalse(root.get("truncatedAtRowCap").asBoolean());
        assertFalse(root.get("truncated").asBoolean());
        assertEquals(3, root.get("columns").size());
        assertEquals("id", root.get("columns").get(0).asText());
    }

    @Test
    @DisplayName("Truncate rows when character cap is exceeded")
    void truncateOnCharCap() throws Exception {
        ChatProperties properties = new ChatProperties();
        properties.setAppSchema("PUBLIC");
        properties.getLoop().setSqlRowCap(50);
        properties.getLoop().setSqlTimeoutSeconds(5);
        properties.getLoop().setResultCharCap(90); // extremely small cap

        ChatSqlValidator validator = new ChatSqlValidator();
        ChatSqlExecutor smallCapExecutor = new ChatSqlExecutor(dataSource, validator, properties, objectMapper);

        String json = smallCapExecutor.execute("SELECT id, amount, description FROM v_chat_transactions");
        JsonNode root = objectMapper.readTree(json);

        assertTrue(root.get("truncated").asBoolean());
        assertNotNull(root.get("truncationNote"));
    }

    @Test
    @DisplayName("Genericize database execution errors without leaking raw ORA details")
    void genericizeErrors() {
        ChatSqlFailedException ex = assertThrows(ChatSqlFailedException.class, () ->
                executor.execute("SELECT * FROM v_chat_categories") // table does not exist in mem db
        );

        assertFalse(ex.getMessage().contains("Table \"V_CHAT_CATEGORIES\" not found"));
        assertTrue(ex.getMessage().contains("Unknown table/view"));
    }
}
