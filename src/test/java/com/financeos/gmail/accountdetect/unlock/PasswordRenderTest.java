package com.financeos.gmail.accountdetect.unlock;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** Made-up profile: Riya Sharma, born 14 Aug 1994. */
class PasswordRenderTest {

    private static List<String> passwords(String rule, String name, String dob, List<String> cards) {
        return PasswordRender.render(rule, name, dob, cards).stream().map(PasswordRender.Candidate::password).toList();
    }

    @Test
    void firstNameDropsSalutationsAndDots() {
        assertThat(PasswordRender.firstName("Dr. Riya Sharma")).isEqualTo("Riya");
        assertThat(PasswordRender.firstName("Mrs Riya")).isEqualTo("Riya");
        assertThat(PasswordRender.firstName("Mr.")).isNull();
        assertThat(PasswordRender.firstName(null)).isNull();
    }

    @Test
    void taggedNameAndDateTokens() {
        assertThat(passwords("NAME4U+DDMM", "Riya Sharma", "1994-08-14", List.of())).containsExactly("RIYA1408");
        assertThat(passwords("NAME4L+DDMMYY", "Riya Sharma", "1994-08-14", List.of())).containsExactly("riya140894");
        assertThat(passwords("NAME3U+DDMMYYYY", "Riya Sharma", "1994-08-14", List.of())).containsExactly("RIY14081994");
    }

    @Test
    void untaggedNameGivesUpperThenLowerAndTheRuleKeepsItsTag() {
        List<PasswordRender.Candidate> c = PasswordRender.render("NAME4+DDMM", "Riya Sharma", "1994-08-14", List.of());
        assertThat(c).extracting(PasswordRender.Candidate::password).containsExactly("RIYA1408", "riya1408");
        assertThat(c).extracting(PasswordRender.Candidate::rule).containsExactly("NAME4U+DDMM", "NAME4L+DDMM");
    }

    @Test
    void shortFirstNameIsUsedWhole() {
        assertThat(passwords("NAME4U+DDMM", "Al Bee", "1994-08-14", List.of())).containsExactly("AL1408", "ALBE1408");
    }

    @Test
    void aFirstNameUnderFourLettersAlsoTriesTheFirstFourLettersOfTheFullName() {
        // initials: the full name with spaces and dots removed
        assertThat(passwords("NAME4L+DDMM", "Raj Kumar Sharma", "1994-08-14", List.of())).containsExactly("raj1408", "rajk1408");
        assertThat(passwords("NAME4U+DDMM", "R. K. Sharma", "1994-08-14", List.of())).containsExactly("RKSH1408");
    }

    @Test
    void aSingleLetterFirstNameIsNeverUsedAlone() {
        assertThat(passwords("NAME3U", "S Kumar", null, List.of())).containsExactly("SKU");
        assertThat(passwords("NAME4U", "S Kumar", null, List.of())).containsExactly("SKUM");
        assertThat(PasswordRender.nameBases("S")).isEmpty();
    }

    @Test
    void cardFourTakesEachNumberGiven() {
        assertThat(passwords("CARD4", null, null, List.of("4417", "1234"))).containsExactly("4417", "1234");
        assertThat(passwords("NAME4U+CARD4", "Riya Sharma", null, List.of("4417"))).containsExactly("RIYA4417");
        assertThat(passwords("CARD4", null, null, List.of())).isEmpty();
    }

    @Test
    void partialBirthDateGivesDdmmOnly() {
        assertThat(passwords("DDMM", null, "--08-14", List.of())).containsExactly("1408");
        assertThat(passwords("DDMMYY", null, "--08-14", List.of())).isEmpty();
    }

    @Test
    void unsupportedTokensOrMissingProfileRenderNothing() {
        assertThat(PasswordRender.render("CUSTID+DDMMYYYY", "Riya Sharma", "1994-08-14", List.of())).isEmpty();
        assertThat(PasswordRender.render("PAN5+DDMMYYYY", "Riya Sharma", "1994-08-14", List.of())).isEmpty();
        assertThat(PasswordRender.render("NAME4U+DDMM", null, "1994-08-14", List.of())).isEmpty();
        assertThat(PasswordRender.render("NAME4U+DDMM", "Riya Sharma", null, List.of())).isEmpty();
        assertThat(PasswordRender.renderable("NAME4U+CRN")).isFalse();
        assertThat(PasswordRender.renderable("NAME4U+DDMM")).isTrue();
    }

    @Test
    void renderOneForACreatedAccount() {
        assertThat(PasswordRender.renderOne("NAME4U+CARD4", "Riya Sharma", "1994-08-14", "4417")).isEqualTo("RIYA4417");
        assertThat(PasswordRender.renderOne("NAME4U+DDMM", null, "1994-08-14", "4417")).isNull();
    }
}
