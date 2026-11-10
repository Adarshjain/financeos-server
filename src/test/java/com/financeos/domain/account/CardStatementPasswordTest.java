package com.financeos.domain.account;

import com.financeos.gmail.accountdetect.unlock.StatementPasswordRules;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class CardStatementPasswordTest {

    @Test
    void hsbcCreditCardRuleIncludesDdmmyyCard6() {
        assertThat(StatementPasswordRules.bundled().patterns("hsbc", "credit_card")).contains("DDMMYY+CARD6");
    }

    @Test
    void derivesForHsbc() {
        assertThat(CardStatementPassword.derive("hsbc", "Riya Sharma", "1990-01-15", "654321")).contains("150190654321");
    }

    @Test
    void emptyForABankWithoutACard6Rule() {
        assertThat(CardStatementPassword.derive("icici", "Riya Sharma", "1990-01-15", "654321")).isEmpty();
        assertThat(CardStatementPassword.derive(null, "Riya Sharma", "1990-01-15", "654321")).isEmpty();
    }

    @Test
    void emptyWithoutBirthDateOrLast6() {
        assertThat(CardStatementPassword.derive("hsbc", "Riya Sharma", null, "654321")).isEmpty();
        assertThat(CardStatementPassword.derive("hsbc", "Riya Sharma", "1990-01-15", null)).isEmpty();
        assertThat(CardStatementPassword.derive("hsbc", "Riya Sharma", "1990-01-15", " ")).isEmpty();
    }
}
