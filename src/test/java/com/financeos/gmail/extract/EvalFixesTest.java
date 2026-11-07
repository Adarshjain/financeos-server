package com.financeos.gmail.extract;

import com.financeos.gmail.classify.ClassificationResult;
import com.financeos.gmail.classify.EmailClassifier;
import com.financeos.gmail.classify.EmailKind;
import com.financeos.gmail.classify.EmailSubtype;
import com.financeos.gmail.internal.GmailMessage;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/** Prod-eval fixes: masked, made-up copies of the three mail shapes the classifier or extractor got wrong. */
class EvalFixesTest {

    private final EmailClassifier classifier = new EmailClassifier();
    private final TransactionExtractor extractor = new TransactionExtractor();

    private static GmailMessage mail(String from, String subject, String body, String sent) {
        return new GmailMessage("m", Instant.parse(sent), from, subject, null, body, null, List.of());
    }

    // 1. Axis "transaction ofINR 2 ... has been reversed": glued "ofINR" made the amount invisible, so a security cue won.
    private static final String AXIS_REVERSED = "Dear Sample Customer, Thank you for banking with us. We wish to inform you that the transaction ofINR 2 "
            + "on your Credit Card no. XX1234 on 16-10-23 21:09:46 IST at Sample Club has been reversed. If this transaction is not initiated by you, "
            + "please call 18000000000. Always open to help you. Regards, Axis Bank Ltd. Please do not share your Internet Banking password with anyone.";

    @Test
    void axisGluedCurrencyReversalIsAnAlertReversal() {
        GmailMessage m = mail("Axis Bank Alerts <alerts@axisbank.com>", "Transaction alert on Axis Bank Credit Card no. XX1234", AXIS_REVERSED, "2023-10-16T15:39:50Z");
        ClassificationResult r = classifier.classify(m);
        assertThat(r.kind()).isEqualTo(EmailKind.ALERT);
        assertThat(r.subtype()).isEqualTo(EmailSubtype.REVERSAL);

        ExtractedTransaction tx = extractor.extract(m).orElseThrow();
        assertThat(tx.tier()).isEqualTo(Tier.TEMPLATE);
        assertThat(tx.amount()).isEqualByComparingTo("2");
        assertThat(tx.currency()).isEqualTo("INR");
        assertThat(tx.direction()).isEqualTo(Direction.CREDIT);
        assertThat(tx.date()).hasToString("2023-10-16");
        assertThat(tx.last4()).isEqualTo("1234");
    }

    @Test
    void axisGenuineSecurityMailStaysSecurity() {
        GmailMessage m = mail("Axis Bank Alerts <alerts@axisbank.com>", "Your Axis Bank Internet Banking password has been changed",
                "Dear Sample Customer, your Internet Banking login password has been changed on 16-10-2023. If you did not do this, call 18000000000.", "2023-10-16T15:39:50Z");
        ClassificationResult r = classifier.classify(m);
        assertThat(r.kind()).isEqualTo(EmailKind.OTHER);
        assertThat(r.subtype()).isEqualTo(EmailSubtype.SECURITY);
    }

    // 2. Auto Pay activation: the amount is the initial debit, not the mandate limit.
    private static final String AUTOPAY_WITH_DEBIT = "07-05-2024 Dear Customer, Thank you for banking with us. We wish to inform you that Auto Pay facility "
            + "has been successfully activated on your Axis Bank Card no. XX1234 for INR 150.00 from Sample Store and initial amt of INR 40.00 has been debited. "
            + "Sample Store can initiate subsequent transactions for a maximum amount of INR 150.00. Always open to help you. Regards, Axis Bank Ltd.";
    private static final String AUTOPAY_NO_DEBIT = "07-05-2024 Dear Customer, Thank you for banking with us. We wish to inform you that Auto Pay facility "
            + "has been successfully activated on your Axis Bank Card no. XX1234 for INR 150.00 from Sample Store. "
            + "Sample Store can initiate subsequent transactions for a maximum amount of INR 150.00. Always open to help you. Regards, Axis Bank Ltd.";

    @Test
    void autoPayActivationReadsTheInitialDebit() {
        GmailMessage m = mail("alerts@axisbank.com", "Auto Pay facility has been activated", AUTOPAY_WITH_DEBIT, "2024-05-07T12:07:33Z");
        ClassificationResult r = classifier.classify(m);
        assertThat(r.kind()).isEqualTo(EmailKind.ALERT);
        assertThat(r.subtype()).isEqualTo(EmailSubtype.DEBIT);

        ExtractedTransaction tx = extractor.extract(m).orElseThrow();
        assertThat(tx.tier()).isEqualTo(Tier.TEMPLATE);
        assertThat(tx.amount()).isEqualByComparingTo("40.00");
        assertThat(tx.direction()).isEqualTo(Direction.DEBIT);
        assertThat(tx.date()).hasToString("2024-05-07");
        assertThat(tx.last4()).isEqualTo("1234");
    }

    @Test
    void autoPayActivationWithoutInitialDebitIsNoTransaction() {
        GmailMessage m = mail("alerts@axisbank.com", "Auto Pay facility has been activated", AUTOPAY_NO_DEBIT, "2024-05-07T12:07:33Z");
        ClassificationResult r = classifier.classify(m);
        assertThat(r.kind()).isNotEqualTo(EmailKind.ALERT);
        assertThat(extractor.extract(m)).isEqualTo(Optional.empty());
    }

    // 3. ICICI payment received: last4 is the last digit group of "NNNN XXXX XXXX NNNN".
    private static final String ICICI_PAYMENT = "Dear Customer, Jun 11, 2024 Greetings from ICICI Bank. We have received payment of INR 2210.1 on your "
            + "ICICI Bank Credit Card Account 1234 XXXX XXXX 5678 through Visa Money Transfer on June 09, 2024. For any clarification, write to us.";

    @Test
    void iciciPaymentReceivedUsesTheLastDigitGroup() {
        GmailMessage m = mail("credit_cards@icicibank.com", "Payment received on your ICICI Bank Credit Card", ICICI_PAYMENT, "2024-06-11T06:47:27Z");
        ClassificationResult r = classifier.classify(m);
        assertThat(r.kind()).isEqualTo(EmailKind.ALERT);
        assertThat(r.subtype()).isEqualTo(EmailSubtype.CREDIT);

        ExtractedTransaction tx = extractor.extract(m).orElseThrow();
        assertThat(tx.templateId()).isEqualTo("icici.cc.payment.v3");
        assertThat(tx.amount()).isEqualByComparingTo("2210.1");
        assertThat(tx.direction()).isEqualTo(Direction.CREDIT);
        assertThat(tx.date()).hasToString("2024-06-09");
        assertThat(tx.last4()).isEqualTo("5678");
    }

    @Test
    void genericLast4TakesTheLastGroupOfASpacedMaskedNumber() {
        GmailMessage m = mail("alerts@unknown-bank.example", "Payment received", ICICI_PAYMENT, "2024-06-11T06:47:27Z");
        ExtractedTransaction tx = extractor.extract(m).orElseThrow();
        assertThat(tx.tier()).isEqualTo(Tier.GENERIC);
        assertThat(tx.last4()).isEqualTo("5678");
        assertThat(tx.direction()).isEqualTo(Direction.CREDIT);
    }
}
