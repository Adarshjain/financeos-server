package com.financeos.domain.transaction.match;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ReferenceNumbersTest {

    @Test
    void upiNarrationsYieldTheirRrn() {
        assertThat(ReferenceNumbers.extract("UPI/DR/412345678901/SWIGGY/YESB/swiggy@ybl/Payment")).containsExactly("412345678901");
        assertThat(ReferenceNumbers.extract("UPI-ZOMATO LTD-zomato-order@ptybl-YESB0PTMUPI-412345678902-Payment from Phone"))
                .containsExactly("412345678902");
        assertThat(ReferenceNumbers.extract("UPI DR 498765432109 RAMESH KUMAR")).containsExactly("498765432109");
        assertThat(ReferenceNumbers.extract("MMT/IMPS/605409990907/RENT OCT/KARTHIK/HDFC0001234")).containsExactly("605409990907");
    }

    @Test
    void alertTextsYieldLabelledReferences() {
        assertThat(ReferenceNumbers.extract("Rs.500.00 debited from a/c **1234 on 20-10-26 to VPA karthik@okaxis (UPI Ref No 429312345678)."))
                .containsExactly("429312345678");
        assertThat(ReferenceNumbers.extract("Dear Customer, INR 45.00 paid to KARBAGAVALLI. RRN: 429876543210")).containsExactly("429876543210");
        assertThat(ReferenceNumbers.extract("IMPS Ref no 605409994425 credited Rs 33,000")).containsExactly("605409994425");
        assertThat(ReferenceNumbers.extract("Ref No. 123456789012 - amount debited")).containsExactly("123456789012");
    }

    @Test
    void neftAndRtgsUtrs() {
        assertThat(ReferenceNumbers.extract("NEFT CR-HDFC0000001-ACME PAYROLL-HDFCN52024101512345678"))
                .containsExactly("HDFCN52024101512345678");
        assertThat(ReferenceNumbers.extract("NEFT/N295241234567890/ACME SERVICES")).containsExactly("N295241234567890");
        assertThat(ReferenceNumbers.extract("RTGS UTR: SBINR52024103100012345 from ACME")).containsExactly("SBINR52024103100012345");
    }

    @Test
    void chequeNumbersDropLeadingZeros() {
        assertThat(ReferenceNumbers.extract("CHQ NO 004512 CLEARING")).containsExactly("CHQ:4512");
        assertThat(ReferenceNumbers.extract("Cheque No. 004512 presented")).containsExactly("CHQ:4512");
    }

    @Test
    void maskedCardAndAccountNumbersAreNeverReferences() {
        assertThat(ReferenceNumbers.extract("Rs 60,182.88 spent on your Ace Credit Card XX1234 at LIFE INSURA on 17-10-22")).isEmpty();
        assertThat(ReferenceNumbers.extract("Card ending 4xxxxxxxxxxx1234 used for INR 928.08")).isEmpty();
        assertThat(ReferenceNumbers.extract("XXXX XXXX XXXX 5678 POS 10073240 F IN. INST")).isEmpty();
        assertThat(ReferenceNumbers.extract("10073240 F IN. INST")).isEmpty();
        assertThat(ReferenceNumbers.extract("A/c XXXXXXXX1234 debited")).isEmpty();
    }

    @Test
    void unsureTextGivesNothing() {
        // two different 12-digit numbers in one UPI narration: which one is the RRN is unclear
        assertThat(ReferenceNumbers.extract("UPI/412345678901/123456789012/TRANSFER")).isEmpty();
        // a bare 12-digit number outside a UPI / IMPS narration (e.g. an account number)
        assertThat(ReferenceNumbers.extract("TRANSFER TO 123456789012 SELF")).isEmpty();
        // an UTR shape outside a NEFT / RTGS narration
        assertThat(ReferenceNumbers.extract("POS N295241234567890 AMAZON")).isEmpty();
        assertThat(ReferenceNumbers.extract(null)).isEmpty();
        assertThat(ReferenceNumbers.extract("  ")).isEmpty();
    }
}
