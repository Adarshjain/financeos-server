package com.financeos.gmail.extract;

import com.financeos.gmail.internal.GmailMessage;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/** Generic tier only: an extractor with no templates. All text is made up. */
class GenericExtractorTest {

    private final TransactionExtractor extractor = new TransactionExtractor(List.of());

    private Optional<ExtractedTransaction> run(String subject, String body) {
        return extractor.extract(new GmailMessage("m", Instant.parse("2026-03-10T05:30:00Z"),
                "Some Bank <alerts@somebank.example>", subject, null, body, null, List.of()));
    }

    @Test
    void debitedFromAccount() {
        ExtractedTransaction tx = run("Alert", "Dear Customer, INR 2,500.00 has been debited from your A/c XX1234 on 09-03-2026 "
                + "towards SAMPLE SHOP. Available balance is INR 90,000.00.").orElseThrow();
        assertThat(tx.tier()).isEqualTo(Tier.GENERIC);
        assertThat(tx.templateId()).isNull();
        assertThat(tx.confidence()).isEqualTo(0.6);
        assertThat(tx.amount()).isEqualByComparingTo("2500.00");
        assertThat(tx.currency()).isEqualTo("INR");
        assertThat(tx.direction()).isEqualTo(Direction.DEBIT);
        assertThat(tx.last4()).isEqualTo("1234");
        assertThat(tx.date()).isEqualTo("2026-03-09");
        assertThat(tx.counterparty()).isEqualTo("SAMPLE SHOP");
    }

    @Test
    void cardSpendAtMerchant() {
        ExtractedTransaction tx = run("Card alert", "Rs.499.00 spent on your card ending 4321 at SAMPLE CAFE on 08 Mar 2026.").orElseThrow();
        assertThat(tx.direction()).isEqualTo(Direction.DEBIT);
        assertThat(tx.amount()).isEqualByComparingTo("499.00");
        assertThat(tx.last4()).isEqualTo("4321");
        assertThat(tx.counterparty()).isEqualTo("SAMPLE CAFE");
        assertThat(tx.date()).isEqualTo("2026-03-08");
    }

    @Test
    void foreignCurrencyCredit() {
        ExtractedTransaction tx = run("Credit", "USD 40.50 has been credited to your account ending 1234.").orElseThrow();
        assertThat(tx.currency()).isEqualTo("USD");
        assertThat(tx.amount()).isEqualByComparingTo("40.50");
        assertThat(tx.direction()).isEqualTo(Direction.CREDIT);
    }

    @Test
    void creditViaUpiFromVpa() {
        ExtractedTransaction tx = run("UPI", "INR 150.00 received in your A/c XX1234 from VPA sample@okbank on 10-03-26.").orElseThrow();
        assertThat(tx.direction()).isEqualTo(Direction.CREDIT);
        assertThat(tx.counterparty()).isEqualTo("sample@okbank");
        assertThat(tx.date()).isEqualTo("2026-03-10");
    }

    @Test
    void reversalIsACredit() {
        ExtractedTransaction tx = run("Reversal", "The transaction of INR 300 at SAMPLE STORE on card XX1234 has been reversed.").orElseThrow();
        assertThat(tx.direction()).isEqualTo(Direction.CREDIT);
    }

    @Test
    void balanceAmountIsNotTheTransaction() {
        ExtractedTransaction tx = run("Alert", "Available balance INR 9,999.00. INR 100.00 debited from A/c XX1234.").orElseThrow();
        assertThat(tx.amount()).isEqualByComparingTo("100.00");
    }

    @Test
    void labelledFields() {
        ExtractedTransaction tx = run("Alert", "Transaction Amount: INR 75.25 Merchant Name: SAMPLE STORE Card No. XX1234 spent").orElseThrow();
        assertThat(tx.amount()).isEqualByComparingTo("75.25");
    }

    @Test
    void emptyWithoutAnAmountAndNoInventedFields() {
        assertThat(run("Hello", "Your account was debited. Thank you.")).isEmpty();
        ExtractedTransaction tx = run("Alert", "INR 10 debited.").orElseThrow();
        assertThat(tx.date()).isNull();
        assertThat(tx.counterparty()).isNull();
        assertThat(tx.last4()).isNull();
    }

    @Test
    void doesNotTakeALaterDateThanTheSentDay() {
        ExtractedTransaction tx = run("Alert", "INR 10 debited from A/c XX1234 on 25-12-2026.").orElseThrow();
        assertThat(tx.date()).isNull();
    }
}
