package com.financeos.domain.user;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class AccountDeletionExecutorTest {

    @Mock
    private JdbcTemplate jdbcTemplate;

    private AccountDeletionExecutor executor;
    private UUID userId;

    @BeforeEach
    void setUp() {
        executor = new AccountDeletionExecutor(jdbcTemplate);
        userId = UUID.randomUUID();
    }

    @SuppressWarnings("unchecked")
    private void dictionaryReturns(List<String> tables) {
        when(jdbcTemplate.query(contains("user_tab_columns"), any(RowMapper.class))).thenReturn(tables);
    }

    private void countsAre(long count) {
        when(jdbcTemplate.queryForObject(startsWith("SELECT COUNT(*)"), eq(Long.class), eq(userId.toString())))
                .thenReturn(count);
    }

    @Test
    void userScopedTables_dropsUsersAndMalformedNames() {
        dictionaryReturns(List.of("ACCOUNTS", "TRANSACTIONS", "USERS", "BAD NAME; DROP", "ACCOUNTS"));

        List<String> tables = executor.userScopedTables();

        assertEquals(List.of("ACCOUNTS", "TRANSACTIONS"), tables);
    }

    @Test
    void userScopedTables_failsClosedWhenTheDictionaryIsUnreadable() {
        when(jdbcTemplate.query(contains("user_tab_columns"), any(RowMapper.class)))
                .thenThrow(new DataAccessResourceFailureException("no connection"));

        IllegalStateException ex = assertThrows(IllegalStateException.class, () -> executor.userScopedTables());
        assertTrue(ex.getMessage().contains("Cannot enumerate user-scoped tables"));
    }

    @Test
    void userScopedTables_failsClosedOnAnEmptyResult() {
        dictionaryReturns(List.of());

        // An empty list would make the verification sweep iterate nothing and silently
        // "pass" — the one failure mode a completeness guard must never have.
        assertThrows(IllegalStateException.class, () -> executor.userScopedTables());
    }

    @Test
    void userScopedTables_failsClosedWhenACanaryTableIsMissing() {
        dictionaryReturns(List.of("ACCOUNTS", "DASHBOARDS"));

        IllegalStateException ex = assertThrows(IllegalStateException.class, () -> executor.userScopedTables());
        assertTrue(ex.getMessage().contains("TRANSACTIONS"));
    }

    @Test
    void deleteUserAndVerify_deletesOnceAndSweepsEveryTable() {
        dictionaryReturns(List.of("ACCOUNTS", "TRANSACTIONS"));
        when(jdbcTemplate.update(startsWith("DELETE FROM users"), eq(userId.toString()))).thenReturn(1);
        countsAre(0L);

        executor.deleteUserAndVerify(userId);

        verify(jdbcTemplate).update("DELETE FROM users WHERE id = ?", userId.toString());
        verify(jdbcTemplate).queryForObject(contains("\"ACCOUNTS\""), eq(Long.class), eq(userId.toString()));
        verify(jdbcTemplate).queryForObject(contains("\"TRANSACTIONS\""), eq(Long.class), eq(userId.toString()));
    }

    @Test
    void deleteUserAndVerify_throwsWhenRowsSurvive() {
        dictionaryReturns(List.of("ACCOUNTS", "TRANSACTIONS"));
        when(jdbcTemplate.update(startsWith("DELETE FROM users"), eq(userId.toString()))).thenReturn(1);
        countsAre(4L);

        IllegalStateException ex = assertThrows(IllegalStateException.class, () -> executor.deleteUserAndVerify(userId));

        assertTrue(ex.getMessage().contains("verification failed"));
        assertTrue(ex.getMessage().contains("ACCOUNTS"));
    }

    @Test
    void deleteUserAndVerify_throwsWhenNoUserRowWasDeleted() {
        dictionaryReturns(List.of("ACCOUNTS", "TRANSACTIONS"));
        when(jdbcTemplate.update(startsWith("DELETE FROM users"), eq(userId.toString()))).thenReturn(0);

        IllegalStateException ex = assertThrows(IllegalStateException.class, () -> executor.deleteUserAndVerify(userId));
        assertTrue(ex.getMessage().contains("expected to delete 1 users row"));
    }

    @Test
    void deleteUserAndVerify_resolvesTheTableListBeforeDeletingAnything() {
        when(jdbcTemplate.query(contains("user_tab_columns"), any(RowMapper.class)))
                .thenThrow(new DataAccessResourceFailureException("no connection"));

        assertThrows(IllegalStateException.class, () -> executor.deleteUserAndVerify(userId));

        verify(jdbcTemplate, never()).update(startsWith("DELETE FROM users"), anyString());
    }

    @Test
    void countRowsForUser_returnsOnlyNonZeroCounts() {
        dictionaryReturns(List.of("ACCOUNTS", "TRANSACTIONS"));
        when(jdbcTemplate.queryForObject(contains("\"ACCOUNTS\""), eq(Long.class), eq(userId.toString()))).thenReturn(2L);
        when(jdbcTemplate.queryForObject(contains("\"TRANSACTIONS\""), eq(Long.class), eq(userId.toString()))).thenReturn(0L);

        Map<String, Long> counts = executor.countRowsForUser(userId);

        assertEquals(Map.of("accounts", 2L), counts);
    }
}
