package com.financeos.domain.investment.imports;

import static org.assertj.core.api.Assertions.assertThat;

import com.financeos.statement.parser.ProtectedPdfs;
import java.io.ByteArrayInputStream;
import java.util.List;
import org.junit.jupiter.api.Test;

class CasParserPasswordCaseTest {

    private final CasParser parser = new CasParser();

    private String firstError(byte[] pdf, String password) {
        List<ParsedRow> rows = parser.parse(new ByteArrayInputStream(pdf), new ParseContext(null, password));
        return rows.isEmpty() ? "" : String.valueOf(rows.get(0).error());
    }

    @Test
    void opensWithTheOtherCaseOfTheTypedPassword() throws Exception {
        assertThat(firstError(ProtectedPdfs.pdf("abcd1234"), "ABCD1234")).doesNotContain("password");
        assertThat(firstError(ProtectedPdfs.pdf("ABCD1234"), "ABCD1234")).doesNotContain("password");
        assertThat(firstError(ProtectedPdfs.pdf("other"), "ABCD1234")).contains("password");
    }
}
