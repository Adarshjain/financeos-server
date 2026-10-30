package com.financeos.domain.transaction.autolink;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.financeos.domain.account.AccountType;
import com.financeos.domain.loan.LoanService;
import com.financeos.domain.loan.LoanService.UnsettledInstallment;
import com.financeos.domain.transaction.TransactionType;
import com.financeos.domain.transaction.autolink.EmiAutoLinker.Pair;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.SimpleTransactionStatus;

class EmiAutoLinkerTest {

    private static final LocalDate DUE = LocalDate.of(2026, 3, 5);
    private static final UUID BANK = UUID.randomUUID();
    private static final UUID OTHER_BANK = UUID.randomUUID();
    private static final UUID LOAN_A = UUID.randomUUID();
    private static final UUID LOAN_B = UUID.randomUUID();

    private static PlatformTransactionManager tm() {
        PlatformTransactionManager tm = mock(PlatformTransactionManager.class);
        when(tm.getTransaction(any(TransactionDefinition.class))).thenReturn(new SimpleTransactionStatus());
        return tm;
    }

    private static UnsettledInstallment inst(UUID loan, int seq, LocalDate due, String emi, UUID account) {
        return new UnsettledInstallment(loan, seq, due, new BigDecimal(emi), account);
    }

    private static AutoLinkCandidate debit(UUID account, LocalDate date, String amount) {
        return new AutoLinkCandidate(UUID.randomUUID(), account, AccountType.bank_account, date, new BigDecimal(amount),
                TransactionType.DEBIT, "ACH D- HDFC LTD");
    }

    @Test
    void aUniqueMatchLinks() {
        UnsettledInstallment i = inst(LOAN_A, 3, DUE, "25000", BANK);
        AutoLinkCandidate t = debit(BANK, DUE.plusDays(1), "25000");

        List<Pair> pairs = EmiAutoLinker.plan(List.of(i), List.of(t), Set.of());

        assertThat(pairs).hasSize(1);
        assertThat(pairs.get(0).installment()).isEqualTo(i);
        assertThat(pairs.get(0).transactionId()).isEqualTo(t.id());
    }

    @Test
    void theAmountToleranceIsTheViewsOneHundredRupeesOrOnePercent() {
        UnsettledInstallment small = inst(LOAN_A, 1, DUE, "5000", BANK);
        assertThat(EmiAutoLinker.plan(List.of(small), List.of(debit(BANK, DUE, "5099")), Set.of())).hasSize(1);
        assertThat(EmiAutoLinker.plan(List.of(small), List.of(debit(BANK, DUE, "5101")), Set.of())).isEmpty();
        UnsettledInstallment big = inst(LOAN_A, 1, DUE, "100000", BANK);
        assertThat(EmiAutoLinker.plan(List.of(big), List.of(debit(BANK, DUE, "100999")), Set.of())).hasSize(1);
        assertThat(EmiAutoLinker.plan(List.of(big), List.of(debit(BANK, DUE, "101001")), Set.of())).isEmpty();
    }

    @Test
    void anInstallmentWithTwoCandidatesIsNotLinked() {
        UnsettledInstallment i = inst(LOAN_A, 3, DUE, "25000", BANK);

        List<Pair> pairs = EmiAutoLinker.plan(List.of(i),
                List.of(debit(BANK, DUE, "25000"), debit(BANK, DUE.plusDays(2), "25010")), Set.of());

        assertThat(pairs).isEmpty();
    }

    @Test
    void aTransactionFittingTwoInstallmentsIsNotLinked() {
        // two loans, same EMI, same payment account, due a day apart: the one debit fits both
        UnsettledInstallment a = inst(LOAN_A, 3, DUE, "25000", BANK);
        UnsettledInstallment b = inst(LOAN_B, 7, DUE.plusDays(1), "25000", BANK);

        List<Pair> pairs = EmiAutoLinker.plan(List.of(a, b), List.of(debit(BANK, DUE, "25000")), Set.of());

        assertThat(pairs).isEmpty();
    }

    @Test
    void aRejectedPairIsSkipped() {
        UnsettledInstallment i = inst(LOAN_A, 3, DUE, "25000", BANK);
        AutoLinkCandidate t = debit(BANK, DUE, "25000");
        Set<String> rejected = Set.of(AutoLinkStore.pairKey(SuggestionKind.EMI, t.id(), LoanService.installmentRef(LOAN_A, 3)));

        assertThat(EmiAutoLinker.plan(List.of(i), List.of(t), rejected)).isEmpty();
    }

    @Test
    void aRejectedCandidateNoLongerMakesTheInstallmentAmbiguous() {
        UnsettledInstallment i = inst(LOAN_A, 3, DUE, "25000", BANK);
        AutoLinkCandidate rejected = debit(BANK, DUE, "25000");
        AutoLinkCandidate other = debit(BANK, DUE.plusDays(1), "25000");
        Set<String> keys = Set.of(AutoLinkStore.pairKey(SuggestionKind.EMI, rejected.id(), LoanService.installmentRef(LOAN_A, 3)));

        List<Pair> pairs = EmiAutoLinker.plan(List.of(i), List.of(rejected, other), keys);

        assertThat(pairs).extracting(Pair::transactionId).containsExactly(other.id());
    }

    @Test
    void aDebitOnAnotherAccountOutsideTheWindowOrACreditNeverFits() {
        UnsettledInstallment i = inst(LOAN_A, 3, DUE, "25000", BANK);
        AutoLinkCandidate credit = new AutoLinkCandidate(UUID.randomUUID(), BANK, AccountType.bank_account, DUE,
                new BigDecimal("25000"), TransactionType.CREDIT, "x");

        assertThat(EmiAutoLinker.plan(List.of(i), List.of(debit(OTHER_BANK, DUE, "25000")), Set.of())).isEmpty();
        assertThat(EmiAutoLinker.plan(List.of(i), List.of(debit(BANK, DUE.plusDays(8), "25000")), Set.of())).isEmpty();
        assertThat(EmiAutoLinker.plan(List.of(i), List.of(credit), Set.of())).isEmpty();
    }

    @Test
    void aLoanWithoutAPaymentAccountIsNeverAutoLinked() {
        UnsettledInstallment i = inst(LOAN_A, 3, DUE, "25000", null);

        assertThat(EmiAutoLinker.plan(List.of(i), List.of(debit(BANK, DUE, "25000")), Set.of())).isEmpty();
    }

    @Test
    void anOldUnsettledInstallmentIsMatchedOverAllHistory() {
        UnsettledInstallment old = inst(LOAN_A, 1, LocalDate.of(2024, 1, 5), "25000", BANK);

        assertThat(EmiAutoLinker.plan(List.of(old), List.of(debit(BANK, LocalDate.of(2024, 1, 6), "25000")), Set.of())).hasSize(1);
    }

    @Test
    void linkWritesThePaymentThroughTheConfirmPathAndCounts() {
        AutoLinkCandidateLoader loader = mock(AutoLinkCandidateLoader.class);
        AutoLinkStore store = mock(AutoLinkStore.class);
        LoanService loanService = mock(LoanService.class);
        UUID userId = UUID.randomUUID();
        UnsettledInstallment i = inst(LOAN_A, 3, DUE, "25000", BANK);
        AutoLinkCandidate t = debit(BANK, DUE, "25000");
        when(loanService.getUnsettledInstallments()).thenReturn(List.of(i));
        when(loader.load(userId)).thenReturn(List.of(t));
        when(store.loadRejectionKeys(userId)).thenReturn(Set.of());
        when(loader.freeIds(eq(userId), any())).thenReturn(Set.of(t.id()));

        AutoLinkOutcome outcome = new EmiAutoLinker(loader, store, loanService, tm()).link(userId);

        verify(loanService).addAutoPayment(LOAN_A, 3, t.id());
        assertThat(outcome.byKind().get(SuggestionKind.EMI).linked()).isEqualTo(1);
        assertThat(outcome.partial()).isFalse();
    }

    @Test
    void aTransactionLinkedByAnotherPassMeanwhileIsNotLinked() {
        AutoLinkCandidateLoader loader = mock(AutoLinkCandidateLoader.class);
        AutoLinkStore store = mock(AutoLinkStore.class);
        LoanService loanService = mock(LoanService.class);
        UUID userId = UUID.randomUUID();
        AutoLinkCandidate t = debit(BANK, DUE, "25000");
        when(loanService.getUnsettledInstallments()).thenReturn(List.of(inst(LOAN_A, 3, DUE, "25000", BANK)));
        when(loader.load(userId)).thenReturn(List.of(t));
        when(store.loadRejectionKeys(userId)).thenReturn(Set.of());
        when(loader.freeIds(eq(userId), any())).thenReturn(Set.of());

        AutoLinkOutcome outcome = new EmiAutoLinker(loader, store, loanService, tm()).link(userId);

        verify(loanService, never()).addAutoPayment(any(), org.mockito.ArgumentMatchers.anyInt(), any());
        assertThat(outcome.linked()).isZero();
    }

    @Test
    void aFailingPairIsLoggedAndMakesTheOutcomePartialWhileTheNextStillLinks() {
        AutoLinkCandidateLoader loader = mock(AutoLinkCandidateLoader.class);
        AutoLinkStore store = mock(AutoLinkStore.class);
        LoanService loanService = mock(LoanService.class);
        UUID userId = UUID.randomUUID();
        AutoLinkCandidate t1 = debit(BANK, DUE, "25000");
        AutoLinkCandidate t2 = debit(OTHER_BANK, DUE, "9000");
        when(loanService.getUnsettledInstallments()).thenReturn(List.of(
                inst(LOAN_A, 3, DUE, "25000", BANK), inst(LOAN_B, 2, DUE, "9000", OTHER_BANK)));
        when(loader.load(userId)).thenReturn(List.of(t1, t2));
        when(store.loadRejectionKeys(userId)).thenReturn(Set.of());
        when(loader.freeIds(eq(userId), any())).thenReturn(Set.of(t1.id(), t2.id()));
        doThrow(new IllegalStateException("boom")).when(loanService).addAutoPayment(LOAN_A, 3, t1.id());

        AutoLinkOutcome outcome = new EmiAutoLinker(loader, store, loanService, tm()).link(userId);

        verify(loanService).addAutoPayment(LOAN_B, 2, t2.id());
        assertThat(outcome.linked()).isEqualTo(1);
        assertThat(outcome.partial()).isTrue();
    }
}
