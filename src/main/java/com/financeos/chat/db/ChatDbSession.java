package com.financeos.chat.db;

import com.financeos.core.security.UserContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.sql.DataSource;
import java.sql.CallableStatement;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Objects;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * AutoCloseable wrapper enforcing tenant isolation on CHAT_RO database connections.
 * 
 * On creation (against Oracle):
 *   1. Borrows connection from chatDataSource;
 *   2. Sets CURRENT_SCHEMA to the configured app schema;
 *   3. Calls DBMS_SESSION.SET_IDENTIFIER(<currentUserId>) with the UUID from UserContext.
 *
 * On close():
 *   Calls DBMS_SESSION.CLEAR_IDENTIFIER and returns the connection to the pool.
 *
 * Security Invariant: If SET_IDENTIFIER fails on Oracle, the connection is closed
 * immediately and no queries can be executed.
 */
public class ChatDbSession implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(ChatDbSession.class);
    private static final Pattern SCHEMA_PATTERN = Pattern.compile("^[A-Z0-9_]+$", Pattern.CASE_INSENSITIVE);

    private final Connection connection;
    private boolean identifierSet = false;
    private boolean isOracle = false;

    public ChatDbSession(DataSource dataSource, String appSchema) throws SQLException {
        Objects.requireNonNull(dataSource, "chatDataSource cannot be null. Is CHAT_RO configured?");
        if (appSchema == null || !SCHEMA_PATTERN.matcher(appSchema.trim()).matches()) {
            throw new IllegalArgumentException("Invalid app-schema name: " + appSchema);
        }

        UUID userId = UserContext.getCurrentUserId();
        if (userId == null) {
            throw new IllegalStateException("Cannot open ChatDbSession without an authenticated user in UserContext.");
        }

        Connection conn = null;
        try {
            conn = dataSource.getConnection();
            String dbProduct = conn.getMetaData().getDatabaseProductName();
            this.isOracle = dbProduct != null && dbProduct.toLowerCase().contains("oracle");

            if (isOracle) {
                // Set current schema for resolution of v_chat_* unqualified view names
                try (Statement stmt = conn.createStatement()) {
                    stmt.execute("ALTER SESSION SET CURRENT_SCHEMA = " + appSchema.trim());
                }

                // Set session identifier for SYS_CONTEXT('USERENV', 'CLIENT_IDENTIFIER')
                try (CallableStatement call = conn.prepareCall("BEGIN DBMS_SESSION.SET_IDENTIFIER(?); END;")) {
                    call.setString(1, userId.toString());
                    call.execute();
                }
            } else {
                log.debug("ChatDbSession connected to non-Oracle database ({}); skipping DBMS_SESSION identifier", dbProduct);
            }

            this.connection = conn;
            this.identifierSet = true;
        } catch (Throwable t) {
            if (conn != null) {
                try {
                    conn.close();
                } catch (SQLException ex) {
                    t.addSuppressed(ex);
                }
            }
            log.error("Failed to initialize ChatDbSession for user {}: {}", userId, t.getMessage());
            throw (t instanceof SQLException sqle) ? sqle : new SQLException("Failed to setup chat session", t);
        }
    }

    public Connection getConnection() {
        if (!identifierSet || connection == null) {
            throw new IllegalStateException("ChatDbSession connection is not open or identifier state is invalid.");
        }
        return connection;
    }

    @Override
    public void close() {
        if (connection == null) {
            return;
        }
        try {
            if (isOracle && identifierSet && !connection.isClosed()) {
                try (CallableStatement call = connection.prepareCall("BEGIN DBMS_SESSION.CLEAR_IDENTIFIER; END;")) {
                    call.execute();
                } catch (SQLException e) {
                    log.warn("Failed to clear DBMS_SESSION identifier on session close: {}", e.getMessage());
                }
            }
        } catch (SQLException e) {
            log.warn("Error checking connection state during ChatDbSession close: {}", e.getMessage());
        } finally {
            try {
                if (!connection.isClosed()) {
                    connection.close();
                }
            } catch (SQLException e) {
                log.error("Error closing CHAT_RO connection: {}", e.getMessage());
            }
        }
    }
}
