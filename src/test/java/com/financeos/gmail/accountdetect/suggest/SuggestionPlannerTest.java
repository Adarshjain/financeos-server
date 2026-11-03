package com.financeos.gmail.accountdetect.suggest;

import com.financeos.domain.account.AccountIdentifierKind;
import com.financeos.domain.account.AccountType;
import com.financeos.gmail.accountdetect.Detection;
import com.financeos.gmail.accountdetect.Vocab.Conf;
import com.financeos.gmail.accountdetect.Vocab.Kind;
import com.financeos.gmail.accountdetect.Vocab.Lifecycle;
import com.financeos.gmail.accountdetect.suggest.AccountSuggestion.Band;
import com.financeos.gmail.accountdetect.suggest.ExistingAccounts.Acc;
import com.financeos.gmail.accountdetect.suggest.ExistingAccounts.Alias;
import com.financeos.gmail.accountdetect.suggest.ExistingAccounts.CardRef;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static com.financeos.gmail.accountdetect.DetectionFixtures.detection;
import static com.financeos.gmail.accountdetect.DetectionFixtures.entity;
import static org.assertj.core.api.Assertions.assertThat;

/** Mapping, bands and matching against existing accounts, over made-up entities. */
class SuggestionPlannerTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 11, 3);

    private static SuggestionPlanner.Plan only(Detection.Entity e, ExistingAccounts existing) {
        List<SuggestionPlanner.Plan> plans = SuggestionPlanner.plan(detection(e), existing, TODAY);
        assertThat(plans).hasSize(1);
        return plans.get(0);
    }

    private static SuggestionPlanner.Plan find(List<SuggestionPlanner.Plan> plans, String key) {
        return plans.stream().filter(p -> p.key().equals(key)).findFirst().orElseThrow();
    }

    private static Acc bankAcc(String bank, String last4, boolean open) {
        return new Acc(UUID.randomUUID(), "Savings", AccountType.bank_account, bank, open, last4, List.of(), List.of());
    }

    private static Acc cardAcc(String bank, String... last4s) {
        List<CardRef> cards = java.util.Arrays.stream(last4s).map(l -> new CardRef(UUID.randomUUID(), l)).toList();
        return new Acc(UUID.randomUUID(), "Card", AccountType.credit_card, bank, true, null, cards, List.of());
    }

    private static ExistingAccounts of(Acc... accs) {
        return new ExistingAccounts(List.of(accs));
    }

    // ---------------------------------------------------------------- mapping and bands

    @Test
    void kindsMapToSuggestionKindsAndKeysUseTheBaseKind() {
        List<SuggestionPlanner.Plan> plans = SuggestionPlanner.plan(detection(
                entity("hdfc", "1111", Kind.ACCOUNT, Conf.HIGH),
                entity("hdfc", "2222", Kind.CREDIT_CARD, Conf.HIGH),
                entity("icici", "3333", Kind.DEBIT_CARD, Conf.MEDIUM)), ExistingAccounts.NONE, TODAY);
        assertThat(plans).extracting(SuggestionPlanner.Plan::key)
                .containsExactly("hdfc:BANK_ACCOUNT:1111", "hdfc:CREDIT_CARD:2222", "icici:DEBIT_CARD:3333");
        assertThat(plans).extracting(SuggestionPlanner.Plan::kind).containsExactly(
                AccountSuggestion.Kind.BANK_ACCOUNT, AccountSuggestion.Kind.CREDIT_CARD, AccountSuggestion.Kind.DEBIT_CARD);
        assertThat(plans).extracting(SuggestionPlanner.Plan::band).containsOnly(Band.FOUND);
    }

    @Test
    void anEntityWithoutAKindIsNotSuggested() {
        assertThat(SuggestionPlanner.plan(detection(entity("hdfc", "1111", null, Conf.MEDIUM)), ExistingAccounts.NONE, TODAY)).isEmpty();
    }

    @Test
    void closedReplacedOrUpgradedAtMediumIsOlderButLowIsNot() {
        for (Lifecycle l : List.of(Lifecycle.CLOSED, Lifecycle.REPLACED, Lifecycle.UPGRADED)) {
            Detection.Entity e = entity("hdfc", "2222", Kind.CREDIT_CARD, Conf.HIGH);
            e.lifecycle = l;
            e.lifecycleConf = Conf.MEDIUM;
            assertThat(SuggestionPlanner.band(e, TODAY)).as(l.name()).isEqualTo(Band.OLDER);
            e.lifecycleConf = Conf.LOW;
            assertThat(SuggestionPlanner.band(e, TODAY)).as(l + " LOW").isEqualTo(Band.FOUND);
        }
        Detection.Entity stopped = entity("hdfc", "2222", Kind.CREDIT_CARD, Conf.HIGH);
        stopped.lifecycle = Lifecycle.STOPPED;
        stopped.lifecycleConf = Conf.LOW;
        assertThat(SuggestionPlanner.band(stopped, TODAY)).isEqualTo(Band.FOUND);
    }

    @Test
    void lastSeenOverTwelveMonthsAgoIsOlderWhenMediumButLowStaysMaybe() {
        Detection.Entity medium = entity("hdfc", "2222", Kind.CREDIT_CARD, Conf.MEDIUM);
        medium.last = TODAY.minusMonths(12).minusDays(1);
        assertThat(SuggestionPlanner.band(medium, TODAY)).isEqualTo(Band.OLDER);
        medium.last = TODAY.minusMonths(12);
        assertThat(SuggestionPlanner.band(medium, TODAY)).isEqualTo(Band.FOUND);
        // a LOW guess is never promoted to a confident row, stale or not
        Detection.Entity low = entity("hdfc", "2222", Kind.CREDIT_CARD, Conf.LOW);
        low.last = TODAY.minusMonths(12).minusDays(1);
        assertThat(SuggestionPlanner.band(low, TODAY)).isEqualTo(Band.MAYBE);
    }

    @Test
    void aKindBelowMediumIsMaybeEvenWhenTheNumberIsSure() {
        for (Conf k : new Conf[]{Conf.LOW, null}) {
            Detection.Entity e = entity("hdfc", "2222", Kind.CREDIT_CARD, Conf.HIGH);
            e.kindConf = k;
            assertThat(SuggestionPlanner.band(e, TODAY)).as("kindConf " + k).isEqualTo(Band.MAYBE);
            e.last = TODAY.minusMonths(13);
            assertThat(SuggestionPlanner.band(e, TODAY)).as("stale, kindConf " + k).isEqualTo(Band.MAYBE);
        }
        Detection.Entity medium = entity("hdfc", "2222", Kind.CREDIT_CARD, Conf.HIGH);
        medium.kindConf = Conf.MEDIUM;
        assertThat(SuggestionPlanner.band(medium, TODAY)).isEqualTo(Band.FOUND);
    }

    @Test
    void unknownBanksAndValuesThatDoNotFitAreSkipped() {
        Detection.Entity oddBank = entity("?mail.example.test", "1111", Kind.CREDIT_CARD, Conf.HIGH);
        Detection.Entity blankBank = entity(" ", "1111", Kind.CREDIT_CARD, Conf.HIGH);
        Detection.Entity longBank = entity("b".repeat(41), "1111", Kind.CREDIT_CARD, Conf.HIGH);
        Detection.Entity longLast4 = entity("hdfc", "12345", Kind.CREDIT_CARD, Conf.HIGH);
        Detection.Entity fine = entity("hdfc", "2222", Kind.CREDIT_CARD, Conf.HIGH);
        List<SuggestionPlanner.Plan> plans = SuggestionPlanner.plan(detection(oddBank, blankBank, longBank, longLast4, fine), ExistingAccounts.NONE, TODAY);
        assertThat(plans).extracting(SuggestionPlanner.Plan::key).containsExactly("hdfc:CREDIT_CARD:2222");
    }

    @Test
    void variantTextIsCutToItsColumns() {
        Detection.Entity e = entity("hdfc", "2222", Kind.CREDIT_CARD, Conf.HIGH);
        e.variant = "v".repeat(400);
        for (int i = 0; i < 30; i++) e.variantCandidates.add(String.valueOf((char) ('a' + i % 26)).repeat(150) + i);
        SuggestionPlanner.Plan p = only(e, ExistingAccounts.NONE);
        assertThat(p.variant()).hasSize(150);
        assertThat(p.variantCandidatesJson()).isNotNull().hasSizeLessThanOrEqualTo(2000);
        assertThat(AccountSuggestionService.readCandidates(p.variantCandidatesJson())).isNotEmpty().allSatisfy(n -> assertThat(n.length()).isLessThanOrEqualTo(150));
    }

    @Test
    void lowExistsIsMaybeAndMediumIsFound() {
        assertThat(SuggestionPlanner.band(entity("hdfc", "1", Kind.ACCOUNT, Conf.LOW), TODAY)).isEqualTo(Band.MAYBE);
        assertThat(SuggestionPlanner.band(entity("hdfc", "1", Kind.ACCOUNT, Conf.MEDIUM), TODAY)).isEqualTo(Band.FOUND);
    }

    // ---------------------------------------------------------------- hide what exists

    @Test
    void bankAccountLast4AtTheSameBankMatches() {
        Acc a = bankAcc("hdfc", "1111", true);
        SuggestionPlanner.Plan p = only(entity("hdfc", "1111", Kind.ACCOUNT, Conf.HIGH), of(a));
        assertThat(p.match()).isEqualTo(new ExistingAccounts.Match(a.id(), null));
    }

    @Test
    void sameLast4AtAnotherBankDoesNotMatch() {
        assertThat(only(entity("hdfc", "1111", Kind.ACCOUNT, Conf.HIGH), of(bankAcc("icici", "1111", true))).match()).isNull();
    }

    @Test
    void anAccountWithUnknownBankNeverHidesASuggestion() {
        Acc a = bankAcc(null, "1111", true);
        assertThat(only(entity("hdfc", "1111", Kind.ACCOUNT, Conf.HIGH), of(a)).match()).isNull();
        assertThat(only(entity("hdfc", "1111", Kind.CREDIT_CARD, Conf.HIGH), of(cardAcc(null, "1111"))).match()).isNull();
    }

    @Test
    void cardKindsMatchOnlyCardsAndBankAccountsOnlyBankAccountNumbers() {
        // a debit card never hides a bank account number, nor a bank account number a card
        Acc bank = bankAcc("hdfc", "1111", true);
        assertThat(only(entity("hdfc", "1111", Kind.DEBIT_CARD, Conf.HIGH), of(bank)).match()).isNull();
        assertThat(only(entity("hdfc", "1111", Kind.CREDIT_CARD, Conf.HIGH), of(bank)).match()).isNull();
        Acc card = cardAcc("hdfc", "2222");
        assertThat(only(entity("hdfc", "2222", Kind.ACCOUNT, Conf.HIGH), of(card)).match()).isNull();
        // a debit card on a bank account (any account type) does hide a debit card
        Acc withDebit = new Acc(UUID.randomUUID(), "S", AccountType.bank_account, "hdfc", true, null, List.of(new CardRef(UUID.randomUUID(), "3333")), List.of());
        assertThat(only(entity("hdfc", "3333", Kind.DEBIT_CARD, Conf.HIGH), of(withDebit)).match().accountId()).isEqualTo(withDebit.id());
    }

    @Test
    void openClosedAndAddonCardsAllMatch() {
        // closed cards and add-on cards are plain card rows on the account: any of them hides the number
        Acc a = cardAcc("hdfc", "2222", "3333", "4444");
        for (String l : List.of("2222", "3333", "4444")) {
            SuggestionPlanner.Plan p = only(entity("hdfc", l, Kind.CREDIT_CARD, Conf.HIGH), of(a));
            assertThat(p.match().accountId()).isEqualTo(a.id());
            assertThat(p.match().cardId()).isEqualTo(a.cards().stream().filter(c -> c.last4().equals(l)).findFirst().orElseThrow().id());
        }
    }

    @Test
    void aliasesMatchByLast4OrAnAccountNumberEndingInIt() {
        Acc exact = new Acc(UUID.randomUUID(), "A", AccountType.bank_account, "hdfc", true, null, List.of(),
                List.of(new Alias("5555", AccountIdentifierKind.OTHER)));
        assertThat(only(entity("hdfc", "5555", Kind.ACCOUNT, Conf.HIGH), of(exact)).match().accountId()).isEqualTo(exact.id());
        Acc full = new Acc(UUID.randomUUID(), "B", AccountType.bank_account, "hdfc", true, null, List.of(),
                List.of(new Alias("50100012345555", AccountIdentifierKind.ACCOUNT_NUMBER)));
        assertThat(only(entity("hdfc", "5555", Kind.ACCOUNT, Conf.HIGH), of(full)).match().accountId()).isEqualTo(full.id());
        Acc customerId = new Acc(UUID.randomUUID(), "C", AccountType.bank_account, "hdfc", true, null, List.of(),
                List.of(new Alias("98765555", AccountIdentifierKind.CUSTOMER_ID)));
        assertThat(only(entity("hdfc", "5555", Kind.ACCOUNT, Conf.HIGH), of(customerId)).match()).isNull();
        // only an ACCOUNT_NUMBER alias matches by its ending; OTHER and CRN names match exactly only
        for (AccountIdentifierKind k : List.of(AccountIdentifierKind.OTHER, AccountIdentifierKind.CRN)) {
            Acc longer = new Acc(UUID.randomUUID(), "D", AccountType.bank_account, "hdfc", true, null, List.of(), List.of(new Alias("98765555", k)));
            assertThat(only(entity("hdfc", "5555", Kind.ACCOUNT, Conf.HIGH), of(longer)).match()).as(k.name()).isNull();
        }
    }

    // ---------------------------------------------------------------- debit cards

    @Test
    void debitCardWithOneOpenBankAccountAtTheBankGetsItAsParent() {
        Acc a = bankAcc("hdfc", "1111", true);
        SuggestionPlanner.Plan p = only(entity("hdfc", "7777", Kind.DEBIT_CARD, Conf.HIGH), of(a, bankAcc("hdfc", "1112", false)));
        assertThat(p.kind()).isEqualTo(AccountSuggestion.Kind.DEBIT_CARD);
        assertThat(p.parentAccountId()).isEqualTo(a.id());
        assertThat(p.debitNeedsBank()).isFalse();
    }

    @Test
    void debitCardWithSeveralOpenBankAccountsHasNoParent() {
        SuggestionPlanner.Plan p = only(entity("hdfc", "7777", Kind.DEBIT_CARD, Conf.HIGH), of(bankAcc("hdfc", "1111", true), bankAcc("hdfc", "1112", true)));
        assertThat(p.parentAccountId()).isNull();
        assertThat(p.debitNeedsBank()).isFalse();
    }

    @Test
    void debitCardWithNoOpenBankAccountAtTheBankLooksForTheBanksSuggestion() {
        SuggestionPlanner.Plan p = only(entity("hdfc", "7777", Kind.DEBIT_CARD, Conf.HIGH), of(bankAcc("icici", "1111", true), bankAcc("hdfc", "1112", false)));
        assertThat(p.parentAccountId()).isNull();
        assertThat(p.debitNeedsBank()).isTrue();
    }

    // ---------------------------------------------------------------- add-ons and replacements

    @Test
    void addonOfAnExistingCardHangsUnderThatAccount() {
        Acc primary = cardAcc("hdfc", "2222");
        Detection.Entity addon = entity("hdfc", "8888", Kind.CREDIT_CARD, Conf.MEDIUM);
        addon.lifecycle = Lifecycle.ADD_ON;
        addon.lifecycleConf = Conf.MEDIUM;
        addon.linkedTo = "hdfc:2222";
        SuggestionPlanner.Plan p = only(addon, of(primary));
        assertThat(p.kind()).isEqualTo(AccountSuggestion.Kind.ADDON_CARD);
        assertThat(p.parentAccountId()).isEqualTo(primary.id());
        assertThat(p.parentKey()).isNull();
    }

    @Test
    void addonOfASuggestedPrimaryPointsAtThePrimarysSuggestion() {
        Detection.Entity primary = entity("hdfc", "2222", Kind.CREDIT_CARD, Conf.HIGH);
        Detection.Entity addon = entity("hdfc", "8888", Kind.CREDIT_CARD, Conf.MEDIUM);
        addon.lifecycle = Lifecycle.ADD_ON;
        addon.lifecycleConf = Conf.MEDIUM;
        addon.linkedTo = "hdfc:2222";
        List<SuggestionPlanner.Plan> plans = SuggestionPlanner.plan(detection(primary, addon), ExistingAccounts.NONE, TODAY);
        SuggestionPlanner.Plan p = find(plans, "hdfc:CREDIT_CARD:8888");
        assertThat(p.kind()).isEqualTo(AccountSuggestion.Kind.ADDON_CARD);
        assertThat(p.parentKey()).isEqualTo("hdfc:CREDIT_CARD:2222");
        assertThat(find(plans, "hdfc:CREDIT_CARD:2222").kind()).isEqualTo(AccountSuggestion.Kind.CREDIT_CARD);
    }

    @Test
    void aLowLifecycleNeverLinksAnAddonOrReplacement() {
        Acc primary = cardAcc("hdfc", "2222");
        Detection.Entity addon = entity("hdfc", "8888", Kind.CREDIT_CARD, Conf.MEDIUM);
        addon.lifecycle = Lifecycle.ADD_ON;
        addon.lifecycleConf = Conf.LOW;
        addon.linkedTo = "hdfc:2222";
        SuggestionPlanner.Plan p = only(addon, of(primary));
        assertThat(p.kind()).isEqualTo(AccountSuggestion.Kind.CREDIT_CARD);
        assertThat(p.parentAccountId()).isNull();
        assertThat(p.parentKey()).isNull();

        Detection.Entity fresh = entity("hdfc", "9999", Kind.CREDIT_CARD, Conf.HIGH);
        fresh.lifecycle = Lifecycle.REPLACEMENT;
        fresh.lifecycleConf = Conf.LOW;
        fresh.linkedTo = "hdfc:2222";
        SuggestionPlanner.Plan r = only(fresh, of(primary));
        assertThat(r.kind()).isEqualTo(AccountSuggestion.Kind.CREDIT_CARD);
        assertThat(r.parentAccountId()).isNull();

        // a suggested primary: still no link at LOW
        Detection.Entity top = entity("hdfc", "2222", Kind.CREDIT_CARD, Conf.HIGH);
        List<SuggestionPlanner.Plan> plans = SuggestionPlanner.plan(detection(top, addon), ExistingAccounts.NONE, TODAY);
        assertThat(find(plans, "hdfc:CREDIT_CARD:8888").parentKey()).isNull();
    }

    @Test
    void addonWhosePrimaryIsUnknownStaysACreditCard() {
        Detection.Entity addon = entity("hdfc", "8888", Kind.CREDIT_CARD, Conf.MEDIUM);
        addon.lifecycle = Lifecycle.ADD_ON;
        addon.lifecycleConf = Conf.MEDIUM;
        addon.linkedTo = "hdfc:2222";
        SuggestionPlanner.Plan p = only(addon, ExistingAccounts.NONE);
        assertThat(p.kind()).isEqualTo(AccountSuggestion.Kind.CREDIT_CARD);
        assertThat(p.parentKey()).isNull();
    }

    @Test
    void replacementOfAnExistingCardHangsUnderItsAccount() {
        Acc old = cardAcc("hdfc", "2222");
        Detection.Entity fresh = entity("hdfc", "9999", Kind.CREDIT_CARD, Conf.HIGH);
        fresh.lifecycle = Lifecycle.REPLACEMENT;
        fresh.lifecycleConf = Conf.MEDIUM;
        fresh.linkedTo = "hdfc:2222";
        SuggestionPlanner.Plan p = only(fresh, of(old));
        assertThat(p.kind()).isEqualTo(AccountSuggestion.Kind.REPLACEMENT_CARD);
        assertThat(p.parentAccountId()).isEqualTo(old.id());

        // the old number not on any card: a plain credit card
        assertThat(only(fresh, of(bankAcc("hdfc", "2222", true))).kind()).isEqualTo(AccountSuggestion.Kind.CREDIT_CARD);
    }

    // ---------------------------------------------------------------- "Is this your…"

    @Test
    void candidatesAreOpenSameKindSameBankAccountsWithoutANumber() {
        Acc noLast4 = bankAcc("hdfc", null, true);
        Acc closedNoLast4 = bankAcc("hdfc", null, false);
        Acc otherBank = bankAcc("icici", null, true);
        assertThat(only(entity("hdfc", "1111", Kind.ACCOUNT, Conf.HIGH), of(noLast4, closedNoLast4, otherBank, bankAcc("hdfc", "4321", true))).candidates())
                .containsExactly(noLast4.id());

        Acc noCard = cardAcc("hdfc");
        assertThat(only(entity("hdfc", "2222", Kind.CREDIT_CARD, Conf.HIGH), of(noCard, cardAcc("hdfc", "3333"))).candidates())
                .containsExactly(noCard.id());
        assertThat(only(entity("hdfc", "2222", Kind.DEBIT_CARD, Conf.HIGH), of(noCard)).candidates()).isEmpty();
    }

    @Test
    void anAccountWithNoBankWhoseNumberEqualsIsACandidateButOtherNumbersAreNot() {
        Acc noBankSame = bankAcc(null, "1111", true);
        Acc noBankOther = bankAcc(null, "9999", true);
        Acc noBankNoNumber = bankAcc(null, null, true);
        SuggestionPlanner.Plan p = only(entity("hdfc", "1111", Kind.ACCOUNT, Conf.HIGH), of(noBankSame, noBankOther, noBankNoNumber));
        assertThat(p.match()).isNull();
        assertThat(p.candidates()).containsExactly(noBankSame.id());

        Acc noBankCard = new Acc(UUID.randomUUID(), "C", AccountType.credit_card, null, true, null, List.of(new CardRef(UUID.randomUUID(), "2222")), List.of());
        assertThat(only(entity("hdfc", "2222", Kind.CREDIT_CARD, Conf.HIGH), of(noBankCard)).candidates()).containsExactly(noBankCard.id());
    }

    @Test
    void severalCandidatesAreAllReportedAndOnlyBankAndCreditCardKindsAsk() {
        Acc one = bankAcc("hdfc", null, true);
        Acc two = bankAcc("hdfc", null, true);
        assertThat(only(entity("hdfc", "1111", Kind.ACCOUNT, Conf.HIGH), of(one, two)).candidates()).containsExactlyInAnyOrder(one.id(), two.id());
        assertThat(only(entity("hdfc", "1111", Kind.DEBIT_CARD, Conf.HIGH), of(one, two)).candidates()).isEmpty();
    }

    // ---------------------------------------------------------------- older number of an existing card

    private static Detection.Entity lifecycle(Detection.Entity e, Lifecycle l, Conf conf, String linkedTo) {
        e.lifecycle = l;
        e.lifecycleConf = conf;
        e.linkedTo = linkedTo;
        return e;
    }

    @Test
    void anExistingCardsReplacementLinkingThisNumberMakesItAnOlderNumberOfThatAccount() {
        Acc card = cardAcc("hdfc", "2222");
        Detection.Entity newer = lifecycle(entity("hdfc", "2222", Kind.CREDIT_CARD, Conf.HIGH), Lifecycle.REPLACEMENT, Conf.MEDIUM, "hdfc:1111");
        Detection.Entity old = entity("hdfc", "1111", Kind.CREDIT_CARD, Conf.HIGH);
        List<SuggestionPlanner.Plan> plans = SuggestionPlanner.plan(detection(newer, old), of(card), TODAY);

        SuggestionPlanner.Plan p = find(plans, "hdfc:CREDIT_CARD:1111");
        assertThat(p.parentAccountId()).isEqualTo(card.id());
        assertThat(p.band()).isEqualTo(Band.OLDER);
        assertThat(p.kind()).isEqualTo(AccountSuggestion.Kind.CREDIT_CARD);
    }

    @Test
    void thisNumberReplacedOrUpgradedIntoAnExistingCardIsAnOlderNumberToo() {
        Acc card = cardAcc("hdfc", "2222");
        for (Lifecycle l : new Lifecycle[]{Lifecycle.REPLACED, Lifecycle.UPGRADED}) {
            Detection.Entity old = lifecycle(entity("hdfc", "1111", Kind.CREDIT_CARD, Conf.HIGH), l, Conf.HIGH, "hdfc:2222");
            SuggestionPlanner.Plan p = only(old, of(card));
            assertThat(p.parentAccountId()).as(l.name()).isEqualTo(card.id());
            assertThat(p.band()).as(l.name()).isEqualTo(Band.OLDER);
        }
    }

    @Test
    void aLowLifecycleNeverLinks() {
        Acc card = cardAcc("hdfc", "2222");
        Detection.Entity newer = lifecycle(entity("hdfc", "2222", Kind.CREDIT_CARD, Conf.HIGH), Lifecycle.REPLACEMENT, Conf.LOW, "hdfc:1111");
        Detection.Entity old = entity("hdfc", "1111", Kind.CREDIT_CARD, Conf.HIGH);
        SuggestionPlanner.Plan p = find(SuggestionPlanner.plan(detection(newer, old), of(card), TODAY), "hdfc:CREDIT_CARD:1111");
        assertThat(p.parentAccountId()).isNull();
        assertThat(p.band()).isEqualTo(Band.FOUND);

        Detection.Entity replaced = lifecycle(entity("hdfc", "1111", Kind.CREDIT_CARD, Conf.HIGH), Lifecycle.REPLACED, Conf.LOW, "hdfc:2222");
        assertThat(only(replaced, of(card)).parentAccountId()).isNull();
    }

    @Test
    void noLinkWhenTheSuccessorIsNotAnExistingCardOrAtAnotherBankOrNotACreditCardAccount() {
        Detection.Entity old = lifecycle(entity("hdfc", "1111", Kind.CREDIT_CARD, Conf.HIGH), Lifecycle.REPLACED, Conf.HIGH, "hdfc:2222");
        assertThat(only(old, ExistingAccounts.NONE).parentAccountId()).isNull();
        assertThat(only(old, of(cardAcc("icici", "2222"))).parentAccountId()).isNull();
        Acc debit = new Acc(UUID.randomUUID(), "Savings", AccountType.bank_account, "hdfc", true, "9999",
                List.of(new CardRef(UUID.randomUUID(), "2222")), List.of());
        assertThat(only(old, of(debit)).parentAccountId()).isNull();
    }

    @Test
    void aMaybeRowStaysMaybeEvenWhenLinked() {
        Acc card = cardAcc("hdfc", "2222");
        Detection.Entity old = lifecycle(entity("hdfc", "1111", Kind.CREDIT_CARD, Conf.LOW), Lifecycle.REPLACED, Conf.HIGH, "hdfc:2222");
        SuggestionPlanner.Plan p = only(old, of(card));
        assertThat(p.band()).isEqualTo(Band.MAYBE);
    }

    @Test
    void aDeclinedLinkIsNeverMadeAgain() {
        Acc card = cardAcc("hdfc", "2222");
        Detection.Entity old = lifecycle(entity("hdfc", "1111", Kind.CREDIT_CARD, Conf.HIGH), Lifecycle.REPLACED, Conf.HIGH, "hdfc:2222");
        SuggestionPlanner.Plan p = SuggestionPlanner.plan(detection(old), of(card), TODAY, java.util.Set.of("hdfc:CREDIT_CARD:1111")).get(0);
        assertThat(p.parentAccountId()).isNull();
        assertThat(p.band()).isEqualTo(Band.OLDER);   // REPLACED is OLDER on its own
    }

    @Test
    void anAddonKeepsItsKindAndParentInsteadOfTheOlderLink() {
        Acc card = cardAcc("hdfc", "2222");
        Detection.Entity addon = lifecycle(entity("hdfc", "8888", Kind.CREDIT_CARD, Conf.HIGH), Lifecycle.ADD_ON, Conf.HIGH, "hdfc:2222");
        SuggestionPlanner.Plan p = only(addon, of(card));
        assertThat(p.kind()).isEqualTo(AccountSuggestion.Kind.ADDON_CARD);
        assertThat(p.parentAccountId()).isEqualTo(card.id());
        assertThat(p.band()).isEqualTo(Band.FOUND);
    }
}
