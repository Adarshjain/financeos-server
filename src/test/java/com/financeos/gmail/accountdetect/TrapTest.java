package com.financeos.gmail.accountdetect;

import com.financeos.gmail.accountdetect.Detection.Entity;
import com.financeos.gmail.accountdetect.SyntheticMailbox.BankSpec;
import com.financeos.gmail.accountdetect.SyntheticMailbox.Card;
import com.financeos.gmail.accountdetect.Vocab.Channel;
import com.financeos.gmail.accountdetect.Vocab.Conf;
import com.financeos.gmail.accountdetect.Vocab.Kind;
import com.financeos.gmail.accountdetect.Vocab.Lifecycle;
import com.financeos.gmail.accountdetect.Vocab.Status;
import org.junit.jupiter.api.Test;

import java.util.List;

import static com.financeos.gmail.accountdetect.SyntheticMailbox.MONTHS;
import static com.financeos.gmail.accountdetect.SyntheticMailbox.START;
import static org.assertj.core.api.Assertions.assertThat;

/** One made-up mailbox per trap; each asserts the trap never yields a confident wrong fact. */
class TrapTest {

    private static final int LAST = MONTHS - 1;

    private static Card card(BankSpec b, String l4, String variant, int from, int to) {
        return new Card(b, l4, Kind.CREDIT_CARD, variant, from, to);
    }

    private static boolean reportedAtLeastMedium(Detection d, String bank, String l4) {
        Entity e = d.find(bank, l4);
        return e != null && e.existsConf.atLeast(Conf.MEDIUM);
    }

    @Test
    void merchantNamesNeverBecomeVariants_andTheSwiggyCardKeepsItsOwn() {
        SyntheticMailbox mb = new SyntheticMailbox(11);
        BankSpec hdfc = mb.bank("hdfc", 0, true, false, false);
        Card swiggy = card(hdfc, "4411", "Swiggy", 0, LAST);
        mb.cardAlerts(swiggy, 0, LAST, false);   // alerts at SWIGGY, FLIPKART, IRCTC ...
        mb.cardBilling(swiggy, 0, LAST, List.of());
        BankSpec axis = mb.bank("axis", 2, true, false, false); // statements never name the product
        Card plain = card(axis, "5522", "Magnus", 0, LAST);
        mb.cardAlerts(plain, 0, LAST, false);
        mb.cardBilling(plain, 0, LAST, List.of());

        Detection d = mb.detect(11);
        assertThat(d.find("hdfc", "4411").variant).isEqualTo("Swiggy");
        Entity p = d.find("axis", "5522");
        assertThat(p.variant).isNull();
        assertThat(p.variantConf).isNull();
    }

    @Test
    void counterpartyAccountsUpiRefsAndARecurringPayeeAreNeverConfident() {
        SyntheticMailbox mb = new SyntheticMailbox(12);
        BankSpec hdfc = mb.bank("hdfc", 0, false, false, false);
        mb.accountMails(hdfc, "3301", "7702", true);   // NEFT/IMPS counterparties, UPI refs, monthly rent payee

        Detection d = mb.detect(12);
        Entity own = d.find("hdfc", "3301");
        assertThat(own.existsConf).isEqualTo(Conf.HIGH);
        assertThat(own.kind).isEqualTo(Kind.ACCOUNT);
        assertThat(reportedAtLeastMedium(d, "hdfc", "7702")).isFalse();
        for (Entity e : d.entities) if (!e.last4.equals("3301")) assertThat(e.existsConf).isEqualTo(Conf.LOW);
        assertThat(d.dropped).containsKey("number in counterparty slot only");
    }

    @Test
    void aadhaarShapesAndOtpsAreRejected() {
        SyntheticMailbox mb = new SyntheticMailbox(13);
        BankSpec icici = mb.bank("icici", 0, false, false, false);
        Card c = card(icici, "6120", "Coral", 0, LAST);
        mb.cardAlerts(c, 0, LAST, false);
        mb.cardBilling(c, 0, LAST, List.of());
        for (int m = 0; m < 6; m++) {
            mb.mail(icici.domain(), mb.day(m), "Aadhaar seeding confirmation",
                    "Dear Customer, your Aadhaar number XXXX XXXX 8812 has been successfully linked.", true);
            mb.mail(icici.domain(), mb.day(m), "OTP for your transaction",
                    "482913 is the OTP for transaction of INR 120.00 on your ICICI Bank Credit Card XX9933. Do not share.", true);
        }
        Detection d = mb.detect(13);
        assertThat(d.find("icici", "8812")).isNull();
        assertThat(d.find("icici", "9933")).isNull();
        assertThat(d.dropped).containsKey("number rejected: aadhaar");
        assertThat(d.dropped).containsKey("mail role OTP (no facts)");
    }

    @Test
    void offersForCardsTheUserDoesNotOwnYieldNothing() {
        SyntheticMailbox mb = new SyntheticMailbox(14);
        BankSpec hdfc = mb.bank("hdfc", 0, false, false, false);
        Card c = card(hdfc, "2208", "Millennia", 0, LAST);
        mb.cardAlerts(c, 0, LAST, false);
        mb.cardBilling(c, 0, LAST, List.of());
        mb.marketing(hdfc, List.of(c)); // "Upgrade to Regalia Gold ... your card XX2208", pre-approved cards with made-up numbers
        Detection d = mb.detect(14);
        assertThat(d.find("hdfc", "2208").variant).isEqualTo("Millennia");
        assertThat(d.entities).hasSize(1);
        assertThat(d.dropped).containsKey("mail role MARKETING (no facts)");
    }

    @Test
    void explicitReplacementWithTimelineIsMedium_timelineOnlyIsLow() {
        SyntheticMailbox mb = new SyntheticMailbox(15);
        BankSpec sbi = mb.bank("sbi", 2, true, false, false);
        Card a = card(sbi, "1170", null, 0, 10), b = card(sbi, "8840", null, 11, LAST);
        mb.cardAlerts(a, 0, 10, false);
        mb.cardBilling(a, 0, 10, List.of());
        mb.cardAlerts(b, 11, LAST, false);
        mb.cardBilling(b, 11, LAST, List.of());
        mb.mail(sbi.domain(), mb.day(10), "Your replacement card is on its way",
                "Dear Customer, your SBI Card Credit Card XX1170 has been replaced. Your new card ending 8840 has been dispatched.", true);
        BankSpec yes = mb.bank("yes", 2, false, false, false);
        Card c = card(yes, "3015", null, 0, 8), e = card(yes, "6631", null, 9, LAST);
        mb.cardAlerts(c, 0, 8, false);
        mb.cardBilling(c, 0, 8, List.of());
        mb.cardAlerts(e, 9, LAST, false);
        mb.cardBilling(e, 9, LAST, List.of());

        // one explicit mail plus the billing hand-over: MEDIUM (HIGH needs 2 explicit witnesses 30+ days apart)
        assertThat(mb.detectRaw().find("sbi", "1170").lifecycleConf).isEqualTo(Conf.MEDIUM);
        Detection d = mb.detect(15);
        Entity old = d.find("sbi", "1170");
        assertThat(old.lifecycle).isEqualTo(Lifecycle.REPLACED);
        assertThat(old.linkedTo).isEqualTo("sbi:8840");
        assertThat(old.lifecycleConf.atLeast(Conf.MEDIUM)).isTrue();
        assertThat(d.find("sbi", "8840").lifecycle).isEqualTo(Lifecycle.REPLACEMENT);
        Entity t = d.find("yes", "3015");
        assertThat(t.lifecycle).isEqualTo(Lifecycle.REPLACED);
        assertThat(t.lifecycleConf).isEqualTo(Conf.LOW);   // a timeline alone is LOW
    }

    @Test
    void upgradeWithVariantChange() {
        SyntheticMailbox mb = new SyntheticMailbox(16);
        BankSpec hdfc = mb.bank("hdfc", 0, true, false, false);
        Card a = card(hdfc, "5150", "Regalia", 0, 12), b = card(hdfc, "7720", "Regalia Gold", 13, LAST);
        mb.cardAlerts(a, 0, 12, false);
        mb.cardBilling(a, 0, 12, List.of());
        mb.cardAlerts(b, 13, LAST, false);
        mb.cardBilling(b, 13, LAST, List.of());
        mb.mail(hdfc.domain(), mb.day(12), "Your HDFC Bank Credit Card has been upgraded",
                "Dear Customer, your HDFC Bank Credit Card XX5150 has been upgraded to Regalia Gold Credit Card. Your new Regalia Gold HDFC Bank Credit Card ending 7720 has been dispatched.", true);
        assertThat(mb.detectRaw().find("hdfc", "5150").lifecycleConf).isEqualTo(Conf.MEDIUM);   // one explicit mail + timeline
        Detection d = mb.detect(16);
        Entity old = d.find("hdfc", "5150");
        assertThat(old.variant).isEqualTo("Regalia");      // the upgrade sentence is never tied to the old number
        assertThat(old.lifecycle).isEqualTo(Lifecycle.UPGRADED);
        assertThat(old.linkedTo).isEqualTo("hdfc:7720");
        assertThat(old.lifecycleConf.atLeast(Conf.MEDIUM)).isTrue();
        // "Regalia Gold HDFC Bank Credit Card": the catalogue drops the issuer words, so the dispatch mail and the
        // new card's statements name one product
        assertThat(d.find("hdfc", "7720").variant).isEqualTo("Regalia Gold");
        assertThat(d.find("hdfc", "7720").variantConf).isEqualTo(Conf.LOW);   // Regalia Gold nests with Regalia
        assertThat(d.find("hdfc", "7720").variantCandidates).contains("Regalia Gold", "Regalia");
    }

    @Test
    void addOnFromStatementListingIsHigh_andFromTimelineIsLow() {
        SyntheticMailbox mb = new SyntheticMailbox(17);
        BankSpec axis = mb.bank("axis", 0, false, true, false);
        Card p = card(axis, "9001", "Atlas", 0, LAST), x = card(axis, "9002", "Atlas", 4, LAST);
        mb.cardAlerts(p, 0, LAST, false);
        mb.cardBilling(p, 0, 3, List.of());
        mb.cardBilling(p, 4, LAST, List.of("9002"));
        mb.cardAlerts(x, 4, LAST, false);
        BankSpec icici = mb.bank("icici", 2, false, false, false);
        Card q = card(icici, "4401", null, 0, LAST), y = card(icici, "4402", null, 6, LAST);
        mb.cardAlerts(q, 0, LAST, false);
        mb.cardBilling(q, 0, LAST, List.of());
        mb.cardAlerts(y, 6, LAST, false);

        Detection d = mb.detect(17);
        Entity a = d.find("axis", "9002");
        assertThat(a.lifecycle).isEqualTo(Lifecycle.ADD_ON);
        assertThat(a.linkedTo).isEqualTo("axis:9001");
        assertThat(a.lifecycleConf).isEqualTo(Conf.HIGH);
        Entity b = d.find("icici", "4402");
        assertThat(b.lifecycle).isEqualTo(Lifecycle.ADD_ON);
        assertThat(b.linkedTo).isEqualTo("icici:4401");
        assertThat(b.lifecycleConf).isEqualTo(Conf.LOW);   // a timeline guess only: the bills of a separate card may simply be missing
    }

    @Test
    void explicitClosureIsClosed_silentStopIsOnlyLowStopped_dormantIsLow() {
        SyntheticMailbox mb = new SyntheticMailbox(18);
        BankSpec au = mb.bank("au", 2, true, false, false);
        Card c = card(au, "2626", null, 0, 9);
        mb.cardAlerts(c, 0, 9, false);
        mb.cardBilling(c, 0, 9, List.of());
        mb.mail(au.domain(), START.plusMonths(10).atDay(20), "Your AU Bank Credit Card has been closed",
                "As requested, your AU Bank Credit Card ending 2626 has been closed. Thank you for banking with us.", true);
        BankSpec indus = mb.bank("indusind", 2, true, false, false);
        Card s = card(indus, "3737", null, 0, 8);
        mb.cardAlerts(s, 0, 8, false);
        mb.cardBilling(s, 0, 8, List.of());
        Card live = card(indus, "3738", null, 0, LAST);
        mb.cardAlerts(live, 0, LAST, false);
        mb.cardBilling(live, 0, LAST, List.of());
        // "will be closed if not used" is not a closure
        mb.mail(indus.domain(), mb.day(5), "Important information about your IndusInd Bank Credit Card",
                "Your IndusInd Bank Credit Card XX3738 will be closed if not used for 12 months.", true);

        assertThat(mb.detectRaw().find("au", "2626").lifecycleConf).isEqualTo(Conf.MEDIUM); // one explicit mail + activity stopping: MEDIUM
        Detection d = mb.detect(18);
        Entity closed = d.find("au", "2626");
        assertThat(closed.lifecycle).isEqualTo(Lifecycle.CLOSED);
        assertThat(closed.lifecycleConf).isEqualTo(Conf.MEDIUM); // one witness + a corroborating timeline
        Entity stopped = d.find("indusind", "3737");
        assertThat(stopped.lifecycle).isEqualTo(Lifecycle.STOPPED);
        assertThat(stopped.lifecycleConf).isEqualTo(Conf.LOW);
        assertThat(stopped.status).isEqualTo(Status.DORMANT);
        assertThat(d.find("indusind", "3738").lifecycle).isNull();
    }

    @Test
    void sameLast4AtTwoBanksStaysTwoEntities() {
        SyntheticMailbox mb = new SyntheticMailbox(19);
        BankSpec hdfc = mb.bank("hdfc", 0, false, false, false);
        mb.accountMails(hdfc, "4545", null, false);
        BankSpec icici = mb.bank("icici", 2, true, false, false);
        Card c = card(icici, "4545", null, 0, LAST);
        mb.cardAlerts(c, 0, LAST, false);
        mb.cardBilling(c, 0, LAST, List.of());
        Detection d = mb.detect(19);
        assertThat(d.find("hdfc", "4545").kind).isEqualTo(Kind.ACCOUNT);
        assertThat(d.find("icici", "4545").kind).isEqualTo(Kind.CREDIT_CARD);
    }

    @Test
    void paymentAppConfirmsButNeverCreates() {
        SyntheticMailbox mb = new SyntheticMailbox(20);
        BankSpec hdfc = mb.bank("hdfc", 2, false, false, false);
        Card c = card(hdfc, "8080", null, 0, LAST);
        mb.cardAlerts(c, 0, LAST, false);
        mb.cardBilling(c, 0, LAST, List.of());
        for (int m = 0; m < MONTHS; m++) {
            mb.mail("cred.club", mb.day(m), "Payment successful", "Payment of INR 999.00 to HDFC Bank credit card XX8080 was successful.", true);
            mb.mail("cred.club", mb.day(m), "Payment successful", "Payment of INR 500.00 to HDFC Bank credit card XX9191 was successful.", true);
        }
        Detection d = mb.detect(20);
        assertThat(d.find("hdfc", "8080").channels).contains(Channel.PAYMENT_APP);
        assertThat(d.find("hdfc", "9191")).isNull();
        assertThat(d.appHits).containsKeys("hdfc:8080", "hdfc:9191");
    }

    @Test
    void missingMonthsKeepTheSeries() {
        SyntheticMailbox mb = new SyntheticMailbox(21);
        BankSpec fed = mb.bank("federal", 0, false, false, false);
        Card c = card(fed, "6060", "Scapia", 0, LAST);
        mb.cardAlerts(c, 0, LAST, false);
        mb.cardBilling(c, 0, 9, List.of());
        mb.cardBilling(c, 11, LAST, List.of()); // month 10 has no statement
        Detection d = mb.detect(21);
        Entity e = d.find("federal", "6060");
        assertThat(e.existsConf).isEqualTo(Conf.HIGH);
        assertThat(e.lifecycle).isNull();
        assertThat(e.billingMonths).doesNotContain(START.plusMonths(10));
        assertThat(e.billingMonths.last()).isGreaterThan(START.plusMonths(LAST - 3));
    }

    @Test
    void placeholderSampleNumbersInStatementsNeverBecomeCards() {
        SyntheticMailbox mb = new SyntheticMailbox(21);
        BankSpec axis = mb.bank("axis", 0, true, false, false);
        Card c = card(axis, "6631", "Magnus", 0, LAST);
        mb.cardAlerts(c, 0, LAST, false);
        for (int m = 0; m < MONTHS; m++) {
            mb.mail(axis.domain(), mb.day(m), "Your Axis Bank Credit Card statement for " + START.plusMonths(m).format(SyntheticMailbox.MON),
                    "Your statement for credit card no. XX1234 is ready. Total amount due INR 1,200.00.", true);
            mb.mail(axis.domain(), mb.day(m), "Axis Bank Credit Card e-statement",
                    "Credit card number XXXX0000 statement attached. Minimum amount due INR 200.00.", true);
        }
        Detection d = mb.detect(21);
        assertThat(d.find("axis", "1234")).isNull();
        assertThat(d.find("axis", "0000")).isNull();
        assertThat(d.dropped).containsKey("number rejected: placeholder shape");
        assertThat(reportedAtLeastMedium(d, "axis", "6631")).isTrue();
    }

    @Test
    void aListOfNumbersInOneMailAndANumberThatNeverStandsAloneAreNotCards() {
        SyntheticMailbox mb = new SyntheticMailbox(22);
        BankSpec hsbc = mb.bank("hsbc", 0, true, false, false);
        Card c = card(hsbc, "7106", "Platinum", 0, LAST);
        mb.cardAlerts(c, 0, LAST, false);
        mb.cardBilling(c, 0, LAST, List.of());
        for (int m = 0; m < MONTHS; m++) {
            // help text listing several numbers in one slot, every month
            mb.mail(hsbc.domain(), mb.day(m), "Your HSBC Credit Card statement is ready",
                    "Credit card XX4200, XX5120, XX7385 shown for illustration. Total amount due INR 900.00.", true);
            // a second number that only ever rides along with the own card
            mb.mail(hsbc.domain(), mb.day(m), "Transaction alert on HSBC Credit Card",
                    "Your HSBC credit card XX7106 was used for INR 450.00. Card XX9007 reward partner applied.", true);
        }
        Detection d = mb.detect(22);
        for (String l4 : List.of("4200", "5120", "7385", "9007")) assertThat(reportedAtLeastMedium(d, "hsbc", l4)).as(l4).isFalse();
        assertThat(d.find("hsbc", "7106").existsConf).isEqualTo(Conf.HIGH);
    }

    @Test
    void aMissingMailDateIsUnknownNotEpoch() {
        MailDerived m = new MailDerived("hdfc", "c", Vocab.Role.TXN_ALERT, java.time.LocalDate.of(1970, 1, 1), null,
                List.of(), List.of(), List.of(), List.of());
        assertThat(m.date()).isNull();
    }
}
