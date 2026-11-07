package com.financeos.gmail.accountdetect.unlock;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** Equitas: DDMM then the first three letters of the name. Made-up profile: Riya Sharma, born 14 Aug. */
class EquitasPasswordRuleTest {

    @Test
    void equitasAccountPatternsStartWithDdmmAndName3() {
        assertThat(StatementPasswordRules.bundled().patterns("equitas", "account"))
                .containsExactly("DDMM+NAME3U", "DDMM+NAME3L", "NAME4U+DDMMYYYY");
    }

    @Test
    void ddmmThenNameThreeRendersBothCases() {
        assertThat(PasswordRender.render("DDMM+NAME3U", "Riya Sharma", "1994-08-14", List.of()).stream().map(PasswordRender.Candidate::password).toList())
                .containsExactly("1408RIY");
        assertThat(PasswordRender.render("DDMM+NAME3L", "Riya Sharma", "1994-08-14", List.of()).stream().map(PasswordRender.Candidate::password).toList())
                .containsExactly("1408riy");
    }
}
