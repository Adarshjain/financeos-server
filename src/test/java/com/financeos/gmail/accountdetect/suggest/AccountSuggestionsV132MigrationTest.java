package com.financeos.gmail.accountdetect.suggest;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/** V132 adds the unlock and exact-limit columns inside the swallowing wrapper (a re-run is a no-op). */
class AccountSuggestionsV132MigrationTest {

    @Test
    void v132AddsItsColumnsIdempotently() throws Exception {
        String norm = Files.readString(Path.of("src/main/resources/db/migration/V132__account_detect_unlock.sql")).replaceAll("\\s+", " ");
        assertThat(norm).contains("run_ddl_swallowing('ALTER TABLE account_detect_facts ADD (unlock_hint VARCHAR2(200))', -1430);");
        assertThat(norm).contains("run_ddl_swallowing('ALTER TABLE account_detect_facts ADD (unlock_rule VARCHAR2(100))', -1430);");
        assertThat(norm).contains("run_ddl_swallowing('ALTER TABLE account_suggestions ADD (limit_amount NUMBER(15,2))', -1430);");
        assertThat(norm).contains("run_ddl_swallowing('ALTER TABLE account_suggestions ADD (password_rule VARCHAR2(100))', -1430);");
        assertThat(norm).contains("run_ddl_swallowing('ALTER TABLE account_suggestions ADD (not_in_your_name NUMBER(1) DEFAULT 0 NOT NULL)', -1430);");
        assertThat(norm).contains("run_ddl_swallowing('ALTER TABLE account_detect_state ADD (unlock_gen NUMBER(10) DEFAULT 0 NOT NULL)', -1430);");
        assertThat(norm).contains("run_ddl_swallowing('ALTER TABLE account_detect_state ADD (holder_gen NUMBER(10) DEFAULT 0 NOT NULL)', -1430);");
        assertThat(norm).contains("run_ddl_swallowing('ALTER TABLE account_detect_facts ADD (unlock_tried_gen NUMBER(10) DEFAULT 0 NOT NULL)', -1430);");
        assertThat(norm).contains("run_ddl_swallowing('ALTER TABLE account_detect_facts ADD (holder_checked_gen NUMBER(10) DEFAULT 0 NOT NULL)', -1430);");
    }
}
