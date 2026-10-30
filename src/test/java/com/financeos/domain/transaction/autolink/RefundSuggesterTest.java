package com.financeos.domain.transaction.autolink;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.financeos.domain.account.AccountType;
import com.financeos.domain.transaction.TransactionType;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class RefundSuggesterTest {

    private static final LocalDate D = LocalDate.of(2026, 10, 30);
    private static final UUID ACCOUNT = UUID.randomUUID();
    private static final UUID OTHER_ACCOUNT = UUID.randomUUID();

    private static AutoLinkCandidate tx(UUID account, LocalDate date, String amount, TransactionType type, String text) {
        return new AutoLinkCandidate(UUID.randomUUID(), account, AccountType.credit_card, date, new BigDecimal(amount), type, text);
    }

    @Test
    void aCreditAfterASameMerchantDebitOnTheSameAccountIsSuggested() {
        AutoLinkCandidate purchase = tx(ACCOUNT, D.minusDays(10), "1999", TransactionType.DEBIT, "POS AMAZON PAY 123456");
        AutoLinkCandidate refund = tx(ACCOUNT, D, "1999", TransactionType.CREDIT, "AMAZON PAY 98765");

        Map<UUID, List<UUID>> plan = RefundSuggester.plan(List.of(purchase, refund), Set.of());

        assertThat(plan).containsOnlyKeys(refund.id());
        assertThat(plan.get(refund.id())).containsExactly(purchase.id());
    }

    @Test
    void exactAmountComesFirstThenNearestDate() {
        AutoLinkCandidate partialNear = tx(ACCOUNT, D.minusDays(1), "900", TransactionType.DEBIT, "Swiggy");
        AutoLinkCandidate exactFar = tx(ACCOUNT, D.minusDays(30), "500", TransactionType.DEBIT, "Swiggy");
        AutoLinkCandidate partialFar = tx(ACCOUNT, D.minusDays(20), "700", TransactionType.DEBIT, "Swiggy");
        AutoLinkCandidate refund = tx(ACCOUNT, D, "500", TransactionType.CREDIT, "Swiggy");

        Map<UUID, List<UUID>> plan = RefundSuggester.plan(List.of(partialNear, exactFar, partialFar, refund), Set.of());

        assertThat(plan.get(refund.id())).containsExactly(exactFar.id(), partialNear.id(), partialFar.id());
    }

    @Test
    void theWindowIsZeroToSixtyDays() {
        AutoLinkCandidate sameDay = tx(ACCOUNT, D, "100", TransactionType.DEBIT, "Zomato");
        AutoLinkCandidate sixty = tx(ACCOUNT, D.minusDays(60), "100", TransactionType.DEBIT, "Zomato");
        AutoLinkCandidate sixtyOne = tx(ACCOUNT, D.minusDays(61), "100", TransactionType.DEBIT, "Zomato");
        AutoLinkCandidate later = tx(ACCOUNT, D.plusDays(1), "100", TransactionType.DEBIT, "Zomato");
        AutoLinkCandidate refund = tx(ACCOUNT, D, "100", TransactionType.CREDIT, "Zomato");

        Map<UUID, List<UUID>> plan = RefundSuggester.plan(List.of(sameDay, sixty, sixtyOne, later, refund), Set.of());

        assertThat(plan.get(refund.id())).containsExactlyInAnyOrder(sameDay.id(), sixty.id());
    }

    @Test
    void aCreditLargerThanTheDebitIsNotARefund() {
        Map<UUID, List<UUID>> plan = RefundSuggester.plan(List.of(
                tx(ACCOUNT, D.minusDays(5), "100", TransactionType.DEBIT, "Myntra"),
                tx(ACCOUNT, D, "100.01", TransactionType.CREDIT, "Myntra")), Set.of());

        assertThat(plan).isEmpty();
    }

    @Test
    void aDifferentAccountOrMerchantDoesNotMatch() {
        Map<UUID, List<UUID>> plan = RefundSuggester.plan(List.of(
                tx(ACCOUNT, D.minusDays(5), "100", TransactionType.DEBIT, "Myntra"),
                tx(OTHER_ACCOUNT, D, "100", TransactionType.CREDIT, "Myntra"),
                tx(ACCOUNT, D, "100", TransactionType.CREDIT, "Ajio")), Set.of());

        assertThat(plan).isEmpty();
    }

    @Test
    void aBlankMerchantNeverMatches() {
        Map<UUID, List<UUID>> plan = RefundSuggester.plan(List.of(
                tx(ACCOUNT, D.minusDays(5), "100", TransactionType.DEBIT, null),
                tx(ACCOUNT, D, "100", TransactionType.CREDIT, "   ")), Set.of());

        assertThat(plan).isEmpty();
    }

    @Test
    void aRejectedPairIsSkipped() {
        AutoLinkCandidate purchase = tx(ACCOUNT, D.minusDays(5), "100", TransactionType.DEBIT, "Myntra");
        AutoLinkCandidate refund = tx(ACCOUNT, D, "100", TransactionType.CREDIT, "Myntra");

        Map<UUID, List<UUID>> plan = RefundSuggester.plan(List.of(purchase, refund),
                Set.of(AutoLinkStore.pairKey(SuggestionKind.REFUND, refund.id(), purchase.id())));

        assertThat(plan).isEmpty();
    }

    @Test
    void linkStoresRefundSuggestionsAndNeverLinks() {
        AutoLinkCandidateLoader loader = mock(AutoLinkCandidateLoader.class);
        AutoLinkStore store = mock(AutoLinkStore.class);
        UUID userId = UUID.randomUUID();
        AutoLinkCandidate purchase = tx(ACCOUNT, D.minusDays(5), "100", TransactionType.DEBIT, "Myntra");
        AutoLinkCandidate refund = tx(ACCOUNT, D, "100", TransactionType.CREDIT, "Myntra");
        when(loader.load(userId)).thenReturn(List.of(purchase, refund));
        when(store.loadRejectionKeys(userId)).thenReturn(Set.of());

        AutoLinkOutcome outcome = new RefundSuggester(loader, store).link(userId);

        verify(store).syncSuggestions(eq(userId), eq(Set.of(SuggestionKind.REFUND)),
                eq(Map.of(SuggestionKind.REFUND, Map.of(refund.id(), List.of(purchase.id())))));
        assertThat(outcome.linked()).isZero();
        assertThat(outcome.suggested()).isEqualTo(1);
    }

    @Test
    void aRealisticBankRefundStringMatchesThePurchaseMerchant() {
        AutoLinkCandidate purchase = tx(ACCOUNT, D.minusDays(12), "649", TransactionType.DEBIT, "UPI/SWIGGY/4821937261/Payment");
        AutoLinkCandidate refund = tx(ACCOUNT, D, "649", TransactionType.CREDIT, "UPI REFUND SWIGGY 4821937999 RFND");
        AutoLinkCandidate rev = tx(ACCOUNT, D, "100", TransactionType.CREDIT, "REV-POS AMAZON PAY INDIA");
        AutoLinkCandidate amazon = tx(ACCOUNT, D.minusDays(3), "100", TransactionType.DEBIT, "POS AMAZON PAY INDIA");
        AutoLinkCandidate creditFor = tx(ACCOUNT, D, "50", TransactionType.CREDIT, "CREDIT FOR ZOMATO ORDER");
        AutoLinkCandidate zomato = tx(ACCOUNT, D.minusDays(2), "50", TransactionType.DEBIT, "ZOMATO ORDER");

        Map<UUID, List<UUID>> plan = RefundSuggester.plan(List.of(purchase, refund, rev, amazon, creditFor, zomato), Set.of());

        assertThat(plan.get(refund.id())).containsExactly(purchase.id());
        assertThat(plan.get(rev.id())).containsExactly(amazon.id());
        assertThat(plan.get(creditFor.id())).containsExactly(zomato.id());
        assertThat(RefundSuggester.merchantKey("REFUND")).isEmpty();
    }
}
