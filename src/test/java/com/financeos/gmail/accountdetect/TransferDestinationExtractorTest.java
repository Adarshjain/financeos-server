package com.financeos.gmail.accountdetect;

import com.financeos.gmail.accountdetect.FeatureExtractor.Num;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** The destination of a transfer is not a slot; the user's own number in a plain credit alert still is. */
class TransferDestinationExtractorTest {

    private static List<String> slots(String seg) {
        return FeatureExtractor.numbersIn(seg).stream().filter(n -> !n.label().rejected()).map(Num::last4).toList();
    }

    @Test
    void hdfcDebitedFromCreditedToGivesOnlyTheSource() {
        assertThat(slots("INR 2,50,000.00 has been debited from your account ending xxxxxxxxxx1234 on 06-06-26 and credited to the account ending xxxxxxxx5678 via IMPS."))
                .containsExactly("1234");
    }

    @Test
    void hdfcDebitedAndCreditedGivesOnlyTheSource() {
        assertThat(slots("Your A/c xxxxxxxxxx1234 is debited for INR 2,00,000.00 on 03-07-25 and A/c xxxxxxxx5678 is credited."))
                .containsExactly("1234");
    }

    @Test
    void beneficiaryAndTransferredToContextsAreNotSlots() {
        assertThat(slots("Rs. 500.00 debited from A/c XX1234 to beneficiary A/c XX5678 via NEFT.")).containsExactly("1234");
        assertThat(slots("Rs. 500.00 transferred to A/c XX5678 from your A/c XX1234.")).containsExactly("1234");
        assertThat(slots("Rs. 500.00 debited from A/c XX1234 and sent to the account ending XX5678.")).containsExactly("1234");
    }

    @Test
    void ownAccountInAPlainCreditAlertIsStillASlot() {
        assertThat(slots("Rs. 1,000.00 credited to your account **1234 by VPA someone@upi.")).containsExactly("1234");
        assertThat(slots("Your account XX1234 is credited with Rs. 1,000.00.")).containsExactly("1234");
    }
}
