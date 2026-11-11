package com.financeos.gmail.accountdetect.unlock;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** Slice bank statements: the birth date as DDMMYYYY. Made-up profiles only. */
class SliceAccountPasswordRuleTest {

    @Test
    void sliceAccountsUseTheBirthDate() {
        assertThat(StatementPasswordRules.bundled().patterns("slice", "account")).containsExactly("DDMMYYYY");
    }

    /** The example the bank's own statement mail gives. */
    @Test
    void theRuleGivesTheBanksOwnExample() {
        assertThat(PasswordRender.render("DDMMYYYY", "Riya Sharma", "1998-07-15", List.of()).stream().map(PasswordRender.Candidate::password).toList())
                .containsExactly("15071998");
    }

    @Test
    void sliceHasNoCardRule() {
        assertThat(StatementPasswordRules.bundled().patterns("slice", "credit_card")).isEmpty();
    }
}
