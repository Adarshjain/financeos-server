package com.financeos.gmail.accountdetect.unlock;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** CARD6 rules, with a made-up birth date 1990-01-15 and card ending 654321. */
class PasswordRenderCard6Test {

    private static List<String> render(String rule, String dob, List<String> last6) {
        return PasswordRender.render(rule, "Riya Sharma", dob, List.of(), last6).stream()
                .map(PasswordRender.Candidate::password).toList();
    }

    @Test
    void card6RendersWithDdmmyy() {
        assertThat(PasswordRender.renderable("DDMMYY+CARD6")).isTrue();
        assertThat(render("DDMMYY+CARD6", "1990-01-15", List.of("654321"))).containsExactly("150190654321");
    }

    @Test
    void nothingWithoutALast6OrWithABadOne() {
        assertThat(render("DDMMYY+CARD6", "1990-01-15", null)).isEmpty();
        assertThat(render("DDMMYY+CARD6", "1990-01-15", List.of())).isEmpty();
        assertThat(render("DDMMYY+CARD6", "1990-01-15", List.of("4321"))).isEmpty();
        assertThat(render("DDMMYY+CARD6", "1990-01-15", List.of("65432a"))).isEmpty();
    }

    @Test
    void nothingWithoutAFullBirthDate() {
        assertThat(render("DDMMYY+CARD6", null, List.of("654321"))).isEmpty();
        assertThat(render("DDMMYY+CARD6", "--01-15", List.of("654321"))).isEmpty();
    }

    @Test
    void theFourArgRenderAndRenderOneGiveNothingForACard6Rule() {
        assertThat(PasswordRender.render("DDMMYY+CARD6", "Riya Sharma", "1990-01-15", List.of("4321"))).isEmpty();
        assertThat(PasswordRender.renderOne("DDMMYY+CARD6", "Riya Sharma", "1990-01-15", "4321")).isNull();
    }
}
