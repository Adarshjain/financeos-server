package com.financeos.gmail.accountdetect.job;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * V127 shape: every DDL statement is wrapped so a re-run is a no-op (ORA-00955 / ORA-01408 swallowed), both tables
 * cascade with their user (account deletion), and facts are unique per (user, message) and indexed by date.
 * Applying it to Oracle is checked by booting the server against the dev database.
 */
class AccountDetectMigrationTest {

    private static final Path V127 = Path.of("src/main/resources/db/migration/V127__account_detect.sql");

    @Test
    void v127IsIdempotentAndCascadesWithTheUser() throws Exception {
        String sql = Files.readString(V127);
        String norm = sql.replaceAll("\\s+", " ");

        assertThat(count(norm, "run_ddl_swallowing\\('CREATE TABLE account_detect_state ")).isEqualTo(1);
        assertThat(count(norm, "run_ddl_swallowing\\('CREATE TABLE account_detect_facts ")).isEqualTo(1);
        assertThat(norm).contains("CREATE INDEX idx_acct_detect_facts_date ON account_detect_facts (user_id, internal_date)', -955, -1408)");
        // every CREATE runs inside the swallowing wrapper
        assertThat(count(norm, "CREATE (TABLE|INDEX)")).isEqualTo(count(norm, "run_ddl_swallowing\\('CREATE"));
        assertThat(count(norm, "\\)', -955")).isEqualTo(3);

        assertThat(norm).contains("FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE");
        assertThat(count(norm, "ON DELETE CASCADE")).isEqualTo(2);
        assertThat(norm).contains("CONSTRAINT uq_acct_detect_facts_msg UNIQUE (user_id, message_id)");
        assertThat(norm).contains("CONSTRAINT pk_account_detect_state PRIMARY KEY (user_id)");
        assertThat(norm).contains("facts CLOB NOT NULL");
        for (String col : new String[]{"connection_id", "status", "marker_at", "first_scan_done_at", "last_run_at", "last_error",
                "mails_seen", "mails_total", "updated_at", "message_id", "internal_date", "sender_domain", "role", "has_pdf",
                "pdf_locked", "pdf_attachment_id", "unlock_state", "created_at"}) {
            assertThat(norm).as(col).containsPattern("\\b" + col + " ");
        }
    }

    @Test
    void v127IsTheOnlyMigrationWithItsVersion() throws Exception {
        try (var s = Files.list(Path.of("src/main/resources/db/migration"))) {
            assertThat(s.filter(p -> p.getFileName().toString().startsWith("V127__")).count()).isEqualTo(1);
        }
        try (var s = Files.list(Path.of("src/main/java/db/migration"))) {
            assertThat(s.filter(p -> p.getFileName().toString().startsWith("V127__")).count()).isZero();
        }
    }

    private static int count(String s, String regex) {
        var m = Pattern.compile(regex).matcher(s);
        int n = 0;
        while (m.find()) n++;
        return n;
    }
}
