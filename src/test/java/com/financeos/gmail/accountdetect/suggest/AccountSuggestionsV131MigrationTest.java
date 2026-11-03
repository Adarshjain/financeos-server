package com.financeos.gmail.accountdetect.suggest;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/** V131 adds parent_declined inside the swallowing wrapper (re-run is a no-op). */
class AccountSuggestionsV131MigrationTest {

    @Test
    void v131AddsItsColumnIdempotently() throws Exception {
        String norm = Files.readString(Path.of("src/main/resources/db/migration/V131__account_suggestions_parent_declined.sql")).replaceAll("\\s+", " ");
        assertThat(norm).contains("run_ddl_swallowing('ALTER TABLE account_suggestions ADD (parent_declined NUMBER(1) DEFAULT 0 NOT NULL)', -1430);");
    }
}
