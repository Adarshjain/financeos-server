package com.financeos.domain.transaction.autolink;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.financeos.api.transactionlink.dto.CreateTransactionLinkRequest;
import com.financeos.domain.account.AccountType;
import com.financeos.domain.transaction.TransactionType;
import com.financeos.domain.transaction.autolink.TransferLinker.Plan;
import com.financeos.domain.transaction.link.LinkOrigin;
import com.financeos.domain.transaction.link.LinkType;
import com.financeos.domain.transaction.link.TransactionLinkService;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.SimpleTransactionStatus;

class TransferLinkerTest {

    private static final LocalDate D = LocalDate.of(2026, 10, 10);
    private static final UUID BANK_A = UUID.randomUUID();
    private static final UUID BANK_B = UUID.randomUUID();
    private static final UUID BANK_C = UUID.randomUUID();
    private static final UUID CARD = UUID.randomUUID();

    private static PlatformTransactionManager tm() {
        PlatformTransactionManager tm = mock(PlatformTransactionManager.class);
        when(tm.getTransaction(any(TransactionDefinition.class))).thenReturn(new SimpleTransactionStatus());
        return tm;
    }

    private static AutoLinkCandidate tx(UUID account, AccountType accountType, LocalDate date, String amount, TransactionType type) {
        return new AutoLinkCandidate(UUID.randomUUID(), account, accountType, date, new BigDecimal(amount), type, "x");
    }

    private static AutoLinkCandidate debit(UUID account, LocalDate date, String amount) {
        return tx(account, AccountType.bank_account, date, amount, TransactionType.DEBIT);
    }

    private static AutoLinkCandidate credit(UUID account, LocalDate date, String amount) {
        return tx(account, AccountType.bank_account, date, amount, TransactionType.CREDIT);
    }

    @Test
    void aUniquePairIsLinkedAsATransfer() {
        AutoLinkCandidate out = debit(BANK_A, D, "5000.00");
        AutoLinkCandidate in = credit(BANK_B, D.plusDays(1), "5000");

        Plan plan = TransferLinker.plan(List.of(out, in), Set.of());

        assertThat(plan.autoLinks()).hasSize(1);
        assertThat(plan.autoLinks().get(0).debit()).isEqualTo(out);
        assertThat(plan.autoLinks().get(0).credit()).isEqualTo(in);
        assertThat(plan.suggestions()).isEmpty();
    }

    @Test
    void twoCandidateCreditsGiveASuggestionNearestFirstAndNoLink() {
        AutoLinkCandidate out = debit(BANK_A, D, "700");
        AutoLinkCandidate later = credit(BANK_B, D.plusDays(2), "700");
        AutoLinkCandidate sameDay = credit(BANK_C, D, "700");

        Plan plan = TransferLinker.plan(List.of(out, later, sameDay), Set.of());

        assertThat(plan.autoLinks()).isEmpty();
        assertThat(plan.suggestions()).hasSize(1);
        assertThat(plan.suggestions().get(0).kind()).isEqualTo(SuggestionKind.TRANSFER);
        assertThat(plan.suggestions().get(0).transactionId()).isEqualTo(out.id());
        assertThat(plan.suggestions().get(0).candidateIds()).containsExactly(sameDay.id(), later.id());
    }

    @Test
    void twoDebitsForOneCreditAreBothSuggestionsNotLinks() {
        AutoLinkCandidate out1 = debit(BANK_A, D, "300");
        AutoLinkCandidate out2 = debit(BANK_C, D, "300");
        AutoLinkCandidate in = credit(BANK_B, D, "300");

        Plan plan = TransferLinker.plan(List.of(out1, out2, in), Set.of());

        assertThat(plan.autoLinks()).isEmpty();
        assertThat(plan.suggestions()).extracting(TransferLinker.Suggestion::transactionId)
                .containsExactlyInAnyOrder(out1.id(), out2.id());
    }

    @Test
    void aRejectedPairIsSkippedInEitherDirection() {
        AutoLinkCandidate out = debit(BANK_A, D, "5000");
        AutoLinkCandidate in = credit(BANK_B, D, "5000");

        assertThat(TransferLinker.plan(List.of(out, in), Set.of(AutoLinkStore.pairKey(SuggestionKind.TRANSFER, out.id(), in.id())))
                .autoLinks()).isEmpty();
        Plan reversed = TransferLinker.plan(List.of(out, in), Set.of(AutoLinkStore.pairKey(SuggestionKind.TRANSFER, in.id(), out.id())));
        assertThat(reversed.autoLinks()).isEmpty();
        assertThat(reversed.suggestions()).isEmpty();
    }

    @Test
    void aRejectedCandidateLeavesTheOtherOneAsTheUniquePair() {
        AutoLinkCandidate out = debit(BANK_A, D, "500");
        AutoLinkCandidate rejected = credit(BANK_B, D, "500");
        AutoLinkCandidate other = credit(BANK_C, D.plusDays(1), "500");

        Plan plan = TransferLinker.plan(List.of(out, rejected, other),
                Set.of(AutoLinkStore.pairKey(SuggestionKind.TRANSFER, out.id(), rejected.id())));

        assertThat(plan.autoLinks()).hasSize(1);
        assertThat(plan.autoLinks().get(0).credit()).isEqualTo(other);
    }

    @Test
    void aCreditOnACardIsACardPayment() {
        AutoLinkCandidate out = debit(BANK_A, D, "12000");
        AutoLinkCandidate payment = tx(CARD, AccountType.credit_card, D.plusDays(1), "12000", TransactionType.CREDIT);

        Plan plan = TransferLinker.plan(List.of(out, payment), Set.of());

        assertThat(plan.autoLinks()).hasSize(1);
        assertThat(TransferLinker.kindFor(plan.autoLinks().get(0).credit())).isEqualTo(SuggestionKind.CC_PAYMENT);
    }

    @Test
    void anAmbiguousCardPaymentIsACardPaymentSuggestion() {
        AutoLinkCandidate out = debit(BANK_A, D, "800");
        AutoLinkCandidate card1 = tx(CARD, AccountType.credit_card, D, "800", TransactionType.CREDIT);
        AutoLinkCandidate card2 = tx(UUID.randomUUID(), AccountType.credit_card, D.plusDays(1), "800", TransactionType.CREDIT);

        Plan plan = TransferLinker.plan(List.of(out, card1, card2), Set.of());

        assertThat(plan.suggestions()).hasSize(1);
        assertThat(plan.suggestions().get(0).kind()).isEqualTo(SuggestionKind.CC_PAYMENT);
    }

    @Test
    void aSameAccountPairIsIgnored() {
        Plan plan = TransferLinker.plan(List.of(debit(BANK_A, D, "100"), credit(BANK_A, D, "100")), Set.of());

        assertThat(plan.autoLinks()).isEmpty();
        assertThat(plan.suggestions()).isEmpty();
    }

    @Test
    void aCardPurchaseIsNeverATransferAnchor() {
        AutoLinkCandidate purchase = tx(CARD, AccountType.credit_card, D, "250", TransactionType.DEBIT);

        Plan plan = TransferLinker.plan(List.of(purchase, credit(BANK_B, D, "250")), Set.of());

        assertThat(plan.autoLinks()).isEmpty();
        assertThat(plan.suggestions()).isEmpty();
    }

    @Test
    void differentAmountsDoNotPair() {
        Plan plan = TransferLinker.plan(List.of(debit(BANK_A, D, "100.00"), credit(BANK_B, D, "100.01")), Set.of());

        assertThat(plan.autoLinks()).isEmpty();
        assertThat(plan.suggestions()).isEmpty();
    }

    @Test
    void theDateWindowIsOneDayBeforeToTwoDaysAfter() {
        AutoLinkCandidate out = debit(BANK_A, D, "10");

        assertThat(TransferLinker.plan(List.of(out, credit(BANK_B, D.minusDays(1), "10")), Set.of()).autoLinks()).hasSize(1);
        assertThat(TransferLinker.plan(List.of(out, credit(BANK_B, D.plusDays(2), "10")), Set.of()).autoLinks()).hasSize(1);
        assertThat(TransferLinker.plan(List.of(out, credit(BANK_B, D.minusDays(2), "10")), Set.of()).autoLinks()).isEmpty();
        assertThat(TransferLinker.plan(List.of(out, credit(BANK_B, D.plusDays(3), "10")), Set.of()).autoLinks()).isEmpty();
    }

    @Test
    void linkCreatesAutoLinksAndStoresTheRestAsSuggestions() {
        AutoLinkCandidateLoader loader = mock(AutoLinkCandidateLoader.class);
        AutoLinkStore store = mock(AutoLinkStore.class);
        TransactionLinkService linkService = mock(TransactionLinkService.class);
        UUID userId = UUID.randomUUID();
        AutoLinkCandidate out = debit(BANK_A, D, "5000");
        AutoLinkCandidate in = credit(BANK_B, D, "5000");
        AutoLinkCandidate amb = debit(BANK_A, D, "70");
        AutoLinkCandidate amb1 = credit(BANK_B, D, "70");
        AutoLinkCandidate amb2 = credit(BANK_C, D.plusDays(1), "70");
        when(loader.load(userId)).thenReturn(List.of(out, in, amb, amb1, amb2));
        when(store.loadRejectionKeys(userId)).thenReturn(Set.of());
        when(loader.freeIds(eq(userId), any())).thenReturn(Set.of(out.id(), in.id()));

        AutoLinkOutcome outcome = new TransferLinker(loader, store, linkService, tm()).link(userId);

        ArgumentCaptor<CreateTransactionLinkRequest> request = ArgumentCaptor.forClass(CreateTransactionLinkRequest.class);
        verify(linkService).createLink(request.capture(), eq(LinkOrigin.AUTO));
        assertThat(request.getValue().type()).isEqualTo(LinkType.TRANSFER);
        assertThat(request.getValue().members()).hasSize(2);
        assertThat(request.getValue().members().get(0).transactionId()).isEqualTo(out.id());
        assertThat(request.getValue().members().get(0).isAnchor()).isTrue();
        assertThat(request.getValue().members().get(1).transactionId()).isEqualTo(in.id());
        verify(store).syncSuggestions(eq(userId), eq(Set.of(SuggestionKind.TRANSFER, SuggestionKind.CC_PAYMENT)),
                eq(Map.of(SuggestionKind.TRANSFER, Map.of(amb.id(), List.of(amb1.id(), amb2.id())))));
        assertThat(outcome.linked()).isEqualTo(1);
        assertThat(outcome.suggested()).isEqualTo(1);
    }

    @Test
    void linkWithNothingToPairStillClearsStaleSuggestions() {
        AutoLinkCandidateLoader loader = mock(AutoLinkCandidateLoader.class);
        AutoLinkStore store = mock(AutoLinkStore.class);
        TransactionLinkService linkService = mock(TransactionLinkService.class);
        UUID userId = UUID.randomUUID();
        when(loader.load(userId)).thenReturn(List.of());
        when(store.loadRejectionKeys(userId)).thenReturn(Set.of());

        AutoLinkOutcome outcome = new TransferLinker(loader, store, linkService, tm()).link(userId);

        verify(linkService, never()).createLink(any(), any());
        verify(store).syncSuggestions(eq(userId), eq(Set.of(SuggestionKind.TRANSFER, SuggestionKind.CC_PAYMENT)), eq(Map.of()));
        assertThat(outcome.linked()).isZero();
    }

    @Test
    void creditsDescribedAsRefundsReversalsCashbackOrRewardsAreNeverCandidates() {
        for (String text : List.of("UPI REFUND SWIGGY", "Reversal of txn", "REV-POS AMAZON", "HDFC CASHBACK", "CASH BACK CREDIT",
                "Reward points redeemed", "Chargeback credit")) {
            AutoLinkCandidate refundish = new AutoLinkCandidate(UUID.randomUUID(), BANK_B, AccountType.bank_account, D,
                    new BigDecimal("250"), TransactionType.CREDIT, text);
            Plan plan = TransferLinker.plan(List.of(debit(BANK_A, D, "250"), refundish), Set.of());
            assertThat(plan.autoLinks()).as(text).isEmpty();
            assertThat(plan.suggestions()).as(text).isEmpty();
        }
        AutoLinkCandidate plain = new AutoLinkCandidate(UUID.randomUUID(), BANK_B, AccountType.bank_account, D,
                new BigDecimal("250"), TransactionType.CREDIT, "Reverend Smith");
        assertThat(TransferLinker.plan(List.of(debit(BANK_A, D, "250"), plain), Set.of()).autoLinks()).hasSize(1);
    }

    @Test
    void mixedCardAndBankCandidatesAreLabelledTransfer() {
        AutoLinkCandidate out = debit(BANK_A, D, "900");
        AutoLinkCandidate card = tx(CARD, AccountType.credit_card, D, "900", TransactionType.CREDIT);
        AutoLinkCandidate bank = credit(BANK_B, D.plusDays(1), "900");

        Plan plan = TransferLinker.plan(List.of(out, card, bank), Set.of());

        assertThat(plan.suggestions()).hasSize(1);
        assertThat(plan.suggestions().get(0).kind()).isEqualTo(SuggestionKind.TRANSFER);
        assertThat(plan.suggestions().get(0).candidateIds()).containsExactly(card.id(), bank.id());
    }

    @Test
    void aFailingPairIsSkippedTheOthersLinkAndSuggestionsStillSync() {
        AutoLinkCandidateLoader loader = mock(AutoLinkCandidateLoader.class);
        AutoLinkStore store = mock(AutoLinkStore.class);
        TransactionLinkService linkService = mock(TransactionLinkService.class);
        UUID userId = UUID.randomUUID();
        AutoLinkCandidate out1 = debit(BANK_A, D, "111");
        AutoLinkCandidate in1 = credit(BANK_B, D, "111");
        AutoLinkCandidate out2 = debit(BANK_A, D, "222");
        AutoLinkCandidate in2 = credit(BANK_B, D, "222");
        when(loader.load(userId)).thenReturn(List.of(out1, in1, out2, in2));
        when(store.loadRejectionKeys(userId)).thenReturn(Set.of());
        when(loader.freeIds(eq(userId), any())).thenReturn(Set.of(out1.id(), in1.id(), out2.id(), in2.id()));
        when(linkService.createLink(any(), any())).thenThrow(new IllegalStateException("boom")).thenReturn(null);

        AutoLinkOutcome outcome = new TransferLinker(loader, store, linkService, tm()).link(userId);

        verify(linkService, org.mockito.Mockito.times(2)).createLink(any(), any());
        verify(store).syncSuggestions(eq(userId), any(), any());
        assertThat(outcome.linked()).isEqualTo(1);
        assertThat(outcome.partial()).isTrue();
    }

    @Test
    void aPairThatIsNoLongerFreeIsNotLinked() {
        AutoLinkCandidateLoader loader = mock(AutoLinkCandidateLoader.class);
        AutoLinkStore store = mock(AutoLinkStore.class);
        TransactionLinkService linkService = mock(TransactionLinkService.class);
        UUID userId = UUID.randomUUID();
        AutoLinkCandidate out = debit(BANK_A, D, "5");
        AutoLinkCandidate in = credit(BANK_B, D, "5");
        when(loader.load(userId)).thenReturn(List.of(out, in));
        when(store.loadRejectionKeys(userId)).thenReturn(Set.of());
        when(loader.freeIds(eq(userId), any())).thenReturn(Set.of(out.id()));

        AutoLinkOutcome outcome = new TransferLinker(loader, store, linkService, tm()).link(userId);

        verify(linkService, never()).createLink(any(), any());
        assertThat(outcome.linked()).isZero();
        assertThat(outcome.partial()).isFalse();
    }
}
