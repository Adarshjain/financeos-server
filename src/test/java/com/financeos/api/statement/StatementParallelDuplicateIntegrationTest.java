package com.financeos.api.statement;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doReturn;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.financeos.domain.account.Account;
import com.financeos.domain.account.AccountRepository;
import com.financeos.domain.statement.StatementDraft;
import com.financeos.domain.statement.StatementPersistenceService;
import com.financeos.domain.statement.StatementRepository;
import com.financeos.domain.statement.StatementSource;
import com.financeos.domain.statement.StatementVerdict;
import com.financeos.domain.user.User;
import com.financeos.domain.user.UserRepository;
import com.financeos.support.ApiTestClient;
import com.financeos.support.UserDataCleanup;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.SpyBean;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Over real persistence: when a parallel upload slips past the existence checks, the unique key rejects the second
 * insert and {@code alreadyExists} then sees the winner's committed row; an unrelated statement is not a duplicate.
 */
@SpringBootTest
@AutoConfigureMockMvc
class StatementParallelDuplicateIntegrationTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper mapper;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private UserRepository userRepository;
    @Autowired private AccountRepository accountRepository;
    @Autowired private StatementPersistenceService service;
    @SpyBean private StatementRepository statementRepository;

    private UUID userId;
    private User user;
    private Account account;

    @BeforeEach
    void setUp() throws Exception {
        // The H2 schema is generated from the entities, which do not declare the production unique keys.
        jdbc.execute("CREATE UNIQUE INDEX IF NOT EXISTS uk_test_stmt_period ON statements (account_id, period_start, period_end)");
        jdbc.execute("CREATE UNIQUE INDEX IF NOT EXISTS uk_test_stmt_sha ON statements (account_id, file_sha256)");
        String email = "stmt-dup-" + UUID.randomUUID() + "@example.test";
        ApiTestClient api = ApiTestClient.signUp(mockMvc, mapper, email);
        userId = userRepository.findByEmail(email).orElseThrow().getId();
        UUID accountId = api.create("/api/v1/accounts", Map.of("type", "bank_account", "name", "Dup Savings", "last4", "7483",
                "openingBalance", 0, "financialPosition", "asset", "excludeFromNetAsset", false));
        user = userRepository.findById(userId).orElseThrow();
        account = accountRepository.findById(accountId).orElseThrow();
        service.createIfNew(user, account, StatementSource.file_upload, "a.pdf", "sha-a",
                draft(LocalDate.of(2026, 2, 1), LocalDate.of(2026, 2, 28))).orElseThrow();
    }

    @AfterEach
    void tearDown() {
        UserDataCleanup.deleteUsers(jdbc, List.of(userId));
        jdbc.execute("DROP INDEX IF EXISTS uk_test_stmt_period");
        jdbc.execute("DROP INDEX IF EXISTS uk_test_stmt_sha");
    }

    private void existenceChecksMiss() {
        doReturn(false).when(statementRepository).existsByAccountIdAndPeriodStartAndPeriodEnd(any(), any(), any());
        doReturn(false).when(statementRepository).existsByAccountIdAndFileSha256(any(), any());
    }

    @Test
    void theSamePeriodRacingPastTheChecksHitsTheUniqueKeyAndIsRecognisedAsDuplicate() {
        StatementDraft same = draft(LocalDate.of(2026, 2, 1), LocalDate.of(2026, 2, 28));
        existenceChecksMiss();

        assertThatThrownBy(() -> service.createIfNew(user, account, StatementSource.file_upload, "b.pdf", "sha-b", same))
                .isInstanceOf(DataIntegrityViolationException.class);
        org.mockito.Mockito.reset(statementRepository);

        assertThat(service.alreadyExists(account.getId(), same, "sha-b")).isTrue();
    }

    @Test
    void theSameFileRacingPastTheChecksHitsTheUniqueKeyAndIsRecognisedAsDuplicate() {
        StatementDraft otherPeriod = draft(LocalDate.of(2026, 3, 1), LocalDate.of(2026, 3, 31));
        existenceChecksMiss();

        assertThatThrownBy(() -> service.createIfNew(user, account, StatementSource.file_upload, "c.pdf", "sha-a", otherPeriod))
                .isInstanceOf(DataIntegrityViolationException.class);
        org.mockito.Mockito.reset(statementRepository);

        assertThat(service.alreadyExists(account.getId(), otherPeriod, "sha-a")).isTrue();
    }

    @Test
    void aStatementOfAnotherPeriodAndFileIsNotADuplicate() {
        assertThat(service.alreadyExists(account.getId(), draft(LocalDate.of(2026, 4, 1), LocalDate.of(2026, 4, 30)), "sha-z")).isFalse();
    }

    @Test
    void aDraftWithoutAPeriodIsADuplicateOnlyByFileHash() {
        StatementDraft noPeriod = draft(null, null);

        assertThat(service.alreadyExists(account.getId(), noPeriod, "sha-z")).isFalse();
        assertThat(service.alreadyExists(account.getId(), noPeriod, "sha-a")).isTrue();
        assertThat(service.alreadyExists(account.getId(), noPeriod, null)).isFalse();
    }

    private static StatementDraft draft(LocalDate start, LocalDate end) {
        return new StatementDraft("bank_account", start, end, BigDecimal.ZERO, new BigDecimal("100.00"), "HSBC",
                "001-234567-006", 3, 0, new BigDecimal("10.00"), new BigDecimal("110.00"), "balance_chain",
                new BigDecimal("100.0"), true, StatementVerdict.AUTO_INGEST, Map.of());
    }
}
