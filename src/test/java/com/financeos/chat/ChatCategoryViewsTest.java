package com.financeos.chat;

import com.financeos.chat.db.ChatSqlValidator;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Ask the data after the categories redesign: the view migration (V122, Oracle only) defines the category,
 * sub-category and tag columns the chat dictionary documents, and the validator lets the model query them.
 * The views themselves are proven against Oracle when the server boots (see the wave notes).
 */
class ChatCategoryViewsTest {

    private static String read(String path) throws Exception {
        return Files.readString(Path.of(path), StandardCharsets.UTF_8);
    }

    @Test
    void transactionsViewCarriesCategorySubCategoryAndTags() throws Exception {
        String sql = read("src/main/resources/db/migration/V122__chat_views_categories.sql");
        for (String column : new String[] {"t.category_key", "tc.name AS category_name", "t.subcategory_key",
                "ts.name AS subcategory_name", "tg.tag_names"}) {
            assertTrue(sql.contains(column), column);
        }
        assertTrue(sql.contains("LISTAGG(g.name, ', ') WITHIN GROUP (ORDER BY g.name) AS tag_names"));
        assertFalse(sql.contains("AS category_names"), "category_names was the old per-user categories, now tag_names");
        assertTrue(sql.contains("WHERE t.user_id = SYS_CONTEXT('USERENV', 'CLIENT_IDENTIFIER')"));
        assertTrue(sql.contains("CREATE OR REPLACE VIEW v_chat_transaction_tags"));
        assertTrue(sql.contains("DROP VIEW v_chat_transaction_categories"));
    }

    @Test
    void theDictionaryDescribesEveryNewColumnAndView() throws Exception {
        String dictionary = read("src/main/resources/chat/dictionary.md");
        for (String name : new String[] {"`category_key` / `category_name`", "`subcategory_key` / `subcategory_name`",
                "`tag_names`", "`v_chat_transaction_tags`", "`v_chat_categories`", "`v_chat_subcategories`",
                "`v_chat_tags`"}) {
            assertTrue(dictionary.contains(name), name);
        }
        assertFalse(dictionary.contains("category_names"));
        assertFalse(dictionary.contains("v_chat_transaction_categories"));
    }

    @Test
    void theValidatorAllowsTheNewViewsAndNotTheRetiredOne() {
        ChatSqlValidator validator = new ChatSqlValidator();
        assertDoesNotThrow(() -> validator.validate(
                "SELECT t.category_key, SUM(t.amount) FROM v_chat_transactions t GROUP BY t.category_key"));
        assertDoesNotThrow(() -> validator.validate("SELECT s.name FROM v_chat_subcategories s"));
        assertDoesNotThrow(() -> validator.validate("SELECT x.tag_name FROM v_chat_transaction_tags x"));
        assertDoesNotThrow(() -> validator.validate("SELECT g.name FROM v_chat_tags g"));
        assertFalse(ChatSqlValidator.ALLOWED_VIEWS.contains("V_CHAT_TRANSACTION_CATEGORIES"));
    }
}
