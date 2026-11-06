package com.financeos.domain.nps.statement;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.financeos.statement.parser.ProtectedPdfs;
import java.util.List;
import org.junit.jupiter.api.Test;

class NpsPasswordCaseTest {

    private final NpsStatementParser parser = new NpsStatementParser();

    @Test
    void unlockedByTheOtherCase() throws Exception {
        assertThat(parser.passwordOpens(ProtectedPdfs.pdf("ravi1406"), "RAVI1406")).isTrue();
        assertThat(parser.passwordOpens(ProtectedPdfs.pdf("RAVI1406"), "RAVI1406")).isTrue();
        assertThat(parser.passwordOpens(ProtectedPdfs.pdf("other"), "RAVI1406")).isFalse();
    }

    @Test
    void parseOpensWithTheOtherCaseSoItIsNotReportedLocked() throws Exception {
        // opens, then fails as "not an NPS statement" (a plain text page), never as locked
        assertThatThrownBy(() -> parser.parse(ProtectedPdfs.pdf("ravi1406"), List.of("RAVI1406")))
                .isNotInstanceOf(NpsStatementLockedException.class);
        assertThatThrownBy(() -> parser.parse(ProtectedPdfs.pdf("other"), List.of("RAVI1406")))
                .isInstanceOf(NpsStatementLockedException.class);
    }
}
