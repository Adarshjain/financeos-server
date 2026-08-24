package com.financeos.chat;

import com.financeos.chat.db.ChatDbSession;
import com.financeos.core.security.UserContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import javax.sql.DataSource;
import java.sql.CallableStatement;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

class ChatDbSessionTest {

    private DataSource mockDataSource;
    private Connection mockConnection;
    private Statement mockStatement;
    private CallableStatement mockCallableStatement;
    private UUID userId;

    @BeforeEach
    void setUp() throws Exception {
        userId = UUID.randomUUID();
        UserContext.setCurrentUserId(userId);

        mockDataSource = mock(DataSource.class);
        mockConnection = mock(Connection.class);
        mockStatement = mock(Statement.class);
        mockCallableStatement = mock(CallableStatement.class);
        java.sql.DatabaseMetaData mockMetaData = mock(java.sql.DatabaseMetaData.class);

        when(mockMetaData.getDatabaseProductName()).thenReturn("Oracle Database 21c");
        when(mockConnection.getMetaData()).thenReturn(mockMetaData);
        when(mockDataSource.getConnection()).thenReturn(mockConnection);
        when(mockConnection.createStatement()).thenReturn(mockStatement);
        when(mockConnection.prepareCall(anyString())).thenReturn(mockCallableStatement);
    }

    @AfterEach
    void tearDown() {
        UserContext.clear();
    }

    @Test
    @DisplayName("Successfully set CURRENT_SCHEMA and DBMS_SESSION identifier")
    void openSessionSuccess() throws Exception {
        try (ChatDbSession session = new ChatDbSession(mockDataSource, "ADMIN")) {
            assertNotNull(session.getConnection());
            verify(mockStatement).execute("ALTER SESSION SET CURRENT_SCHEMA = ADMIN");
            verify(mockCallableStatement).setString(1, userId.toString());
            verify(mockCallableStatement).execute();
        }

        verify(mockConnection).close();
    }

    @Test
    @DisplayName("Close connection immediately if SET_IDENTIFIER fails")
    void failIfSetIdentifierFails() throws Exception {
        doThrow(new SQLException("ORA-01031: insufficient privileges"))
                .when(mockCallableStatement).execute();

        assertThrows(SQLException.class, () -> new ChatDbSession(mockDataSource, "ADMIN"));

        verify(mockConnection).close();
    }

    @Test
    @DisplayName("Fail if no user identity is set in UserContext")
    void failIfNoUserContext() {
        UserContext.clear();
        assertThrows(IllegalStateException.class, () -> new ChatDbSession(mockDataSource, "ADMIN"));
    }
}
