package com.financeos.gmail.accountdetect.suggest;

import com.financeos.domain.account.AccountType;
import com.financeos.gmail.accountdetect.Vocab.Conf;
import com.financeos.gmail.accountdetect.Vocab.Kind;
import com.financeos.gmail.accountdetect.suggest.AccountSuggestion.Band;
import com.financeos.gmail.accountdetect.suggest.ExistingAccounts.Acc;
import com.financeos.gmail.accountdetect.suggest.ExistingAccounts.CardRef;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static com.financeos.gmail.accountdetect.DetectionFixtures.detection;
import static com.financeos.gmail.accountdetect.DetectionFixtures.entity;
import static org.assertj.core.api.Assertions.assertThat;

/** A weak (MAYBE) guess at one bank is not suggested for a number the user already has at another bank. */
class CrossBankPlannerTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 11, 3);

    private static Acc equitas(boolean open) {
        return new Acc(UUID.randomUUID(), "Savings", AccountType.bank_account, "equitas", open, "2183", List.of(), List.of());
    }

    @Test
    void aMaybePlanIsSuppressedWhenAnOpenAccountAtAnotherBankHasTheNumber() {
        var plans = SuggestionPlanner.plan(detection(entity("hdfc", "2183", Kind.ACCOUNT, Conf.LOW)),
                new ExistingAccounts(List.of(equitas(true))), TODAY);
        assertThat(plans).isEmpty();
    }

    @Test
    void anOpenCardAtAnotherBankCountsToo() {
        Acc card = new Acc(UUID.randomUUID(), "Card", AccountType.credit_card, "csb", true, null, List.of(new CardRef(UUID.randomUUID(), "2183")), List.of());
        var plans = SuggestionPlanner.plan(detection(entity("hdfc", "2183", Kind.ACCOUNT, Conf.LOW)), new ExistingAccounts(List.of(card)), TODAY);
        assertThat(plans).isEmpty();
    }

    @Test
    void aFoundPlanIsNotSuppressed() {
        var plans = SuggestionPlanner.plan(detection(entity("hdfc", "2183", Kind.ACCOUNT, Conf.HIGH)),
                new ExistingAccounts(List.of(equitas(true))), TODAY);
        assertThat(plans).hasSize(1);
        assertThat(plans.get(0).band()).isEqualTo(Band.FOUND);
    }

    @Test
    void aMaybePlanStaysWhenTheOtherBankAccountIsClosedOrTheLast4Differs() {
        assertThat(SuggestionPlanner.plan(detection(entity("hdfc", "2183", Kind.ACCOUNT, Conf.LOW)),
                new ExistingAccounts(List.of(equitas(false))), TODAY)).hasSize(1);
        assertThat(SuggestionPlanner.plan(detection(entity("hdfc", "9999", Kind.ACCOUNT, Conf.LOW)),
                new ExistingAccounts(List.of(equitas(true))), TODAY)).hasSize(1);
    }
}
