package com.financeos.gmail.accountdetect.unlock;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** Yes Bank cards: the birth date as DDMMYYYY from July 2026; before that the name's first 4 letters in capitals + DDMM. Made-up profiles only. */
class YesCardPasswordRuleTest {

    @Test
    void yesCardPatternsStartWithTheBirthDate() {
        assertThat(StatementPasswordRules.bundled().patterns("yes", "credit_card"))
                .containsExactly("DDMMYYYY", "NAME4U+DDMM", "CUSTID+DDMMYYYY");
    }

    /** The two examples the bank's own statement mail gives for the older format. */
    @Test
    void theOlderRuleGivesTheBanksOwnExamples() {
        assertThat(PasswordRender.render("NAME4U+DDMM", "Timesh Mishra", "1982-03-12", List.of()).stream().map(PasswordRender.Candidate::password).toList())
                .containsExactly("TIME1203");
        assertThat(PasswordRender.render("NAME4U+DDMM", "T R E Eesan", "1984-08-01", List.of()).stream().map(PasswordRender.Candidate::password).toList())
                .containsExactly("TREE0108");
    }

    @Test
    void theBirthDateRuleIsRenderableAndGivesOnePassword() {
        assertThat(PasswordRender.renderable("DDMMYYYY")).isTrue();
        assertThat(PasswordRender.render("DDMMYYYY", "Riya Sharma", "1994-08-14", List.of()).stream().map(PasswordRender.Candidate::password).toList())
                .containsExactly("14081994");
    }

    @Test
    void aBirthDateWithoutAYearGivesNothing() {
        assertThat(PasswordRender.render("DDMMYYYY", "Riya Sharma", "--08-14", List.of())).isEmpty();
    }

    @Test
    void yesAccountPatternsAreUnchanged() {
        assertThat(StatementPasswordRules.bundled().patterns("yes", "account"))
                .containsExactly("CUSTID+DDMMYYYY", "PAN5+DDMMYYYY");
    }
}
