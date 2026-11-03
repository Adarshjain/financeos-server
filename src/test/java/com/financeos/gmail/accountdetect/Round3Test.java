package com.financeos.gmail.accountdetect;

import com.financeos.gmail.accountdetect.Detection.Entity;
import com.financeos.gmail.accountdetect.SyntheticMailbox.BankSpec;
import com.financeos.gmail.accountdetect.SyntheticMailbox.Card;
import com.financeos.gmail.accountdetect.Vocab.Conf;
import com.financeos.gmail.accountdetect.Vocab.EventType;
import com.financeos.gmail.accountdetect.Vocab.Kind;
import com.financeos.gmail.accountdetect.Vocab.LabelKind;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static com.financeos.gmail.accountdetect.SyntheticMailbox.MONTHS;
import static org.assertj.core.api.Assertions.assertThat;

/** Product catalogue, issuer directory, add-on / welcome / limit-change mails and payment-app corroboration. */
class Round3Test {

    private static final int LAST = MONTHS - 1;

    @Test
    void catalogueCanonicalisesIssuerWordsAndNetworkSuffixes() {
        assertThat(ProductCatalog.canonical("hdfc", "Swiggy HDFC Bank Credit Card")).isEqualTo("Swiggy");
        assertThat(ProductCatalog.canonical("icici", "Amazon Pay ICICI Bank")).isEqualTo("Amazon Pay");
        assertThat(ProductCatalog.canonical("axis", "Airtel Axis Bank")).isEqualTo("Airtel");
        assertThat(ProductCatalog.canonical("icici", "Coral RuPay")).isEqualTo("Coral");
        assertThat(ProductCatalog.canonical("hdfc", "Infinia Metal Edition")).isEqualTo("Infinia");
        assertThat(ProductCatalog.canonical("axis", "Citi PremierMiles")).isEqualTo("PremierMiles");   // ex-Citi on Axis
        assertThat(ProductCatalog.canonical("idfc_first", "IDFC FIRST Wealth Credit Card")).isEqualTo("First Wealth");
        assertThat(ProductCatalog.canonical("hdfc", "Regalia")).isEqualTo("Regalia");               // legacy, not Regalia Gold
        assertThat(ProductCatalog.canonical("hdfc", "Some New Card")).isNull();
    }

    @Test
    void anUncataloguedOrTierOnlyNameIsNeverAboveLow() {
        assertThat(ProductCatalog.name("hdfc", "Zorbo HDFC Bank Credit Card")).isEqualTo("Zorbo");
        assertThat(ProductCatalog.isCatalogued("hdfc", "Zorbo")).isFalse();
        assertThat(ProductCatalog.printable("hdfc", "Zorbo")).isEqualTo("(uncatalogued)");
        assertThat(ProductCatalog.tierOnly("Platinum")).isTrue();
        assertThat(ProductCatalog.tierOnly("Visa Platinum")).isFalse();     // a real product name
        assertThat(ProductCatalog.tierOnly("League Platinum")).isFalse();
        Entity e = new Entity("hdfc", "1111");
        e.kind = Kind.CREDIT_CARD;
        e.variant = "Zorbo";
        e.variantAmbiguous = true;
        e.variantMails = 5;
        e.variantSpanDays = 120;
        e.variantChannels = 2;
        assertThat(Contract.variantMax(e)).isEqualTo(Conf.LOW);
    }

    @Test
    void productPhrasesUseTheCatalogue_genericWordsNeedTheIssuer_neverDebit() {
        assertThat(FeatureExtractor.productPhrases("Your Swiggy HDFC Bank Credit Card XX1234 statement", "hdfc")).containsExactly("Swiggy");
        assertThat(FeatureExtractor.productPhrases("Your HDFC Bank Platinum Credit Card XX1234", "hdfc")).containsExactly("Platinum");
        assertThat(FeatureExtractor.productPhrases("Use your Platinum Credit Card XX1234 today", "hdfc")).isEmpty();
        assertThat(FeatureExtractor.productPhrases("Your Millennia Debit Card XX1234 was used", "hdfc")).isEmpty();
        assertThat(FeatureExtractor.productPhrases("Your Regalia Gold Credit Card XX1234", "hdfc")).containsExactly("Regalia Gold");
        // welcome / dispatch / activation: "<Issuer> <Name>" counts without "card"
        assertThat(FeatureExtractor.productPhrases("Welcome to HDFC Bank Infinia, card ending 1234", "hdfc")).containsExactly("Infinia");
        assertThat(FeatureExtractor.productPhrases("HDFC Bank Infinia members get lounge access on card ending 1234", "hdfc")).isEmpty();
    }

    @Test
    void directoryCoversPsuSfbPaymentsBanksAndFintech_walletsAreApps() {
        assertThat(BankDirectory.bankForDomain("alerts.pnb.co.in")).isEqualTo("pnb");
        assertThat(BankDirectory.bankForDomain("unionbankofindia.co.in")).isEqualTo("union_bank");
        assertThat(BankDirectory.bankForDomain("ujjivansfb.in")).isEqualTo("ujjivan");
        assertThat(BankDirectory.bankForDomain("airtelbank.com")).isEqualTo("airtel_payments");
        assertThat(BankDirectory.bankForDomain("citibank.co.in")).isEqualTo("citi");
        assertThat(BankDirectory.bankForDomain("uni.club")).isEqualTo("uni");
        assertThat(BankDirectory.isApp(BankDirectory.bankForDomain("mobikwik.com"))).isTrue();
        assertThat(BankDirectory.isApp(BankDirectory.bankForDomain("gokiwi.in"))).isTrue();
        assertThat(BankDirectory.isPaymentsBank("paytm_payments")).isTrue();
        assertThat(BankDirectory.ISSUER_NAMES.size()).isGreaterThanOrEqualTo(40);
        // longest name first: "state bank of india" is not also "bank of india"; "city union bank" is not "union bank"
        assertThat(BankDirectory.issuersIn("paid to state bank of india card")).containsExactly("sbi");
        assertThat(BankDirectory.issuersIn("city union bank account")).containsExactly("cub");
        assertThat(BankDirectory.dominantIssuer("south indian bank statement south indian bank")).isEqualTo("south_indian");
    }

    @Test
    void walletAndPrepaidNumbersAreRejected() {
        assertThat(FeatureExtractor.numbersIn("Rs 500 added to your Airtel wallet XXXXXX4692").get(0).label()).isEqualTo(LabelKind.REJECT_PREPAID);
        assertThat(FeatureExtractor.numbersIn("Your prepaid card XX7781 was loaded").get(0).label()).isEqualTo(LabelKind.REJECT_PREPAID);
        assertThat(FeatureExtractor.numbersIn("Your credit card XX7781 was used").get(0).label()).isEqualTo(LabelKind.CREDIT_CARD);
    }

    @Test
    void aNumberAlsoNamedAsAWalletIsNeverReported() {
        SyntheticMailbox mb = new SyntheticMailbox(301);
        for (int m = 0; m < MONTHS; m++) {
            mb.mail("airtelbank.com", mb.day(m), "Debit alert on your Airtel Payments Bank account",
                    "Rs 120.00 debited from your Airtel Payments Bank a/c XX4692 at a merchant.", true);
            mb.mail("airtelbank.com", mb.day(m), "Your Airtel Payments Bank account statement",
                    "Statement for your account XX4692 is ready. Available balance shown.", true);
        }
        Detection before = mb.detectRaw();
        assertThat(before.find("airtel_payments", "4692")).isNotNull();
        mb.mail("airtelbank.com", mb.day(3), "Money added", "Rs 500.00 added to your Airtel wallet XXXXXX4692.", true);
        assertThat(mb.detectRaw().find("airtel_payments", "4692")).isNull();
    }

    @Test
    void theLeadingGroupOfASpacedCardNumberIsNotItsLast4() {
        List<FeatureExtractor.Num> nums = FeatureExtractor.numbersIn("Card No 4893 99XX XXXX 2496");
        assertThat(nums).extracting(FeatureExtractor.Num::last4).containsExactly("2496");
        assertThat(FeatureExtractor.numbersIn("Card No 489399XXXXXX2496")).extracting(FeatureExtractor.Num::last4).containsExactly("2496");
    }

    @Test
    void aPlaceholderShapedNumberIsNeverAboveLow() {
        Entity e = new Entity("hdfc", "1234");
        e.evidenceMails = 10;
        e.evidenceSpanDays = 200;
        e.bankChannels.add(Vocab.Channel.BILLING);
        e.bankChannels.add(Vocab.Channel.STATEMENT_CONTENT);
        assertThat(Contract.existsMax(e)).isEqualTo(Conf.LOW);
        Entity f = new Entity("hdfc", "4821");
        f.evidenceMails = 10;
        f.evidenceSpanDays = 200;
        f.bankChannels.addAll(e.bankChannels);
        assertThat(Contract.existsMax(f)).isEqualTo(Conf.HIGH);
    }

    @Test
    void theCatalogueLoadsEveryIssuerUnderAKnownBankId() {
        assertThat(ProductCatalog.issuers()).contains("hdfc", "yes", "axis", "citi", "union_bank").hasSizeGreaterThanOrEqualTo(30);
        for (String b : ProductCatalog.issuers()) {
            boolean known = BankDirectory.ISSUER_NAMES.containsKey(b) || BankDirectory.EXTRA_BANK_DOMAINS.containsValue(b)
                    || com.financeos.gmail.classify.BankSenderDomains.domains().stream().anyMatch(d -> b.equals(BankDirectory.bankForDomain(d)));
            assertThat(known).as("catalogue issuer id " + b + " is a directory bank id").isTrue();
            assertThat(BankDirectory.variantsOf(b)).isNotEmpty();
        }
    }

    @Test
    void addOnWordingVariants() {
        assertThat(FeatureExtractor.eventsIn("an add-on credit card ending 1234 has been issued for your family member")).contains(EventType.ADD_ON);
        assertThat(FeatureExtractor.eventsIn("supplementary credit card xx1234 issued on your card xx5678")).contains(EventType.ADD_ON);
        assertThat(FeatureExtractor.eventsIn("add-on cardholder details updated for card xx1234")).contains(EventType.ADD_ON);
    }

    @Test
    void limitChangeSentences() {
        assertThat(FeatureExtractor.limitHitIn("The credit limit on your card XX1234 has been increased to Rs. 3,50,000.", "1234").bucket()).isEqualTo("3-5L");
        assertThat(FeatureExtractor.limitHitIn("Your new credit limit is INR 1,20,000 on card XX1234", "1234").bucket()).isEqualTo("1-3L");
        assertThat(FeatureExtractor.limitHitIn("Available credit limit on card XX1234 has been updated to Rs 20,000", "1234")).isNull();
        assertThat(FeatureExtractor.limitHitIn("You are eligible: limit on card XX1234 can be increased to Rs 5,00,000", "1234")).isNull();
        assertThat(FeatureExtractor.limitHitIn("The credit limit on your card has been increased to Rs. 3,50,000.", null)).isNull();
    }

    @Test
    void aLimitChangeMailIsLowAndAnAgreeingStatementDecides() {
        SyntheticMailbox mb = new SyntheticMailbox(302);
        BankSpec hdfc = mb.bank("hdfc", 0, true, false, false);
        Card c = new Card(hdfc, "6611", Kind.CREDIT_CARD, null, 0, LAST);
        mb.cardAlerts(c, 0, LAST, false);
        mb.cardBilling(c, 0, LAST, List.of());
        mb.mail(hdfc.domain(), mb.day(LAST), "Credit limit enhanced on your HDFC Bank Credit Card",
                "Dear Customer, the credit limit on your HDFC Bank Credit Card XX6611 has been increased to Rs. 7,50,000.", true);
        Entity e = mb.detect(302).find("hdfc", "6611");
        assertThat(e.limitBucket).isEqualTo("5-10L");
        assertThat(e.limitConf).isEqualTo(Conf.LOW);
        assertThat(e.limitFromMail).isTrue();
    }

    @Test
    void aPaymentAppConfirmsKindOnlyAwayFromBankMails() {
        SyntheticMailbox mb = new SyntheticMailbox(303);
        BankSpec icici = mb.bank("icici", 0, true, false, false);
        Card c = new Card(icici, "4420", Kind.CREDIT_CARD, null, 0, LAST);
        mb.cardBilling(c, 0, LAST, List.of());
        Detection d0 = mb.detectRaw();
        Entity e0 = d0.find("icici", "4420");
        int channels0 = e0.kindChannels;
        // on a statement day: coincident with a bank mail, so it confirms nothing
        mb.mail("cred.club", SyntheticMailbox.START.plusMonths(2).atDay(12), "Payment of Rs 500 received",
                "Payment of Rs 500 received for your ICICI Bank Credit Card XX4420.", true);
        assertThat(mb.detectRaw().find("icici", "4420").kindAppConfirmed).isFalse();
        // ten days away from any bank mail: an independent confirmation
        mb.mail("cred.club", SyntheticMailbox.START.plusMonths(2).atDay(25), "Payment of Rs 500 received",
                "Payment of Rs 500 received for your ICICI Bank Credit Card XX4420.", true);
        Entity e1 = mb.detectRaw().find("icici", "4420");
        assertThat(e1.kindAppConfirmed).isTrue();
        assertThat(e1.kindChannels).isEqualTo(channels0);   // confirms only: an app adds no channel
    }

    @Test
    void anAliasAlwaysReportsItsEntrysCanonicalShortName() {
        assertThat(ProductCatalog.canonical("kotak", "Kotak 811 #DreamDifferent Credit Card")).isEqualTo("811");
        assertThat(ProductCatalog.canonical("kotak", "811 Dream Different")).isEqualTo("811");
        assertThat(ProductCatalog.canonical("citi", "IndianOil Citi")).isEqualTo("IndianOil");
        assertThat(ProductCatalog.canonical("uni", "Uni GoldX")).isEqualTo("GoldX");
    }

    @Test
    void oldAndNewNamesOfARenamedProductAreOneProduct_reportedByTheCurrentName() {
        assertThat(ProductCatalog.canonical("hsbc", "HSBC Cashback Credit Card")).isEqualTo("Live+");
        assertThat(ProductCatalog.canonical("hdfc", "MoneyBack")).isEqualTo("MoneyBack+");
        assertThat(ProductCatalog.formerName("hsbc", "Cashback")).isTrue();
        assertThat(ProductCatalog.formerName("hsbc", "Live+")).isFalse();
        assertThat(ProductCatalog.sameProduct("hsbc", "Cashback", "Live+")).isTrue();
        // a statement under the old name and a mail under the new one: no conflict, the current name is reported
        SyntheticMailbox mb = new SyntheticMailbox(304);
        BankSpec hsbc = mb.bank("hsbc", 0, true, false, false);
        Card c = new Card(hsbc, "5810", Kind.CREDIT_CARD, null, 0, LAST);
        mb.cardAlerts(c, 0, LAST, false);
        mb.cardBilling(c, 0, LAST, List.of());
        for (int m = 0; m < MONTHS; m++) {
            var ym = SyntheticMailbox.START.plusMonths(m);
            mb.statements.add(new StatementContent("PDF_FOLDER", "hsbc", "5810", Kind.CREDIT_CARD, ym, null, null, List.of(), false,
                    ym.atDay(12), null, null, m < MONTHS / 2 ? "HSBC Cashback Credit Card" : "HSBC Live+ Credit Card"));
        }
        Entity e = mb.detect(304).find("hsbc", "5810");
        assertThat(e.variantConflicts).isZero();
        assertThat(e.variant).isEqualTo("Live+");
        assertThat(e.variantConf.atLeast(Conf.MEDIUM)).isTrue();
    }

    @Test
    void anIssuersOwnTierWordProductCountsOnlyFromAProductPhrase() {
        assertThat(ProductCatalog.tierProduct("amex", "Gold")).isTrue();
        assertThat(ProductCatalog.tierProduct("amex", "Platinum Reserve")).isTrue();
        assertThat(ProductCatalog.tierProduct("hdfc", "Platinum")).isFalse();
        assertThat(FeatureExtractor.productPhrases("Your American Express Gold Card XX1234 statement is ready", "amex")).containsExactly("Gold");
        assertThat(FeatureExtractor.productPhrases("Earn Gold status with American Express XXXX1234", "amex")).isEmpty();
        assertThat(FeatureExtractor.productPhrases("Your American Express Platinum Travel Card XX1234", "amex")).containsExactly("Platinum Travel");
        Entity e = new Entity("amex", "1009");
        e.kind = Kind.CREDIT_CARD;
        e.variant = "Gold";
        e.variantAmbiguous = !ProductCatalog.isCatalogued("amex", "Gold") || ProductCatalog.tierOnly("Gold") && !ProductCatalog.tierProduct("amex", "Gold");
        assertThat(e.variantAmbiguous).isFalse();
        // elsewhere a tier word alone stays ambiguous
        assertThat(ProductCatalog.tierOnly("Platinum") && !ProductCatalog.tierProduct("hsbc", "Platinum")).isTrue();
    }

    @Test
    void aMultiCardStatementListsEachAddOnUnderItsPrimary() {
        SyntheticMailbox mb = new SyntheticMailbox(305);
        BankSpec axis = mb.bank("axis", 0, false, false, false);
        Card p = new Card(axis, "9101", Kind.CREDIT_CARD, null, 0, LAST);
        mb.cardAlerts(p, 0, LAST, false);
        mb.cardBilling(p, 0, LAST, List.of());
        mb.cardAlerts(new Card(axis, "9102", Kind.CREDIT_CARD, null, 2, LAST), 2, LAST, false);
        mb.cardAlerts(new Card(axis, "9103", Kind.CREDIT_CARD, null, 2, LAST), 2, LAST, false);
        for (int m = 2; m < MONTHS; m++) {
            var ym = SyntheticMailbox.START.plusMonths(m);
            // one statement covering the primary and two add-ons (its own number in the list is ignored)
            mb.statements.add(new StatementContent("PDF_FOLDER", "axis", "9101", Kind.CREDIT_CARD, ym, null, null,
                    List.of("9102", "9103", "9101"), false, ym.atDay(15)));
        }
        Detection d = mb.detect(305);
        for (String l4 : List.of("9102", "9103")) {
            Entity a = d.find("axis", l4);
            assertThat(a.lifecycle).as(l4).isEqualTo(Vocab.Lifecycle.ADD_ON);
            assertThat(a.linkedTo).as(l4).isEqualTo("axis:9101");
            assertThat(a.lifecycleConf.atLeast(Conf.MEDIUM)).as(l4).isTrue();
        }
        assertThat(d.find("axis", "9101").lifecycle).isNotEqualTo(Vocab.Lifecycle.ADD_ON);
    }

    @Test
    void aStatementPeriodComesFromItsStatementDate_notTheMailDate() {
        SyntheticMailbox mb = new SyntheticMailbox(306);
        BankSpec sbi = mb.bank("sbi", 2, false, false, false);
        Card c = new Card(sbi, "3307", Kind.CREDIT_CARD, null, 0, LAST);
        mb.cardAlerts(c, 0, LAST, false);
        var first = SyntheticMailbox.START.plusMonths(1);
        // the statement dated in month 1 arrives (as a folder file) with no mail of its own
        mb.statements.add(new StatementContent("PDF_FOLDER", "sbi", "3307", Kind.CREDIT_CARD, first, null, null, List.of(), false, first.atDay(20)));
        Entity e = mb.detect(306).find("sbi", "3307");
        assertThat(e.billingMonths).contains(first);
    }

    @Test
    void aPlaceholderShapedStatementHeaderNumberIsAtMostLow() {
        SyntheticMailbox mb = new SyntheticMailbox(307);
        for (int m = 0; m < MONTHS; m++) {
            var ym = SyntheticMailbox.START.plusMonths(m);
            mb.statements.add(new StatementContent("PDF_FOLDER", "hdfc", "1234", Kind.CREDIT_CARD, ym, null, null, List.of(), false, ym.atDay(5)));
        }
        Entity e = mb.detect(307).find("hdfc", "1234");
        // blank or LOW: never a confident report of a sample number
        assertThat(e == null || e.existsConf == Conf.LOW).isTrue();
    }

    /** One card with alerts and bills, plus a folder statement per month naming the product given for that month. */
    private static Entity cardNamed(long seed, String bank, String l4, java.util.function.IntFunction<String> productOfMonth) {
        SyntheticMailbox mb = new SyntheticMailbox(seed);
        BankSpec b = mb.bank(bank, 2, true, false, false);
        Card c = new Card(b, l4, Kind.CREDIT_CARD, null, 0, LAST);
        mb.cardAlerts(c, 0, LAST, false);
        mb.cardBilling(c, 0, LAST, List.of());
        for (int m = 0; m < MONTHS; m++) {
            var ym = SyntheticMailbox.START.plusMonths(m);
            mb.statements.add(new StatementContent("PDF_FOLDER", bank, l4, Kind.CREDIT_CARD, ym, null, null, List.of(), false,
                    ym.atDay(12), null, null, productOfMonth.apply(m)));
        }
        return mb.detect(seed).find(bank, l4);
    }

    @Test
    void onlyTheShorterOfTwoNestedNamesSeen_isLow() {
        assertThat(ProductCatalog.longerNames("hdfc", "Regalia")).contains("Regalia Gold");
        Entity e = cardNamed(310, "hdfc", "6120", m -> "HDFC Bank Regalia Credit Card");
        assertThat(e.variant).isEqualTo("Regalia");
        assertThat(e.variantConf).isEqualTo(Conf.LOW);
        assertThat(e.variantCandidates).startsWith("Regalia").contains("Regalia Gold");   // seen name, then what it nests with
    }

    @Test
    void theLongerNestedNameSeen_isLowToo() {
        Entity e = cardNamed(311, "hdfc", "6121", m -> "HDFC Bank Regalia Gold Credit Card");
        assertThat(e.variant).isEqualTo("Regalia Gold");
        assertThat(e.variantConf).isEqualTo(Conf.LOW);
        assertThat(e.variantCandidates).startsWith("Regalia Gold").contains("Regalia");
        // a name that nests with nothing follows the normal rules
        Entity f = cardNamed(315, "hdfc", "6125", m -> "HDFC Bank Infinia Credit Card");
        assertThat(ProductCatalog.nestedNames("hdfc", "Infinia")).isEmpty();
        assertThat(f.variantConf.atLeast(Conf.MEDIUM)).isTrue();
        assertThat(f.variantCandidates).isEmpty();
    }

    @Test
    void bothNestedNamesOnOneNumber_theTimelinePicksTheNameButItStaysLow() {
        Entity e = cardNamed(312, "hdfc", "6122", m -> m < MONTHS / 2 ? "HDFC Bank Regalia Credit Card" : "HDFC Bank Regalia Gold Credit Card");
        assertThat(e.variant).isEqualTo("Regalia Gold");
        assertThat(e.variantConf).isEqualTo(Conf.LOW);
        assertThat(e.variantCandidates).contains("Regalia Gold", "Regalia");
    }

    @Test
    void bothNestedNamesOnOneNumber_interleaved_isLowWithBothCandidates() {
        Entity e = cardNamed(313, "hdfc", "6123", m -> m % 2 == 0 ? "HDFC Bank Regalia Credit Card" : "HDFC Bank Regalia Gold Credit Card");
        assertThat(e.variantConf).isEqualTo(Conf.LOW);
        assertThat(e.variantCandidates).contains("Regalia", "Regalia Gold");
        // the longer name back to the shorter one is no upgrade either
        Entity f = cardNamed(314, "hdfc", "6124", m -> m < MONTHS / 2 ? "HDFC Bank Regalia Gold Credit Card" : "HDFC Bank Regalia Credit Card");
        assertThat(f.variantConf).isEqualTo(Conf.LOW);
    }

    @Test
    void namesDifferingByAGluedSuffixAreLow() {
        assertThat(ProductCatalog.textNestedNames("au", "Zenith")).contains("Zenith+");
        assertThat(ProductCatalog.textNestedNames("au", "Zenith+")).contains("Zenith");
        assertThat(ProductCatalog.textNestedNames("hdfc", "Regalia")).isEmpty();   // word nesting is the other rule
    }

    // ------------------------------------------------------------------ review round: confidence contract holes

    @Test
    void aLoanNumberIsNeverReported_evenBesideAnAccountWord() {
        assertThat(FeatureExtractor.kindFrom(List.of("loan", "acct", "no"))).isEqualTo(LabelKind.LOAN);
        assertThat(FeatureExtractor.kindFrom(List.of("your", "emi", "acct"))).isEqualTo(LabelKind.LOAN);
        assertThat(FeatureExtractor.kindFrom(List.of("savings", "acct"))).isEqualTo(LabelKind.ACCOUNT);
        SyntheticMailbox mb = new SyntheticMailbox(320);
        BankSpec hdfc = mb.bank("hdfc", 0, true, false, false);
        Card c = new Card(hdfc, "6611", Kind.CREDIT_CARD, null, 0, LAST);
        mb.cardAlerts(c, 0, LAST, false);
        for (int m = 0; m < 3; m++) {   // more than 60 days of EMI reminders and EMI debits
            mb.mail(hdfc.domain(), mb.day(m), "EMI due reminder", "Dear Customer, the EMI of INR 12,500.00 for your Loan account no XX5678 is due on 05.", true);
            mb.mail(hdfc.domain(), mb.day(m), "EMI debited", "The EMI of INR 12,500.00 for Loan A/c XX5678 has been debited. Thank you.", true);
            mb.mail(hdfc.domain(), mb.day(m), "Loan statement", "Your loan account statement for Loan Account Number XX5678 is ready.", true);
        }
        Detection d = mb.detect(320);
        assertThat(d.find("hdfc", "5678")).isNull();
        for (var n : FeatureExtractor.numbersIn("the EMI for your Loan account no XX5678 is due")) assertThat(n.label()).isEqualTo(LabelKind.LOAN);
    }

    @Test
    void aResampleCountsTowardHighOnlyAtTheFullRunsConfidence() {
        SyntheticMailbox mb = new SyntheticMailbox(321);
        BankSpec icici = mb.bank("icici", 0, false, false, false);
        Card c = new Card(icici, "4471", Kind.CREDIT_CARD, null, 0, LAST);
        mb.cardAlerts(c, 0, LAST, false);
        // one statement mail is the only second channel: most resamples keep the card, but a fifth of them at MEDIUM
        mb.mail(icici.domain(), mb.day(LAST / 2), "Your ICICI Bank Credit Card statement",
                "Your ICICI Bank Credit Card XX4471 statement is attached. Total amount due INR 1,000.00.", true);
        Detection raw = mb.detectRaw();
        assertThat(raw.find("icici", "4471").existsConf).isEqualTo(Conf.HIGH);
        Stability.Result r = Stability.detectStable(mb.mails.stream().map(FeatureExtractor::derive).toList(), mb.statements, 321);
        assertThat(r.stability().get("E|icici:4471")).isLessThan(Stability.HIGH_MIN);
        assertThat(r.detection().find("icici", "4471").existsConf).isEqualTo(Conf.MEDIUM);
    }

    @Test
    void twoLayoutsOnOneChannelAreNoBridge() {
        SyntheticMailbox mb = new SyntheticMailbox(322);
        BankSpec yes = mb.bank("yes", 2, false, false, false);
        for (int m = 0; m < MONTHS; m++) {
            mb.mail(yes.domain(), mb.day(m), "Debit alert", "Your A/c XX4455 is debited with INR 1,250.00 on 03-05. Avl Bal INR 10,000.00.", true);
            mb.mail(yes.domain(), mb.day(m), "Transaction alert", "INR 800.00 withdrawn from savings Acct XX4455 at an ATM on 04-05. Avl Bal INR 9,000.00.", true);
        }
        Entity e = mb.detect(322).find("yes", "4455");
        // two alert layouts are one channel: the number stays one layout's (LOW)
        assertThat(e == null || e.existsConf == Conf.LOW).isTrue();
    }

    @Test
    void paymentAppMonthsNeverFeedActivity() {
        SyntheticMailbox mb = new SyntheticMailbox(323);
        BankSpec icici = mb.bank("icici", 0, false, false, false);
        Card c = new Card(icici, "4420", Kind.CREDIT_CARD, null, 0, 5);
        mb.cardAlerts(c, 0, 5, false);
        mb.cardBilling(c, 0, 5, List.of());
        mb.mail("cred.club", SyntheticMailbox.START.plusMonths(10).atDay(15), "Payment of Rs 500 received",
                "Payment of Rs 500 received for your ICICI Bank Credit Card XX4420.", true);
        Entity e = mb.detectRaw().find("icici", "4420");
        assertThat(e.activityMonths.last().isAfter(SyntheticMailbox.START.plusMonths(6))).isFalse();
    }

    @Test
    void aStatementLinkedOnlyByPeriodGivesLowFactsAndNoEvidence() {
        SyntheticMailbox mb = new SyntheticMailbox(324);
        BankSpec hdfc = mb.bank("hdfc", 2, true, false, false);
        Card c = new Card(hdfc, "6130", Kind.CREDIT_CARD, null, 0, LAST);
        mb.cardAlerts(c, 0, LAST, false);
        mb.cardBilling(c, 0, LAST, List.of());
        Entity before = mb.detectRaw().find("hdfc", "6130");
        // folder statements with no number of their own: tied by period, even though the bank has one card only
        for (int m = 0; m < MONTHS; m++) {
            var ym = SyntheticMailbox.START.plusMonths(m);
            mb.statements.add(new StatementContent("PDF_FOLDER", "hdfc", null, Kind.CREDIT_CARD, ym, null, "3-5L", List.of(), false,
                    ym.atDay(10), null, null, "HDFC Bank Regalia Gold Credit Card"));
        }
        Detection d = mb.detectRaw();
        assertThat(d.statementLinks).anyMatch(l -> Detector.LINK_PERIOD.equals(l.outcome()));
        Entity e = d.find("hdfc", "6130");
        assertThat(e.limitConf).isEqualTo(Conf.LOW);
        assertThat(e.limitDocs).isZero();
        if (e.variantConf != null) assertThat(e.variantConf).isEqualTo(Conf.LOW);
        assertThat(e.variantMails).isZero();
        // no kind vote and no channel from a period-linked document
        assertThat(e.kindChannels).isEqualTo(before.kindChannels);
        assertThat(e.bankChannels).isEqualTo(before.bankChannels);
    }

    @Test
    void aMailsPdfTestifiesOnThatMailsChannel_aFolderDocumentOnStatementContent() {
        Map<String, Vocab.Role> roles = Map.of("c-service", Vocab.Role.CARD_SERVICE, "c-bill", Vocab.Role.STATEMENT, "c-other", Vocab.Role.OTHER);
        StatementContent doc = new StatementContent("PDF_MAIL", "hdfc", "1111", Kind.CREDIT_CARD, null, null, null, List.of(), false);
        assertThat(Detector.docChannel(doc.withOrigin("c-service", java.time.LocalDate.of(2025, 1, 5)), roles)).isEqualTo(Vocab.Channel.SERVICE);
        assertThat(Detector.docChannel(doc.withOrigin("c-bill", java.time.LocalDate.of(2025, 1, 5)), roles)).isEqualTo(Vocab.Channel.BILLING);
        assertThat(Detector.docChannel(doc.withOrigin("c-other", java.time.LocalDate.of(2025, 1, 5)), roles)).isEqualTo(Vocab.Channel.STATEMENT_CONTENT);
        assertThat(Detector.docChannel(new StatementContent("PDF_FOLDER", "hdfc", "1111", Kind.CREDIT_CARD, null, null, null, List.of(), false), roles))
                .isEqualTo(Vocab.Channel.STATEMENT_CONTENT);
    }

    @Test
    void undatedSightingsDoNotBreakTheTimelineRules() {
        List<MailDerived> mails = new java.util.ArrayList<>();
        for (int i = 0; i < 6; i++) {
            mails.add(FeatureExtractor.derive(new MailFeature("hdfcbank.net", null, "Transaction alert",
                    "Thank you for using your HDFC Bank Credit Card ending 5511 for INR 100.00.", null, List.of())));
            mails.add(FeatureExtractor.derive(new MailFeature("hdfcbank.net", null, "Spent on your card",
                    "INR 200.00 spent on HDFC Bank Credit Card XX5511 at a store.", null, List.of())));
        }
        mails.add(FeatureExtractor.derive(new MailFeature("hdfcbank.net", null, "Card replaced",
                "Your HDFC Bank Credit Card XX5511 has been replaced with a new card XX5512.", null, List.of())));
        mails.add(FeatureExtractor.derive(new MailFeature("hdfcbank.net", java.time.Instant.parse("2025-06-01T06:00:00Z"), "Card closed",
                "Your HDFC Bank Credit Card XX5511 has been permanently closed.", null, List.of())));
        Detection d = Detector.detect(mails, List.of());
        assertThat(d).isNotNull();
    }

    @Test
    void anUpgradeSettledNameIsStillCheckedForALongerProduct() {
        // rbl sells Platinum, Platinum Maxima and Platinum Maxima Plus
        assertThat(ProductCatalog.longerNames("rbl", "Platinum Maxima")).contains("Platinum Maxima Plus");
        List<StatementContent> docs = new java.util.ArrayList<>();
        for (int m = 0; m < MONTHS; m++) {
            var ym = SyntheticMailbox.START.plusMonths(m);
            docs.add(new StatementContent("PDF_FOLDER", "rbl", "7301", Kind.CREDIT_CARD, ym, null, null, List.of(), false, ym.atDay(10), null, null,
                    m < MONTHS / 2 ? "RBL Bank Platinum Credit Card" : "RBL Bank Platinum Maxima Credit Card"));
        }
        Entity e = Detector.detect(List.of(), docs).find("rbl", "7301");
        assertThat(e.variant).isEqualTo("Platinum Maxima");
        assertThat(e.variantAmbiguous).isTrue();
        assertThat(e.variantConf).isEqualTo(Conf.LOW);
    }

    @Test
    void twoPdfsInOneMailAreOneWitness_neverASpan() {
        List<StatementContent> docs = new java.util.ArrayList<>();
        var mailDay = java.time.LocalDate.of(2025, 9, 3);
        for (int m : new int[]{1, 7}) {
            var ym = SyntheticMailbox.START.plusMonths(m);
            docs.add(new StatementContent("PDF_MAIL", "hdfc", "7788", Kind.CREDIT_CARD, ym, null, null, List.of(), false, ym.atDay(10))
                    .withOrigin("cluster-x", mailDay));
        }
        Entity e = Detector.detect(List.of(), docs).find("hdfc", "7788");
        assertThat(e == null || (e.evidenceSpanDays == 0 && e.existsConf == Conf.LOW)).isTrue();
    }
}
