package com.financeos.gmail.accountdetect;

import com.financeos.gmail.accountdetect.Detection.Entity;
import com.financeos.gmail.accountdetect.MailDerived.EventHit;
import com.financeos.gmail.accountdetect.SyntheticMailbox.BankSpec;
import com.financeos.gmail.accountdetect.SyntheticMailbox.Card;
import com.financeos.gmail.accountdetect.Vocab.Channel;
import com.financeos.gmail.accountdetect.Vocab.Conf;
import com.financeos.gmail.accountdetect.Vocab.EventType;
import com.financeos.gmail.accountdetect.Vocab.Kind;
import com.financeos.gmail.accountdetect.Vocab.Lifecycle;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static com.financeos.gmail.accountdetect.SyntheticMailbox.MONTHS;
import static com.financeos.gmail.accountdetect.SyntheticMailbox.START;
import static org.assertj.core.api.Assertions.assertThat;

/** Review round: dates read as numbers, lifecycle witnesses, closure scope, weak period links, channels, limits, payees. */
class ReviewFixesTest {

    private static final int LAST = MONTHS - 1;

    private static List<String> own(String seg) {
        return FeatureExtractor.numbersIn(seg).stream().filter(n -> !n.label().rejected()).map(FeatureExtractor.Num::last4).toList();
    }

    // ---- 1. years are never last4s; real numbers ending 20xx still are

    @Test
    void theYearOfADateIsNeverANumber() {
        assertThat(own("Your credit card statement for the period ending 31/10/2024 is ready.")).isEmpty();
        assertThat(own("Statement for card account ending 31 October 2024 is attached.")).isEmpty();
        assertThat(own("Your Credit Card XX4521 statement dated 31-Oct-2024 is ready.")).containsExactly("4521");
        assertThat(own("Credit card account statement for Oct 2024 is ready.")).isEmpty();
        assertThat(own("Your card account 2024-10-31 summary.")).isEmpty();
    }

    @Test
    void aRealNumberEndingIn20xxStaysANumber() {
        assertThat(own("Your HDFC Bank Credit Card ending 2024 was used for Rs. 500.00.")).containsExactly("2024");
        assertThat(own("Your Credit Card XX1987 was used at AMAZON.")).containsExactly("1987");
        assertThat(own("A replacement card with a new number ending 2031 is being dispatched.")).containsExactly("2031");
    }

    // ---- 3. closure scope

    private static List<EventHit> events(String body) {
        MailFeature m = new MailFeature("hdfcbank.net", Instant.parse("2025-06-10T06:00:00Z"), "About your card", body, body, List.of());
        return FeatureExtractor.derive(m).events();
    }

    @Test
    void closureAttachesOnlyToItsSubjectAndANewNumberMakesAReplacement() {
        assertThat(events("Your Credit Card XX4410 has been closed and a new card XX7720 has been issued to you."))
                .containsExactly(new EventHit(EventType.REPLACED, List.of("4410", "7720")));
        assertThat(events("Your Credit Card XX4410 has been closed; the refund will go to your account XX5562 in 7 days."))
                .containsExactly(new EventHit(EventType.CLOSED, List.of("4410")));
        assertThat(events("Your Credit Card XX4410 has been permanently blocked."))
                .containsExactly(new EventHit(EventType.BLOCKED, List.of("4410")));
        assertThat(events("Your Credit Card XX4410 has been blocked and a replacement card XX7720 is on its way."))
                .containsExactly(new EventHit(EventType.REPLACED, List.of("4410", "7720")));
        assertThat(events("Your order has been cancelled. Card XX4410 will not be charged.")).isEmpty();
    }

    @Test
    void aBlockAloneIsALowStatus() {
        SyntheticMailbox mb = new SyntheticMailbox(41);
        BankSpec au = mb.bank("au", 2, true, false, false);
        Card c = new Card(au, "2627", Kind.CREDIT_CARD, null, 0, 9);
        mb.cardAlerts(c, 0, 9, false);
        mb.cardBilling(c, 0, 9, List.of());
        mb.mail(au.domain(), START.plusMonths(10).atDay(5), "Your AU Bank Credit Card has been blocked",
                "Your AU Bank Credit Card XX2627 has been permanently blocked.", true);
        Entity e = mb.detectRaw().find("au", "2627");
        assertThat(e.lifecycle).isEqualTo(Lifecycle.CLOSED);
        assertThat(e.lifecycleConf).isEqualTo(Conf.LOW);
    }

    // ---- 2. lifecycle witnesses

    private static Entity withLifecycle(int... dayOffsets) {
        Entity e = new Entity("axis", "4815");
        e.existsConf = Conf.HIGH;
        e.lifecycle = Lifecycle.CLOSED;
        for (int d : dayOffsets) e.lifecycleWitnesses.add(LocalDate.of(2025, 3, 1).plusDays(d));
        return e;
    }

    @Test
    void lifecycleConfidenceFollowsIndependentWitnesses() {
        Map<String, Entity> none = new HashMap<>();
        assertThat(Contract.lifecycleMax(withLifecycle(0), none)).isEqualTo(Conf.LOW);          // one mail on its own
        Entity t = withLifecycle(0);
        t.lifecycleTimeline = true;
        assertThat(Contract.lifecycleMax(t, none)).isEqualTo(Conf.MEDIUM);                     // one mail + timeline
        assertThat(Contract.lifecycleMax(withLifecycle(0, 10), none)).isEqualTo(Conf.LOW);     // two, under 30 days
        assertThat(Contract.lifecycleMax(withLifecycle(0, 40), none)).isEqualTo(Conf.HIGH);    // two, 30+ days apart
        assertThat(Contract.lifecycleMax(withLifecycle(), none)).isEqualTo(Conf.LOW);          // timeline alone
        Entity b = withLifecycle(0, 40);
        b.lifecycleFromBlock = true;
        assertThat(Contract.lifecycleMax(b, none)).isEqualTo(Conf.LOW);
    }

    @Test
    void aPdfFromAMailIsTheSameWitnessAsThatMail() {
        LocalDate mailDate = LocalDate.of(2025, 5, 14);
        StatementContent s = new StatementContent("PDF_MAIL", "axis", "4815", Kind.CREDIT_CARD, YearMonth.of(2025, 4), null, null,
                List.of(), true, LocalDate.of(2025, 4, 30)).withOrigin("c1", mailDate);
        assertThat(Detector.witnessDate(s)).isEqualTo(mailDate);
    }

    // ---- 4. period-linked facts of a bank with several own numbers of that kind stay LOW

    @Test
    void periodLinkedFactsAreLowWhenTheBankHasSeveralOwnNumbersOfThatKind() {
        SyntheticMailbox mb = new SyntheticMailbox(42);
        BankSpec axis = mb.bank("axis", 2, false, false, false);
        Card old = new Card(axis, "3172", Kind.CREDIT_CARD, null, 0, 5);
        Card now = new Card(axis, "6248", Kind.CREDIT_CARD, null, 8, LAST);
        mb.cardAlerts(old, 0, 5, false);
        mb.cardBilling(old, 0, 5, List.of());
        mb.cardAlerts(now, 8, LAST, false);
        mb.cardBilling(now, 8, LAST, List.of());
        for (int m = 10; m < 14; m++) {
            YearMonth ym = START.plusMonths(m);
            mb.statements.add(new StatementContent("PDF_FOLDER", "axis", null, Kind.CREDIT_CARD, ym, "Magnus", null, List.of(), false, ym.atDay(10)));
        }
        Detection d = mb.detectRaw();
        assertThat(d.statementLinks.stream().filter(l -> l.outcome().equals(Detector.LINK_PERIOD))).isNotEmpty();
        Entity e = d.find("axis", "6248");
        assertThat(e.variant).isEqualTo("Magnus");
        assertThat(e.variantConf).isEqualTo(Conf.LOW);
    }

    // ---- 5. a folder statement is the billing channel unless alerts or service mails also name the number

    @Test
    void folderStatementsDoNotLiftBillingOnlyToHigh() {
        SyntheticMailbox mb = new SyntheticMailbox(43);
        BankSpec icici = mb.bank("icici", 2, false, false, false);
        Card c = new Card(icici, "5318", Kind.CREDIT_CARD, null, 0, LAST);
        mb.cardBilling(c, 0, LAST, List.of());
        for (int m = 2; m < 8; m++) {
            YearMonth ym = START.plusMonths(m);
            mb.statements.add(new StatementContent("PDF_FOLDER", "icici", "5318", Kind.CREDIT_CARD, ym, null, null, List.of(), false, ym.atDay(10)));
        }
        Entity e = mb.detectRaw().find("icici", "5318");
        assertThat(e.bankChannels).containsExactly(Channel.BILLING);
        assertThat(e.existsConf).isEqualTo(Conf.MEDIUM);
    }

    // ---- 6. limit: header region only; MEDIUM needs 2+ agreeing statements 30+ days apart

    @Test
    void theLimitIsReadOnlyFromTheHeaderRegion() {
        String head = "AXIS BANK CREDIT CARD STATEMENT\nCard No: XXXX XXXX XXXX 4815\nStatement Date: 10/04/2025\nCredit Limit: Rs. 2,50,000\n"
                + "Total Amount Due: Rs. 1,234.00\nMinimum Amount Due: Rs. 200.00\n";
        assertThat(StatementPdfReader.fromText(head, "PDF_FOLDER", null, null).limitBucket()).isNotNull();
        StringBuilder footer = new StringBuilder("AXIS BANK CREDIT CARD STATEMENT\nCard No: XXXX XXXX XXXX 4815\nStatement Date: 10/04/2025\n"
                + "Total Amount Due: Rs. 1,234.00\nMinimum Amount Due: Rs. 200.00\n");
        for (int i = 0; i < StatementPdfReader.HEADER_LINES; i++) footer.append("Transaction line ").append(i).append('\n');
        footer.append("Credit Limit: Rs. 2,50,000\n");
        assertThat(StatementPdfReader.fromText(footer.toString(), "PDF_FOLDER", null, null).limitBucket()).isNull();
    }

    @Test
    void limitConfidenceNeedsAgreeingStatementsOverAMonth() {
        Entity e = new Entity("axis", "4815");
        e.limitDocs = 1;
        assertThat(Contract.limitMax(e)).isEqualTo(Conf.LOW);
        e.limitDocs = 3;
        e.limitSpanDays = 60;
        e.limitAgree = true;
        assertThat(Contract.limitMax(e)).isEqualTo(Conf.MEDIUM);
        e.limitAgree = false;
        assertThat(Contract.limitMax(e)).isEqualTo(Conf.LOW);
    }

    // ---- 7. a number only in debit/transfer layouts is a payee, not an own account

    private static void transferMails(SyntheticMailbox mb, BankSpec b, String l4, boolean withBalance) {
        for (int m = 0; m < 12; m++) {
            mb.mail(b.domain(), mb.day(m), "UPI transaction alert",
                    "Rs. 500.00 has been debited via UPI from your A/c XX" + l4 + " to the payee.", true);
            mb.mail(b.domain(), mb.day(m).plusDays(3), "NEFT transfer from your account",
                    "A NEFT transfer of Rs. 1,000.00 from your A/c XX" + l4 + " to the beneficiary is complete.", true);
            if (withBalance) mb.mail(b.domain(), mb.day(m).plusDays(5), "Balance alert",
                    "Your A/c XX" + l4 + " balance is Rs. 12,000.00 as of today.", true);
        }
    }

    @Test
    void anAccountOnlyInTransferLayoutsIsLow_aBalanceLayoutLiftsIt() {
        SyntheticMailbox a = new SyntheticMailbox(44);
        BankSpec sbi = a.bank("sbi", 2, false, false, false);
        transferMails(a, sbi, "7731", false);
        Entity e = a.detectRaw().find("sbi", "7731");
        assertThat(e == null || e.existsConf == Conf.LOW).isTrue();

        SyntheticMailbox b = new SyntheticMailbox(45);
        BankSpec sbi2 = b.bank("sbi", 2, false, false, false);
        transferMails(b, sbi2, "7731", true);
        Entity f = b.detectRaw().find("sbi", "7731");
        assertThat(f).isNotNull();
        assertThat(f.transferOnly).isFalse();
    }

    // ---- a folder copy of a mail's statement counts once, as the mail's billing evidence

    @Test
    void aFolderCopyOfAMailStatementCountsOnce() {
        SyntheticMailbox mb = new SyntheticMailbox(46);
        BankSpec icici = mb.bank("icici", 2, false, true, false);
        Card c = new Card(icici, "5319", Kind.CREDIT_CARD, null, 0, LAST);
        mb.cardBilling(c, 0, LAST, List.of());   // adds a PDF_MAIL statement per billed month
        StatementContent mailDoc = mb.statements.stream().filter(x -> "5319".equals(x.last4())).findFirst().orElseThrow();
        StatementContent copy = new StatementContent("PDF_FOLDER", "icici", "5319", Kind.CREDIT_CARD, mailDoc.period(), null, null,
                List.of(), false, mailDoc.periodEnd());
        Entity without = mb.detectRaw().find("icici", "5319");
        mb.statements.add(copy);
        Detection d = mb.detectRaw();
        assertThat(d.statementLinks.stream().filter(l -> l.doc() == copy).map(Detection.StatementLink::outcome))
                .containsExactly(Detector.LINK_SAME_AS_MAIL);
        Entity with = d.find("icici", "5319");
        assertThat(with.evidenceMails).isEqualTo(without.evidenceMails);   // the copy adds no evidence
        assertThat(with.bankChannels).isEqualTo(without.bankChannels);
    }
}
