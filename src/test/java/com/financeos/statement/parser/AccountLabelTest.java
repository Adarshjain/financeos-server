package com.financeos.statement.parser;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** The account number read after a label: whole-word labels, no amounts, an explicit "No/Number/#/:" label beats a bare mention. */
class AccountLabelTest {

    private static List<Line> lines(String... texts) {
        List<Line> out = new ArrayList<>();
        double top = 10;
        for (String t : texts) {
            out.add(new Line(List.of(new Word(t, 10, 200, top, 1))));
            top += 12;
        }
        return out;
    }

    private static String numberOf(String... texts) {
        return MetadataExtractor.extract(lines(texts), null).accountNumber;
    }

    @Test
    void summaryLineAtOneLakhOrMoreDoesNotBecomeTheAccountNumber() {
        assertThat(numberOf("INR SAVINGS ACCOUNTS 314842.12 CR 0.00 0.00 0.00 314842.12",
                "Account Number : 50100000000001 TEST PERSON")).isEqualTo("50100000000001");
    }

    @Test
    void summaryLineUnderOneLakhDoesNotBecomeTheAccountNumber() {
        assertThat(numberOf("INR SAVINGS ACCOUNTS 54842.12 CR 0.00 0.00 0.00 54842.12",
                "Account Number : 50100000000001 TEST PERSON")).isEqualTo("50100000000001");
        assertThat(numberOf("INR SAVINGS ACCOUNTS 8842.12 CR", "Account No: 50100000000001")).isEqualTo("50100000000001");
    }

    @Test
    void aDecimalOrGroupedAmountRightAfterALabelIsRejected() {
        assertThat(numberOf("Account 314842.12 CR")).isNull();
        assertThat(numberOf("A/C Balance 123456.50")).isNull();
        assertThat(numberOf("Account No 314842,000")).isNull();
        assertThat(numberOf("Account No 314842.12", "Account No 50100000000001")).isEqualTo("50100000000001");
    }

    @Test
    void pluralAndGluedLabelWordsAreNotLabels() {
        assertThat(numberOf("ACCOUNTS 5010000000001")).isNull();
        assertThat(numberOf("CARDS 4000123412341234")).isNull();
        assertThat(numberOf("Accounting 5010000000001")).isNull();
    }

    @Test
    void realLabelFormsStillRead() {
        assertThat(numberOf("Account No 50100000000001")).isEqualTo("50100000000001");
        assertThat(numberOf("Account Number : 50100000000001")).isEqualTo("50100000000001");
        assertThat(numberOf("A/C No. 50100000000001")).isEqualTo("50100000000001");
        assertThat(numberOf("Acct # 50100000000001")).isEqualTo("50100000000001");
        assertThat(numberOf("Card No 4000XXXXXXXX1234")).isEqualTo("4000XXXXXXXX1234");
        assertThat(numberOf("Account-No 50100000000001")).isEqualTo("50100000000001");
        assertThat(numberOf("AccountNo 50100000000001")).isEqualTo("50100000000001");
        assertThat(numberOf("AccountNumber: 50100000000001")).isEqualTo("50100000000001");
        assertThat(numberOf("Credit Card 4000 1234")).isNull();
    }

    @Test
    void anExplicitlyLabelledNumberBeatsABareMention() {
        assertThat(numberOf("Savings Account 99900000000009", "Account Number : 50100000000001"))
                .isEqualTo("50100000000001");
        assertThat(numberOf("Account Number : 50100000000001", "Savings Account 99900000000009"))
                .isEqualTo("50100000000001");
    }

    @Test
    void aSingleBareCandidateIsStillUsed() {
        assertThat(numberOf("Savings Account 99900000000009")).isEqualTo("99900000000009");
    }

    @Test
    void firstLabelledWinsAmongSeveralLabelled() {
        assertThat(numberOf("Account No 50100000000001", "Account No 50100000000002")).isEqualTo("50100000000001");
    }

    @Test
    void fallbackOverWholeDocumentPrefersLabelled() {
        assertThat(Tokens.bestAccountNumber(lines("Savings Account 99900000000009", "Account No 50100000000001")))
                .isEqualTo("50100000000001");
        assertThat(Tokens.bestAccountNumber(lines("INR SAVINGS ACCOUNTS 314842.12 CR"))).isNull();
    }
}
