package com.financeos.gmail.extract;

import com.financeos.gmail.internal.GmailMessage;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/** A beneficiary's account is never the user's; a mail with only that account is an outgoing transfer. Masked text. */
class BeneficiaryAccountTest {

    private final TransactionExtractor extractor = new TransactionExtractor(List.of());

    private Optional<ExtractedTransaction> run(String body) {
        return extractor.extract(new GmailMessage("m", Instant.parse("2026-03-10T05:30:00Z"),
                "Some Bank <alerts@somebank.example>", "Account update", null, body, null, List.of()));
    }

    @Test
    void creditedToBeneficiaryAccountIsDebitWithNoLast4() {
        ExtractedTransaction tx = run("Dear Customer, RTGS Transaction with reference number SAMPLEREF1 for INR 8,20,000.00 has been credited to the "
                + "beneficiary account number XX2183 on 30-09-2024 at 00:43:15. With Regards, Bank RTGS Team").orElseThrow();
        assertThat(tx.direction()).isEqualTo(Direction.DEBIT);
        assertThat(tx.last4()).isNull();
        assertThat(tx.amount()).isEqualByComparingTo("820000.00");
    }

    @Test
    void beneficiaryNumberIsSkippedButOwnAccountIsKept() {
        ExtractedTransaction tx = run("INR 5,000.00 has been debited from your A/c XX1111 and transferred to beneficiary A/c XX2222 on 09-03-2026.").orElseThrow();
        assertThat(tx.direction()).isEqualTo(Direction.DEBIT);
        assertThat(tx.last4()).isEqualTo("1111");
    }

    @Test
    void payeeAndTransferredToAccountAreNotOwn() {
        ExtractedTransaction tx = run("INR 700.00 sent to payee A/c XX3333 on 09-03-2026.").orElseThrow();
        assertThat(tx.last4()).isNull();
        ExtractedTransaction tx2 = run("INR 700.00 debited and transferred to A/c ending 4444 on 09-03-2026.").orElseThrow();
        assertThat(tx2.last4()).isNull();
    }

    @Test
    void creditedToYourAccountStillCredit() {
        ExtractedTransaction tx = run("INR 700.00 has been credited to your A/c XX5555 on 09-03-2026.").orElseThrow();
        assertThat(tx.direction()).isEqualTo(Direction.CREDIT);
        assertThat(tx.last4()).isEqualTo("5555");
    }

    @Test
    void ownNumberAfterThePartyIsKept() {
        assertThat(run("INR 500.00 sent to payee Ravi from your A/c XX1234 on 09-03-2026.").orElseThrow().last4()).isEqualTo("1234");
        assertThat(run("INR 500.00 paid to beneficiary Ravi Kumar from A/c XX1234 on 09-03-2026.").orElseThrow().last4()).isEqualTo("1234");
        assertThat(run("INR 500.00 debited and credited to beneficiary A/c XX5678 from your A/c XX1234 on 09-03-2026.").orElseThrow().last4()).isEqualTo("1234");
    }

    @Test
    void accountNoWordingStillMarksTheOtherSide() {
        assertThat(run("INR 500.00 debited and credited to beneficiary A/c No. 5678 on 09-03-2026.").orElseThrow().last4()).isNull();
        assertThat(run("INR 500.00 debited and transferred to account no. 4444 on 09-03-2026.").orElseThrow().last4()).isNull();
    }
}
