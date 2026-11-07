package com.financeos.gmail.classify;

import com.financeos.gmail.internal.GmailAttachment;
import com.financeos.gmail.internal.GmailMessage;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** Classifier version 8: credit-score reports are documents, RTGS completion notices are confirmations (masked mails). */
class EmailClassifierV8Test {

    private final EmailClassifier classifier = new EmailClassifier();

    private ClassificationResult classify(String from, String subject, String body, List<GmailAttachment> attachments) {
        return classifier.classify(new GmailMessage("m1", Instant.parse("2026-03-25T10:00:00Z"), from, subject, null, body, null, attachments));
    }

    @Test
    void cibilReportWithPdfIsADocumentNotAStatement() {
        ClassificationResult r = classify("slice <noreply@sliceit.com>", "Asha your CIBIL score & report are here",
                "Hi Asha, your CIBIL score and report are attached. The PDF password is the first 4 letters of your name and your birth year.",
                List.of(new GmailAttachment("a1", "report.pdf", "application/pdf", new byte[0])));
        assertThat(r.kind()).isEqualTo(EmailKind.OTHER);
        assertThat(r.subtype()).isEqualTo(EmailSubtype.DOCUMENT);
    }

    @Test
    void experianCreditReportWithoutAttachmentIsOther() {
        ClassificationResult r = classify("Experian <no-reply@experian.example>", "Your Experian credit report is ready", "Open the app to view it.", List.of());
        assertThat(r.kind()).isEqualTo(EmailKind.OTHER);
    }

    @Test
    void axisCardCancellationLettersAreOtherDocuments() {
        for (String subject : List.of("Cancellation of EMI on your Axis Bank Credit Card ending 1234",
                "Important Communication : Cancellation of your Axis Bank Credit Card")) {
            ClassificationResult r = classify("Axis Bank <cards@axis.bank.in>", subject,
                    "Dear Customer, please find the attached letter regarding your Axis Bank Credit Card ending 1234.",
                    List.of(new GmailAttachment("a1", "letter.pdf", "application/pdf", new byte[0])));
            assertThat(r.kind()).as(subject).isEqualTo(EmailKind.OTHER);
        }
    }

    @Test
    void rtgsCompletedToBeneficiaryIsAConfirmationNotATransaction() {
        ClassificationResult a = classify("HDFC Bank InstaAlerts <alerts@hdfcbank.net>", "View: Account update for your HDFC Bank A/c",
                "Dear Customer, RTGS Transaction with reference number SAMPLEREF1 for INR 8,20,000.00 has been credited to the beneficiary account number XX2183 on 30-09-2024 at 00:43:15.", List.of());
        assertThat(a.kind()).isEqualTo(EmailKind.OTHER);
        assertThat(a.subtype()).isEqualTo(EmailSubtype.SERVICE);
        ClassificationResult b = classify("HDFC Bank InstaAlerts <alerts@hdfcbank.net>", "View: Account update for your HDFC Bank A/c",
                "Your RTGS transfer has been completed successfully. Transaction Details: Amount: INR 10,00,000.00 Credited to beneficiary A/c ending: XX3006 Date & Time: 18-07-2026 at 18:15:50",
                List.of());
        assertThat(b.kind()).isEqualTo(EmailKind.OTHER);
    }

    @Test
    void rtgsInitiatedFromYourAccountStaysADebitAlert() {
        ClassificationResult r = classify("HDFC Bank InstaAlerts <alerts@hdfcbank.net>", "View: Account update for your HDFC Bank A/c",
                "Dear Customer, This is to inform you that RTGS transaction of Rs. 800,000.00 has been initiated from your account No. XXXX1234 through NetBanking.", List.of());
        assertThat(r.kind()).isEqualTo(EmailKind.ALERT);
    }

    private static final String HDFC = "HDFC Bank InstaAlerts <alerts@hdfcbank.net>";

    @Test
    void ownDebitMentioningABeneficiaryStaysAnAlert() {
        assertThat(classify(HDFC, "Account update", "Rs 10,000 debited from your A/c XX1234 via NEFT and credited to beneficiary A/c XX9999 on 09-03-2026.", List.of()).kind())
                .isEqualTo(EmailKind.ALERT);
        assertThat(classify(HDFC, "Account update", "Your IMPS fund transfer has been completed successfully. INR 5,000 debited from A/c XX1234 on 09-03-2026.", List.of()).kind())
                .isEqualTo(EmailKind.ALERT);
    }

    @Test
    void inboundNeftCreditStaysAnAlert() {
        assertThat(classify(HDFC, "Account update", "INR 5,000.00 has been credited to your A/c XX1234 via NEFT from SAMPLE PAYER on 09-03-2026.", List.of()).kind())
                .isEqualTo(EmailKind.ALERT);
    }

    @Test
    void completionNoticeAcrossSectionBreaksIsStillAConfirmation() {
        ClassificationResult r = classify(HDFC, "Account update",
                "Your RTGS\n\nHas been noted.\n\nTransaction Details:\nAmount: INR 5,00,000.00\nCredited to beneficiary A/c ending: XX5678 transfer has been completed successfully", List.of());
        assertThat(r.kind()).isEqualTo(EmailKind.OTHER);
    }

    @Test
    void creditReportSubjectWithAStatementPdfStaysAStatement() {
        ClassificationResult r = classify("Some Bank <alerts@somebank.example>", "Your card bill is ready. Check your CIBIL score",
                "Your statement is attached.", List.of(new GmailAttachment("a1", "statement_1234.pdf", "application/pdf", new byte[0])));
        assertThat(r.kind()).isEqualTo(EmailKind.STATEMENT);
    }
}
