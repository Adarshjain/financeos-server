package com.financeos.chat;

import com.financeos.chat.db.ChatSqlValidator;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Ask the data counts spend and income with the report rule: the view column, the dictionary and the validator. */
class ChatCountsInTotalsTest {

    private static String read(String path) throws Exception {
        return Files.readString(Path.of(path), StandardCharsets.UTF_8);
    }

    @Test
    void theDictionaryDocumentsTheColumnAndDefinesSpendAndIncomeWithIt() throws Exception {
        String dictionary = read("src/main/resources/chat/dictionary.md");

        assertTrue(dictionary.contains("- `counts_in_totals`:"));
        assertTrue(dictionary.contains("**Spend**: Transactions where `direction = 'DEBIT' AND counts_in_totals = 1`"));
        assertTrue(dictionary.contains("**Income**: Transactions where `direction = 'CREDIT' AND counts_in_totals = 1`"));
        assertFalse(dictionary.contains("category_key NOT IN ('self_transfer'"), "no hard-coded category list in the definitions");
    }

    @Test
    void theMigrationDefinesTheColumnOnTheTransactionsView() throws Exception {
        String sql = read("src/main/resources/db/migration/V189__chat_transactions_counts_in_totals.sql");

        assertTrue(sql.contains("CREATE OR REPLACE VIEW v_chat_transactions AS"));
        assertTrue(sql.contains(" AS counts_in_totals"));
        assertTrue(sql.contains("WHERE t.user_id = SYS_CONTEXT('USERENV', 'CLIENT_IDENTIFIER')"));
    }

    @Test
    void theValidatorAllowsQueryingTheColumn() {
        assertDoesNotThrow(() -> new ChatSqlValidator().validate(
                "SELECT SUM(t.amount) FROM v_chat_transactions t WHERE t.direction = 'DEBIT' AND t.counts_in_totals = 1"));
    }
}
