package com.financeos.gmail.accountdetect.unlock;

import static org.assertj.core.api.Assertions.assertThat;

import com.financeos.gmail.accountdetect.Vocab.Kind;
import com.financeos.gmail.accountdetect.unlock.StatementUnlocker.Outcome;
import com.financeos.gmail.accountdetect.unlock.StatementUnlocker.State;
import com.financeos.gmail.accountdetect.unlock.StatementUnlocker.Target;
import java.time.YearMonth;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** A PDF locked with the other case of a candidate password still opens, and the returned rule re-renders to what worked. */
class StatementUnlockerCaseTest {

    private final StatementUnlocker unlocker = StatementUnlocker.bundled();
    private final UnlockProfile riya = UnlockProfile.of("Riya Sharma", "1994-08-14");

    private static Target target(List<String> hint) {
        return new Target("hdfc", Kind.CREDIT_CARD, hint, List.of(), YearMonth.of(2026, 10));
    }

    @Test
    void lowercaseVariantOfATaggedRuleOpensAndTheRuleBecomesL() throws Exception {
        Outcome o = unlocker.unlock(StatementUnlockerTest.pdf("riya1408"), target(List.of("NAME4U+DDMM")), riya);

        assertThat(o.state()).isEqualTo(State.UNLOCKED);
        assertThat(o.rule()).isEqualTo("NAME4L+DDMM");
        assertThat(PasswordRender.renderOne(o.rule(), "Riya Sharma", "1994-08-14", null)).isEqualTo("riya1408");
    }

    @Test
    void uppercaseVariantOfALowerRuleOpensAndTheRuleBecomesU() throws Exception {
        Outcome o = unlocker.unlock(StatementUnlockerTest.pdf("RIYA1408"), target(List.of("NAME4L+DDMM")), riya);

        assertThat(o.state()).isEqualTo(State.UNLOCKED);
        assertThat(o.rule()).isEqualTo("NAME4U+DDMM");
        assertThat(PasswordRender.renderOne(o.rule(), "Riya Sharma", "1994-08-14", null)).isEqualTo("RIYA1408");
    }

    @Test
    void savedUppercasePasswordOpensALowercasePdfAndKeepsTheSavedLabel() throws Exception {
        UnlockProfile saved = new UnlockProfile("Riya Sharma", "1994-08-14", Map.of("hdfc", List.of("ABCD1234")));
        Outcome o = unlocker.unlock(StatementUnlockerTest.pdf("abcd1234"), target(List.of()), saved);

        assertThat(o.state()).isEqualTo(State.UNLOCKED);
        assertThat(o.rule()).isEqualTo(StatementUnlocker.SAVED);
    }

    @Test
    void passwordThatOpensAsGivenIsUnchanged() throws Exception {
        Outcome o = unlocker.unlock(StatementUnlockerTest.pdf("RIYA1408"), target(List.of("NAME4U+DDMM")), riya);

        assertThat(o.rule()).isEqualTo("NAME4U+DDMM");
        assertThat(o.tries()).isEqualTo(1);
    }
}
