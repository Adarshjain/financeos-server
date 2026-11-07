package com.financeos.gmail.classify;

import com.financeos.gmail.internal.GmailMessage;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** Classifier version 7: bank support replies and "you chose to pay your dues" notices are never alerts (masked mails). */
class EmailClassifierV7Test {

    private final EmailClassifier classifier = new EmailClassifier();

    private ClassificationResult classify(String from, String subject, String body) {
        return classifier.classify(new GmailMessage("m1", Instant.parse("2026-03-25T10:00:00Z"), from, subject, null, body, null, List.of()));
    }

    @Test
    void axisSupportReplyQuotingACardTransferIsService() {
        ClassificationResult r = classify("Axis Bank <email.services@axis.bank.in>",
                "Re: Transaction-level MCC and Cashback/Rewards Details Request - Ace Card 1234",
                "Dear Customer, Thank you for writing to Axis Bank. This is with reference to your email dated 24-03-2026 regarding "
                        + "your request for the excess credit refund from your credit card ending with 1234 to 5678. A service request has been "
                        + "raised for the excess credit refund under Reference Number: 123456. We are pleased to share that the request has been "
                        + "successfully resolved, and an amount of INR 377 has been transferred from card 1234 to card 5678 on 25-03-2026. "
                        + "We trust the resolution meets your expectations.");
        assertThat(r.kind()).isEqualTo(EmailKind.OTHER);
        assertThat(r.subtype()).isEqualTo(EmailSubtype.SERVICE);
        assertThat(r.version()).isEqualTo(EmailClassifier.VERSION);
    }

    @Test
    void sliceFullDuesChoiceIsABillNotice() {
        ClassificationResult r = classify("slice <noreply@sliceit.com>", "Card dues to be paid in full",
                "Hi Asha, You have chosen to pay the full amount INR 6,715 of your card dues. We recommend that you repay your dues on time "
                        + "to avoid incurring a late fee. Best, Team slice");
        assertThat(r.kind()).isEqualTo(EmailKind.OTHER);
        assertThat(r.subtype()).isEqualTo(EmailSubtype.BILL_DUE);
    }

    @Test
    void realAlertWithoutReplyMarkersStaysAlert() {
        ClassificationResult r = classify("HDFC Bank <alerts@hdfcbank.net>", "Alert: Transaction on your card",
                "Rs. 725.00 has been debited from your HDFC Bank Credit Card ending 4821 towards BLUE LEAF CAFE on 20-10-2026.");
        assertThat(r.kind()).isEqualTo(EmailKind.ALERT);
    }

    @Test
    void replySubjectWithoutThanksForWritingStillNeedsNoRealTransaction() {
        // A reply that does not thank the customer for writing keeps the old rule: a real transaction sentence wins.
        ClassificationResult r = classify("HDFC Bank <alerts@hdfcbank.net>", "Re: your card",
                "Rs. 725.00 has been debited from your HDFC Bank Credit Card ending 4821 towards BLUE LEAF CAFE on 20-10-2026.");
        assertThat(r.kind()).isEqualTo(EmailKind.ALERT);
    }

    @Test
    void realAlertWithThanksAndReferenceWordingButNoReplySubjectStaysAlert() {
        ClassificationResult r = classify("Axis Bank <alerts@axisbank.com>", "Transaction alert on Axis Bank Credit Card no. XX1234",
                "INR 725.00 has been debited from your Axis Bank Credit Card ending 1234 towards BLUE LEAF CAFE on 20-10-2026. "
                        + "Reference number 123456. Thank you for writing to Axis Bank.");
        assertThat(r.kind()).isEqualTo(EmailKind.ALERT);
    }

    @Test
    void replySubjectQuotingAFreshDebitWithoutThanksWordingStaysAlert() {
        ClassificationResult r = classify("Axis Bank <alerts@axisbank.com>", "Re: card alert",
                "INR 725.00 has been debited from your Axis Bank Credit Card ending 1234 towards BLUE LEAF CAFE on 20-10-2026.");
        assertThat(r.kind()).isEqualTo(EmailKind.ALERT);
    }
}
