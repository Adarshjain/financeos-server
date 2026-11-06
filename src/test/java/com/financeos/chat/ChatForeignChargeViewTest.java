package com.financeos.chat;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertTrue;

/** Ask the data leaves out a foreign charge waiting for its statement (V165, Oracle only), and the dictionary says so. */
class ChatForeignChargeViewTest {

    @Test
    void theTransactionsViewKeepsV122AndDropsRowsWithNoRupeeAmount() throws Exception {
        String sql = Files.readString(Path.of("src/main/resources/db/migration/V165__chat_transactions_view_foreign.sql"),
                StandardCharsets.UTF_8);
        assertTrue(sql.contains("CREATE OR REPLACE VIEW v_chat_transactions AS"));
        assertTrue(sql.contains("tg.tag_names"));
        assertTrue(sql.contains("WHERE t.user_id = SYS_CONTEXT('USERENV', 'CLIENT_IDENTIFIER')\n  AND t.amount IS NOT NULL;"));
    }

    @Test
    void theDictionarySaysForeignChargesAwaitingTheirStatementAreLeftOut() throws Exception {
        String dictionary = Files.readString(Path.of("src/main/resources/chat/dictionary.md"), StandardCharsets.UTF_8);
        assertTrue(dictionary.contains("Foreign-currency charges still waiting for their statement"));
    }
}
