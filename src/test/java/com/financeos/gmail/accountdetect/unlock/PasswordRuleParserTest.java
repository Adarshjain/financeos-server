package com.financeos.gmail.accountdetect.unlock;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** Made-up password sentences to rule tokens. */
class PasswordRuleParserTest {

    @Test
    void nameInCapitalsFollowedByDdmm() {
        assertThat(PasswordRuleParser.parse("The password is the first 4 letters of your name in capital letters followed by your date of birth in DDMM format."))
                .containsExactly("NAME4U+DDMM");
    }

    @Test
    void lowerCaseNameAndWordsForNumbers() {
        assertThat(PasswordRuleParser.parse("Password: first four characters of your name in small letters followed by DDMMYYYY"))
                .containsExactly("NAME4L+DDMMYYYY");
    }

    @Test
    void untaggedNameKeepsNoCaseSuffix() {
        assertThat(PasswordRuleParser.parse("Your password is the first 4 letters of your name and DDMM of your birth date"))
                .containsExactly("NAME4+DDMM");
    }

    @Test
    void lastFourCardDigits() {
        assertThat(PasswordRuleParser.parse("Use the last 4 digits of your credit card number as the password")).containsExactly("CARD4");
    }

    @Test
    void alternativesSplitIntoSeveralRules() {
        assertThat(PasswordRuleParser.parse("The password is your first 4 letters of name and DDMM, or the last 4 digits of your card"))
                .containsExactly("NAME4+DDMM", "CARD4");
    }

    @Test
    void identifiersAreRecognisedSoTheUnlockerCanSkipThem() {
        assertThat(PasswordRuleParser.parse("Your password is your Customer ID followed by DDMMYYYY")).containsExactly("CUSTID+DDMMYYYY");
        assertThat(PasswordRuleParser.parse("Password is the first 5 characters of your PAN and DDMMYYYY")).containsExactly("PAN5+DDMMYYYY");
    }

    @Test
    void sentencesWithoutRuleWordsGiveNothing() {
        assertThat(PasswordRuleParser.parse("Please keep your password safe and do not share it.")).isEmpty();
        assertThat(PasswordRuleParser.parse(null)).isEmpty();
    }

    @Test
    void hintOfABodyUsesOnlyPasswordSentencesAndJoinsRules() {
        String body = "Dear customer,\nYour statement is attached.\nThe password to open it is the first 4 letters of your name in capitals followed by DDMM. "
                + "We never ask for your last 4 digits of card on calls. Thanks.";
        assertThat(PasswordRuleParser.hintFromBody(body)).isEqualTo("NAME4U+DDMM");
        assertThat(PasswordRuleParser.hintFromBody("Nothing about it here.")).isNull();
        assertThat(PasswordRuleParser.hintFromBody(null)).isNull();
    }

    @Test
    void hintIsAtMost200CharsAndCutOnARuleBoundary() {
        StringBuilder body = new StringBuilder();
        for (int i = 2; i <= 8; i++) {
            body.append("Password option: first ").append(i).append(" letters of your name and DDMM or last 4 digits of your card or DDMMYY.\n");
        }
        String hint = PasswordRuleParser.hintFromBody(body.toString());
        assertThat(hint).isNotNull();
        assertThat(hint.length()).isLessThanOrEqualTo(PasswordRuleParser.HINT_MAX);
        assertThat(PasswordRuleParser.rulesOf(hint)).allMatch(r -> r.matches("[A-Z0-9+]+"));
    }

    @Test
    void rulesOfSplitsAStoredHint() {
        assertThat(PasswordRuleParser.rulesOf("NAME4U+DDMM|CARD4")).isEqualTo(List.of("NAME4U+DDMM", "CARD4"));
        assertThat(PasswordRuleParser.rulesOf(null)).isEmpty();
        assertThat(PasswordRuleParser.rulesOf(" ")).isEmpty();
    }
}
