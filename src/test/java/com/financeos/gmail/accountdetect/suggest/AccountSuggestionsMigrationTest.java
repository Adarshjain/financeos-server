package com.financeos.gmail.accountdetect.suggest;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * V129 shape: the CREATE runs inside the swallowing wrapper (re-run is a no-op), the table cascades with its user,
 * links to accounts / cards / parent suggestions clear on delete, and keys are unique per user. Applying it to Oracle is
 * checked by booting the server against the dev database.
 */
class AccountSuggestionsMigrationTest {

    private static final Path V129 = Path.of("src/main/resources/db/migration/V129__account_suggestions.sql");

    @Test
    void v129IsIdempotentAndCascadesWithTheUser() throws Exception {
        String norm = Files.readString(V129).replaceAll("\\s+", " ");
        assertThat(norm).contains("run_ddl_swallowing('CREATE TABLE account_suggestions (");
        assertThat(norm).contains(")', -955);");
        assertThat(norm).contains("FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE");
        assertThat(norm).contains("FOREIGN KEY (parent_account_id) REFERENCES accounts(id) ON DELETE SET NULL");
        assertThat(norm).contains("FOREIGN KEY (parent_suggestion_id) REFERENCES account_suggestions(id) ON DELETE SET NULL");
        assertThat(norm).contains("CONSTRAINT uq_acct_sugg_user_key UNIQUE (user_id, suggestion_key)");
        for (String col : new String[]{"kind", "bank", "last4", "variant", "variant_conf", "variant_candidates", "limit_bucket",
                "limit_conf", "lifecycle", "lifecycle_conf", "exists_conf", "band", "first_seen_on", "last_seen_on",
                "evidence_mails", "candidate_account_id", "state", "resolved_account_id", "resolved_card_id", "notified_at",
                "created_at", "updated_at"}) {
            assertThat(norm).as(col).containsPattern("\\b" + col + " ");
        }
        assertThat(norm).contains("''BANK_ACCOUNT'', ''CREDIT_CARD'', ''DEBIT_CARD'', ''ADDON_CARD'', ''REPLACEMENT_CARD''");
        assertThat(norm).contains("''NEW'', ''ADDED'', ''NOT_MINE'', ''BELONGS'', ''MATCHED''");
    }

    @Test
    void v130AddsItsColumnsIdempotentlyAndLeavesV129Alone() throws Exception {
        String norm = Files.readString(Path.of("src/main/resources/db/migration/V130__account_suggestions_v2.sql")).replaceAll("\\s+", " ");
        assertThat(norm).contains("run_ddl_swallowing('ALTER TABLE account_suggestions ADD (kind_conf VARCHAR2(10))', -1430);");
        assertThat(norm).contains("run_ddl_swallowing('ALTER TABLE account_suggestions ADD (is_this_declined NUMBER(1) DEFAULT 0 NOT NULL)', -1430);");
        assertThat(Files.readString(V129)).doesNotContain("kind_conf").doesNotContain("is_this_declined");
    }
}
