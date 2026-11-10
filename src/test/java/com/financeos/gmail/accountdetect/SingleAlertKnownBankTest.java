package com.financeos.gmail.accountdetect;

import com.financeos.domain.account.AccountType;
import com.financeos.gmail.accountdetect.Detection.Entity;
import com.financeos.gmail.accountdetect.Vocab.Kind;
import com.financeos.gmail.accountdetect.suggest.AccountSuggestion;
import com.financeos.gmail.accountdetect.suggest.AccountSuggestion.Band;
import com.financeos.gmail.accountdetect.suggest.ExistingAccounts;
import com.financeos.gmail.accountdetect.suggest.SuggestionPlanner;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** One card alert at a bank the user already uses is a possible new card; elsewhere (or for a known number) it stays dropped. Made-up data. */
class SingleAlertKnownBankTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 11, 10);
    private static final String ALERT = "Credit Card Transaction Alert: Rs. 1,250.00 was spent on your HSBC Credit Card ending 7898 at ACME STORE on 09-11-2026.";

    private static SyntheticMailbox alertOnly(String domain) {
        SyntheticMailbox mb = new SyntheticMailbox(5);
        // the same alert layout earlier, for the user's two known cards (so the layout's number slot reads as an own number)
        for (int m = 5; m <= 10; m += 1) {
            String l4 = m % 2 == 0 ? "7106" : "9724";
            mb.mail(domain, LocalDate.of(2026, m, 6), "Credit Card Transaction Alert", ALERT.replace("7898", l4).replace("09-11-2026", "06-0" + (m < 10 ? m : 9) + "-2026"), true);
        }
        mb.mail(domain, LocalDate.of(2026, 11, 9), "Credit Card Transaction Alert", ALERT, true);
        return mb;
    }

    private static Detection detect(SyntheticMailbox mb, Detector.Options opt) {
        Detection dd = Detector.detect(mb.mails.stream().map(FeatureExtractor::derive).toList(), List.of(), opt); return dd;
    }

    private static ExistingAccounts hsbcUser() {
        ExistingAccounts.Acc a = new ExistingAccounts.Acc(UUID.randomUUID(), "HSBC Card", AccountType.credit_card, "hsbc", true, null,
                List.of(new ExistingAccounts.CardRef(UUID.randomUUID(), "7106"), new ExistingAccounts.CardRef(UUID.randomUUID(), "9724")), List.of());
        return new ExistingAccounts(List.of(a));
    }

    private static Detector.Options known(ExistingAccounts ex) {
        return Detector.Options.ALL.withKnown(ex.openBanks(), ex.allNumbers());
    }

    @Test
    void withoutKnownBanksASingleAlertIsDroppedAsBefore() {
        Detection d = detect(alertOnly("hsbc.bank.in"), Detector.Options.ALL);
        assertThat(d.find("hsbc", "7898")).isNull();
    }

    @Test
    void aSingleCreditCardAlertAtABankWithAnOpenAccountIsAPossibleCreditCard() {
        ExistingAccounts ex = hsbcUser();
        Detection d = detect(alertOnly("hsbc.bank.in"), known(ex));
        Entity e = d.find("hsbc", "7898");
        assertThat(e).isNotNull();
        assertThat(e.kind).isEqualTo(Kind.CREDIT_CARD);
        assertThat(e.existsConf).isEqualTo(Vocab.Conf.LOW);

        List<SuggestionPlanner.Plan> plans = SuggestionPlanner.plan(d, ex, TODAY);
        // the two known cards match their accounts; the new one is the only suggestion that is not matched
        assertThat(plans).filteredOn(x -> x.match() == null).hasSize(1);
        SuggestionPlanner.Plan p = plans.stream().filter(x -> x.match() == null).findFirst().orElseThrow();
        assertThat(p.key()).isEqualTo("hsbc:CREDIT_CARD:7898");
        assertThat(p.kind()).isEqualTo(AccountSuggestion.Kind.CREDIT_CARD);
        assertThat(p.band()).isEqualTo(Band.MAYBE);
        assertThat(p.match()).isNull();
    }

    @Test
    void aSingleAlertAtABankWithNoAccountIsStillDropped() {
        ExistingAccounts other = new ExistingAccounts(List.of(new ExistingAccounts.Acc(UUID.randomUUID(), "Axis", AccountType.bank_account, "axis", true, "1111", List.of(), List.of())));
        Detection d = detect(alertOnly("hsbc.bank.in"), known(other));
        assertThat(d.find("hsbc", "7898")).isNull();
    }

    @Test
    void aClosedOnlyBankDoesNotCount() {
        ExistingAccounts closed = new ExistingAccounts(List.of(new ExistingAccounts.Acc(UUID.randomUUID(), "HSBC", AccountType.credit_card, "hsbc", false, null, List.of(), List.of())));
        assertThat(detect(alertOnly("hsbc.bank.in"), known(closed)).find("hsbc", "7898")).isNull();
    }

    @Test
    void aNumberTheUserAlreadyHasAtAnyBankMakesNoSuggestion() {
        // the same four digits are the user's card at hsbc, or an account at another bank
        ExistingAccounts hasCard = new ExistingAccounts(List.of(new ExistingAccounts.Acc(UUID.randomUUID(), "HSBC Card", AccountType.credit_card, "hsbc", true, null,
                List.of(new ExistingAccounts.CardRef(UUID.randomUUID(), "7898")), List.of())));
        assertThat(detect(alertOnly("hsbc.bank.in"), known(hasCard)).find("hsbc", "7898")).isNull();

        ExistingAccounts otherBank = new ExistingAccounts(List.of(
                new ExistingAccounts.Acc(UUID.randomUUID(), "HSBC Card", AccountType.credit_card, "hsbc", true, null, List.of(), List.of()),
                new ExistingAccounts.Acc(UUID.randomUUID(), "Axis", AccountType.bank_account, "axis", true, "7898", List.of(), List.of())));
        assertThat(detect(alertOnly("hsbc.bank.in"), known(otherBank)).find("hsbc", "7898")).isNull();
    }

    @Test
    void helpersListOpenBanksAndEveryNumber() {
        ExistingAccounts ex = hsbcUser();
        assertThat(ex.openBanks()).containsExactly("hsbc");
        assertThat(ex.allNumbers()).containsExactlyInAnyOrder("7106", "9724");
        assertThat(Set.copyOf(ex.allNumbers())).doesNotContain("7898");
    }

    // ------------------------------------------------------------------ review fixes

    private static Detection detect(SyntheticMailbox mb, List<StatementContent> statements, Detector.Options opt) {
        return Detector.detect(mb.mails.stream().map(FeatureExtractor::derive).toList(), statements, opt);
    }

    private static void alert(SyntheticMailbox mb, LocalDate d, String l4) {
        mb.mail("hsbc.bank.in", d, "Credit Card Transaction Alert", ALERT.replace("7898", l4), true);
    }

    @Test
    void aNewCardIsPossibleEvenWhenAnExistingCardAlsoAppearsInAStatement() {
        ExistingAccounts ex = hsbcUser();
        SyntheticMailbox mb = alertOnly("hsbc.bank.in");
        List<StatementContent> statements = List.of(
                new StatementContent("PDF_FOLDER", "hsbc", "7106", Kind.CREDIT_CARD, java.time.YearMonth.of(2026, 8), null, null, List.of(), false, LocalDate.of(2026, 8, 20)),
                new StatementContent("PDF_FOLDER", "hsbc", "7106", Kind.CREDIT_CARD, java.time.YearMonth.of(2026, 10), null, null, List.of(), false, LocalDate.of(2026, 10, 20)));
        Detection d = detect(mb, statements, known(ex));
        Entity e = d.find("hsbc", "7898");
        assertThat(e).isNotNull();
        assertThat(e.existsConf).isEqualTo(Vocab.Conf.LOW);
        assertThat(SuggestionPlanner.plan(d, ex, TODAY)).filteredOn(p -> p.key().equals("hsbc:CREDIT_CARD:7898")).singleElement()
                .satisfies(p -> assertThat(p.band()).isEqualTo(Band.MAYBE));
    }

    @Test
    void aPayeeSeenOnceInATransferAlertSlotIsNotACard() {
        SyntheticMailbox mb = new SyntheticMailbox(7);
        for (int m = 6; m <= 8; m++) {
            mb.mail("hdfcbank.net", LocalDate.of(2026, m, 6), "Funds transfer alert", "Rs. 5,000.00 sent via IMPS to beneficiary A/c XX1111 from your HDFC Bank A/c on 06-0" + m + "-2026.", true);
        }
        mb.mail("hdfcbank.net", LocalDate.of(2026, 10, 9), "Funds transfer alert", "Rs. 5,000.00 sent via IMPS to beneficiary A/c XX2222 from your HDFC Bank A/c on 09-10-2026.", true);
        ExistingAccounts ex = new ExistingAccounts(List.of(new ExistingAccounts.Acc(UUID.randomUUID(), "HDFC Savings", AccountType.bank_account, "hdfc", true, "0001", List.of(), List.of())));
        assertThat(detect(mb, List.of(), known(ex)).find("hdfc", "2222")).isNull();
    }

    @Test
    void theSlotClassIsTheSameWithAndWithoutTheRuleForTwoSlotShapes() {
        ExistingAccounts ex = hsbcUser();
        // shape 1: {A in 2 months, B once}: A is kept as before
        SyntheticMailbox one = new SyntheticMailbox(8);
        alert(one, LocalDate.of(2026, 9, 6), "7106");
        alert(one, LocalDate.of(2026, 10, 6), "7106");
        alert(one, LocalDate.of(2026, 11, 9), "7898");
        assertThat(detect(one, List.of(), Detector.Options.ALL).find("hsbc", "7106")).isNotNull();
        assertThat(detect(one, List.of(), known(ex)).find("hsbc", "7106")).isNotNull();

        // shape 2: {A x3, B, C, D once}: counterparty-like as before; A and the singles come out exactly as without the rule
        SyntheticMailbox two = new SyntheticMailbox(9);
        for (int m = 6; m <= 8; m++) alert(two, LocalDate.of(2026, m, 6), "7106");
        alert(two, LocalDate.of(2026, 9, 9), "1357");
        alert(two, LocalDate.of(2026, 10, 9), "2468");
        alert(two, LocalDate.of(2026, 11, 9), "3579");
        Detection without = detect(two, List.of(), Detector.Options.ALL);
        Detection with = detect(two, List.of(), known(ex));
        assertThat(with.entities.stream().map(Entity::key).toList()).isEqualTo(without.entities.stream().map(Entity::key).toList());
        assertThat(with.find("hsbc", "1357")).isNull();
        assertThat(with.find("hsbc", "2468")).isNull();
        assertThat(with.find("hsbc", "3579")).isNull();
    }

    @Test
    void aPartnerBankMatchDoesNotDependOnTheListOrder() {
        UUID low = UUID.fromString("00000000-0000-0000-0000-000000000001"), high = UUID.fromString("00000000-0000-0000-0000-000000000002");
        ExistingAccounts.Acc csb = new ExistingAccounts.Acc(high, "Edge", AccountType.credit_card, "csb", true, null, List.of(new ExistingAccounts.CardRef(UUID.randomUUID(), "4455")), List.of());
        ExistingAccounts.Acc federal = new ExistingAccounts.Acc(low, "Other", AccountType.credit_card, "federal", true, null, List.of(new ExistingAccounts.CardRef(UUID.randomUUID(), "4455")), List.of());
        UUID a = new ExistingAccounts(List.of(csb, federal)).match("jupiter", "4455", AccountSuggestion.Kind.CREDIT_CARD).accountId();
        UUID b = new ExistingAccounts(List.of(federal, csb)).match("jupiter", "4455", AccountSuggestion.Kind.CREDIT_CARD).accountId();
        assertThat(a).isEqualTo(b).isEqualTo(low);
        // the exact bank still wins over a partner
        assertThat(new ExistingAccounts(List.of(csb, federal)).match("csb", "4455", AccountSuggestion.Kind.CREDIT_CARD).accountId()).isEqualTo(high);
    }

    @Test
    void theMixedSlotExceptionFiresWhenEveryOtherNumberIsTheUsersOwn() {
        // {7106 x3 months, 9724 x3 months, 7898 once}: MIXED without the exception, OWN with it
        ExistingAccounts ex = hsbcUser();
        Detection with = detect(alertOnly("hsbc.bank.in"), known(ex));
        Detection without = detect(alertOnly("hsbc.bank.in"), Detector.Options.ALL);
        assertThat(without.find("hsbc", "7898")).isNull();
        assertThat(with.find("hsbc", "7898")).isNotNull();
        assertThat(SuggestionPlanner.plan(with, ex, TODAY)).filteredOn(p -> p.key().equals("hsbc:CREDIT_CARD:7898")).singleElement()
                .satisfies(p -> assertThat(p.band()).isEqualTo(Band.MAYBE));
        // the user's own cards come out as theirs (matched), never as new suggestions
        assertThat(SuggestionPlanner.plan(with, ex, TODAY)).filteredOn(p -> p.match() == null).hasSize(1);
    }

    @Test
    void theMixedSlotExceptionDoesNotFireWhenAnotherNumberOfTheSlotIsNotTheUsers() {
        // {7106 (own) x4 mails, 1357 (a repeated payee) x3 mails over 2 months, 7898 once}: MIXED as before; the payee stays dropped
        SyntheticMailbox mb = new SyntheticMailbox(21);
        for (int m = 5; m <= 8; m++) alert(mb, LocalDate.of(2026, m, 6), "7106");
        alert(mb, LocalDate.of(2026, 9, 6), "1357");
        alert(mb, LocalDate.of(2026, 10, 6), "1357");
        alert(mb, LocalDate.of(2026, 10, 7), "1357");
        alert(mb, LocalDate.of(2026, 11, 9), "7898");
        ExistingAccounts ex = new ExistingAccounts(List.of(new ExistingAccounts.Acc(UUID.randomUUID(), "HSBC Card", AccountType.credit_card, "hsbc", true, null,
                List.of(new ExistingAccounts.CardRef(UUID.randomUUID(), "7106")), List.of())));
        Detection without = detect(mb, List.of(), Detector.Options.ALL);
        Detection with = detect(mb, List.of(), known(ex));
        assertThat(with.find("hsbc", "1357")).isNull();
        assertThat(with.find("hsbc", "7898")).isNull();
        assertThat(with.entities.stream().map(Entity::key).toList()).isEqualTo(without.entities.stream().map(Entity::key).toList());
    }
}
