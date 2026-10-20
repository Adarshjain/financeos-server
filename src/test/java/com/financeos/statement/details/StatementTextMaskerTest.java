package com.financeos.statement.details;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class StatementTextMaskerTest {

    @Test
    void masksLongDigitRunsKeepingLastFour() {
        assertThat(StatementTextMasker.mask("Statement card 4111111111111234 ref 1234567")).isEqualTo("Statement card XXXXXXXXXXXX1234 XXX4567");
    }

    @Test
    void masksSpacedCardNumbers() {
        assertThat(StatementTextMasker.mask("Statement card 1111 2222 3333 4444")).isEqualTo("Statement card XXXXXXXXXXXX4444");
    }

    @Test
    void keepsShortRunsDatesAndDecimalAmounts() {
        String line = "Total due 123456.78 on 05/09/2026 min 12,345.00 12345";
        assertThat(StatementTextMasker.mask(line)).isEqualTo(line);
    }

    @Test
    void dropsLinesWithEmailAddresses() {
        assertThat(StatementTextMasker.mask("Total due 10.00\nwrite to someone@example.com about the balance\nDue date 05/10/2026"))
                .isEqualTo("Total due 10.00\nDue date 05/10/2026");
    }

    @Test
    void unlabelledNameAndAddressBlockIsNotSentButSummaryLinesAndValuesAre() {
        String text = "Testy McTestface\n12 Example Road\nSampleville 000000\nOther Text\nStatement Period\n01/09/2026 - 30/09/2026\n"
                + "Total Amount Due\n1,234.50\n1,234.50 x\nsecond value\nUnrelated marketing offer\nfinal offer";
        String out = StatementTextMasker.mask(text);
        assertThat(out).doesNotContain("Testy").doesNotContain("Example Road").doesNotContain("Sampleville")
                .doesNotContain("marketing").doesNotContain("final offer");
        assertThat(out).contains("Statement Period").contains("01/09/2026 - 30/09/2026")
                .contains("Total Amount Due").contains("1,234.50");
    }

    @Test
    void theHolderNameNeverLeavesEvenWhenItIsASummaryWord() {
        String out = StatementTextMasker.mask("Total due 10.00\nPayment received from Testy M\nDue date 05/10/2026 May",
                java.util.List.of("Testy", "May"));
        assertThat(out).isEqualTo("Total due 10.00\nPayment received from\nDue date 05/10/2026");
    }

    @Test
    void aNameOrAddressMergedIntoASummaryLineIsDropped() {
        String out = StatementTextMasker.mask("Testy McTestface Statement Date 30/09/2026\nFlat 12 Example Road Payment Due Date 20/10/2026");
        assertThat(out).isEqualTo("Statement Date 30/09/2026\n12 Payment Due Date 20/10/2026");
    }

    @Test
    void otherWordsInKeptLinesAreDropped() {
        assertThat(StatementTextMasker.mask("Total Amount Due Rs. 1,234.50 Cr please pay promptly"))
                .isEqualTo("Total Amount Due Rs. 1,234.50 Cr pay");
    }

    @Test
    void nullAndEmptyGiveEmpty() {
        assertThat(StatementTextMasker.mask(null)).isEmpty();
        assertThat(StatementTextMasker.mask("")).isEmpty();
    }
}
