package com.financeos.gmail.reconcile;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class MaskedNumberHintsTest {

    @Test
    void equitasStatementMail_namesTheCustomerIdInBodyAndAttachment() {
        String body = "Dear MR. ADARSH JAIN A, Your e-statement for SEP-26 for customer ID XXX1277 is attached for your ready viewing.";

        assertThat(MaskedNumberHints.find("Your Bank Account Statement", body, "XXX1277.pdf")).containsExactly("1277");
    }

    @Test
    void maskedCardNumber_keepsOnlyTheLastFourDigits() {
        assertThat(MaskedNumberHints.find("Statement for 407186******2165")).containsExactly("2165");
        assertThat(MaskedNumberHints.find("A/c XXXXXXXX12345678")).containsExactly("5678");
    }

    @Test
    void bulletAndHyphenMasks_areRecognised() {
        assertThat(MaskedNumberHints.find("card ••0438")).containsExactly("0438");
        assertThat(MaskedNumberHints.find("A/c XX-XX-9921")).containsExactly("9921");
    }

    @Test
    void maskedCustomerIdWithVisiblePrefix_yieldsThePrefix() {
        assertThat(MaskedNumberHints.find("Statements for Accounts under Customer ID 043-XXXXXX")).containsExactly("043");
    }

    @Test
    void unmaskedNumbers_areIgnored() {
        assertThat(MaskedNumberHints.find("Call 18001031222 or pay Rs 12,500 by 2026-10-03, ref 489754847")).isEmpty();
    }

    @Test
    void aSingleMaskCharacterOrALetterRunIsNotAMask() {
        assertThat(MaskedNumberHints.find("Model X1234, BOX1234, MAX12345")).isEmpty();
    }

    @Test
    void maskWithoutDigits_isIgnored() {
        assertThat(MaskedNumberHints.find("Customer ID XXXXXXX")).isEmpty();
    }

    @Test
    void repeatedNumbers_areReportedOnceInOrderOfFirstAppearance() {
        assertThat(MaskedNumberHints.find("XXX1277 and **2165", null, "XXX1277.pdf")).containsExactly("1277", "2165");
    }
}
