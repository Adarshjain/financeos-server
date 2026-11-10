package com.financeos.api.statement;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.financeos.domain.account.Account;
import com.financeos.domain.account.AccountRepository;
import com.financeos.domain.statement.Statement;
import com.financeos.domain.statement.StatementDraft;
import com.financeos.domain.statement.StatementPersistenceService;
import com.financeos.domain.statement.StatementSource;
import com.financeos.domain.statement.StatementVerdict;
import com.financeos.domain.statement.file.StatementFileStore;
import com.financeos.domain.user.User;
import com.financeos.domain.user.UserRepository;
import com.financeos.support.ApiTestClient;
import com.financeos.support.UserDataCleanup;
import java.io.ByteArrayOutputStream;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Deleting a statement removes its stored original file over real persistence (the bulk "unreferenced" delete must
 * see the statement delete), keeps a file another statement still references, and never touches another user's file.
 */
@SpringBootTest
@AutoConfigureMockMvc
class StatementDeletionFileIntegrationTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper mapper;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private UserRepository userRepository;
    @Autowired private AccountRepository accountRepository;
    @Autowired private StatementPersistenceService statementPersistenceService;
    @Autowired private StatementFileStore fileStore;

    private ApiTestClient api;
    private UUID userId;
    private UUID otherUserId;
    private UUID accountId;
    private Statement first;
    private Statement second;
    private UUID fileId;
    private UUID otherUsersFileId;

    @BeforeEach
    void setUp() throws Exception {
        String email = "stmt-file-" + UUID.randomUUID() + "@example.test";
        api = ApiTestClient.signUp(mockMvc, mapper, email);
        userId = userRepository.findByEmail(email).orElseThrow().getId();
        String otherEmail = "stmt-file-other-" + UUID.randomUUID() + "@example.test";
        ApiTestClient.signUp(mockMvc, mapper, otherEmail);
        otherUserId = userRepository.findByEmail(otherEmail).orElseThrow().getId();

        accountId = api.create("/api/v1/accounts", Map.of("type", "bank_account", "name", "File Savings", "last4", "7483",
                "openingBalance", 0, "financialPosition", "asset", "excludeFromNetAsset", false));
        User user = userRepository.findById(userId).orElseThrow();
        Account account = accountRepository.findById(accountId).orElseThrow();
        first = statementPersistenceService.createIfNew(user, account, StatementSource.file_upload, "feb.pdf", "sha-feb",
                draft(LocalDate.of(2026, 2, 1), LocalDate.of(2026, 2, 28))).orElseThrow();
        second = statementPersistenceService.createIfNew(user, account, StatementSource.file_upload, "mar.pdf", "sha-mar",
                draft(LocalDate.of(2026, 3, 1), LocalDate.of(2026, 3, 31))).orElseThrow();

        fileId = fileStore.store(userId, pdf(1), "feb.pdf").orElseThrow();
        otherUsersFileId = fileStore.store(otherUserId, pdf(2), "other.pdf").orElseThrow();
        jdbc.update("UPDATE statements SET file_id = ? WHERE id = ?", fileId.toString(), first.getId().toString());
    }

    @AfterEach
    void tearDown() {
        UserDataCleanup.deleteUsers(jdbc, List.of(userId, otherUserId));
    }

    @Test
    void deletingTheStatementRemovesItsFileRow() throws Exception {
        api.deleteJson(path(first), 200);

        assertEquals(0, files(fileId));
    }

    @Test
    void theFileStaysWhenAnotherStatementReferencesIt() throws Exception {
        jdbc.update("UPDATE statements SET file_id = ? WHERE id = ?", fileId.toString(), second.getId().toString());

        api.deleteJson(path(first), 200);

        assertEquals(1, files(fileId));
        api.deleteJson(path(second), 200);
        assertEquals(0, files(fileId), "gone once the last statement referencing it is deleted");
    }

    @Test
    void anotherUsersFileIsUntouched() throws Exception {
        api.deleteJson(path(first), 200);

        assertEquals(1, files(otherUsersFileId));
    }

    @Test
    void aStatementWithoutAFileDeletesCleanlyAndLeavesFilesAlone() throws Exception {
        api.deleteJson(path(second), 200);

        assertEquals(1, files(fileId));
    }

    private int files(UUID id) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM statement_files WHERE id = ?", Integer.class, id.toString());
    }

    private String path(Statement s) {
        return "/api/v1/accounts/" + accountId + "/statements/" + s.getId();
    }

    /** A distinct blank PDF per page count (distinct bytes, so a distinct sha). */
    private static byte[] pdf(int pages) throws Exception {
        try (PDDocument doc = new PDDocument(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            for (int i = 0; i < pages; i++) {
                doc.addPage(new PDPage());
            }
            doc.save(out);
            return out.toByteArray();
        }
    }

    private static StatementDraft draft(LocalDate start, LocalDate end) {
        return new StatementDraft("bank_account", start, end, BigDecimal.ZERO, new BigDecimal("100.00"), "HSBC",
                "001-234567-006", 3, 0, new BigDecimal("10.00"), new BigDecimal("110.00"), "balance_chain",
                new BigDecimal("100.0"), true, StatementVerdict.AUTO_INGEST, Map.of());
    }
}
