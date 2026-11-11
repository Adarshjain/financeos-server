package com.financeos.gmail.accountdetect.unlock;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** Yes Bank cards: the birth date as DDMMYYYY, as the statement mail states. Made-up birth date: 14 Aug 1994. */
class YesCardPasswordRuleTest {

    @Test
    void yesCardPatternsStartWithTheBirthDate() {
        assertThat(StatementPasswordRules.bundled().patterns("yes", "credit_card"))
                .containsExactly("DDMMYYYY", "CUSTID+DDMMYYYY");
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
