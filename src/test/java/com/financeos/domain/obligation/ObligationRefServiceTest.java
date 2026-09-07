package com.financeos.domain.obligation;

import com.financeos.api.transaction.dto.ObligationRef;
import com.financeos.domain.lending.Counterparty;
import com.financeos.domain.lending.Lending;
import com.financeos.domain.lending.LendingDirection;
import com.financeos.domain.lending.LendingRepository;
import com.financeos.domain.loan.Loan;
import com.financeos.domain.loan.LoanCharge;
import com.financeos.domain.loan.LoanChargeRepository;
import com.financeos.domain.loan.LoanChargeType;
import com.financeos.domain.loan.LoanEvent;
import com.financeos.domain.loan.LoanEventRepository;
import com.financeos.domain.loan.LoanEventType;
import com.financeos.domain.loan.LoanPayment;
import com.financeos.domain.loan.LoanPaymentRepository;
import com.financeos.domain.transaction.Transaction;
import com.financeos.domain.transaction.TransactionType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ObligationRefServiceTest {

    private LendingRepository lendingRepository;
    private LoanPaymentRepository loanPaymentRepository;
    private LoanEventRepository loanEventRepository;
    private LoanChargeRepository loanChargeRepository;

    private ObligationRefService service;

    @BeforeEach
    void setUp() {
        lendingRepository = mock(LendingRepository.class);
        loanPaymentRepository = mock(LoanPaymentRepository.class);
        loanEventRepository = mock(LoanEventRepository.class);
        loanChargeRepository = mock(LoanChargeRepository.class);
        service = new ObligationRefService(lendingRepository, loanPaymentRepository, loanEventRepository, loanChargeRepository);
    }

    private Transaction txn(UUID id) {
        Transaction t = new Transaction();
        t.setId(id);
        return t;
    }

    private Lending lending(UUID id, Transaction t, LendingDirection direction, String cpName, BigDecimal amount) {
        Lending l = new Lending();
        l.setId(id);
        l.setTransaction(t);
        l.setDirection(direction);
        l.setAmount(amount);
        Counterparty cp = new Counterparty();
        cp.setId(UUID.randomUUID());
        cp.setName(cpName);
        l.setCounterparty(cp);
        return l;
    }

    private Loan loan(UUID id, String name) {
        Loan loan = new Loan();
        loan.setId(id);
        loan.setName(name);
        return loan;
    }

    private LoanPayment payment(UUID id, Transaction t, Loan loan, Integer seq, BigDecimal amount) {
        LoanPayment p = new LoanPayment();
        p.setId(id);
        p.setTransaction(t);
        p.setLoan(loan);
        p.setInstallmentSeq(seq);
        p.setAmount(amount);
        return p;
    }

    private LoanEvent event(UUID id, Transaction t, Loan loan, LoanEventType type, BigDecimal amount) {
        LoanEvent e = new LoanEvent();
        e.setId(id);
        e.setTransaction(t);
        e.setLoan(loan);
        e.setEventType(type);
        e.setAmount(amount);
        return e;
    }

    private LoanCharge charge(UUID id, Transaction t, Loan loan, LoanChargeType type, BigDecimal amount) {
        LoanCharge c = new LoanCharge();
        c.setId(id);
        c.setTransaction(t);
        c.setLoan(loan);
        c.setChargeType(type);
        c.setAmount(amount);
        return c;
    }

    // --- refsFor(Collection) ---------------------------------------------------------------

    @Test
    void refsForCollection_emptyOrNull_returnsEmptyMapWithoutRepoCalls() {
        assertEquals(Map.of(), service.refsFor(List.<UUID>of()));
        assertEquals(Map.of(), service.refsFor((List<UUID>) null));
        verifyNoInteractions(lendingRepository, loanPaymentRepository, loanEventRepository, loanChargeRepository);
    }

    @Test
    void refsForCollection_queriesEachRepositoryOnceWithGivenIds() {
        UUID id1 = UUID.randomUUID();
        UUID id2 = UUID.randomUUID();
        List<UUID> ids = List.of(id1, id2);

        when(lendingRepository.findWithCounterpartyByTransactionIdIn(ids)).thenReturn(List.of());
        when(loanPaymentRepository.findWithLoanByTransactionIdIn(ids)).thenReturn(List.of());
        when(loanEventRepository.findWithLoanByTransactionIdIn(ids)).thenReturn(List.of());
        when(loanChargeRepository.findWithLoanByTransactionIdIn(ids)).thenReturn(List.of());

        Map<UUID, List<ObligationRef>> result = service.refsFor(ids);

        assertTrue(result.isEmpty());
        verify(lendingRepository, times(1)).findWithCounterpartyByTransactionIdIn(ids);
        verify(loanPaymentRepository, times(1)).findWithLoanByTransactionIdIn(ids);
        verify(loanEventRepository, times(1)).findWithLoanByTransactionIdIn(ids);
        verify(loanChargeRepository, times(1)).findWithLoanByTransactionIdIn(ids);
    }

    @Test
    void refsForCollection_lendingLent_labelAndParentId() {
        UUID txnId = UUID.randomUUID();
        Transaction t = txn(txnId);
        Lending l = lending(UUID.randomUUID(), t, LendingDirection.lent, "Rahul", new BigDecimal("500.00"));
        when(lendingRepository.findWithCounterpartyByTransactionIdIn(List.of(txnId))).thenReturn(List.of(l));

        Map<UUID, List<ObligationRef>> result = service.refsFor(List.of(txnId));

        ObligationRef ref = result.get(txnId).get(0);
        assertEquals(ObligationKind.LENDING, ref.kind());
        assertEquals("Lent · Rahul", ref.label());
        assertEquals(l.getCounterparty().getId(), ref.parentId());
        assertEquals(new BigDecimal("500.00"), ref.amount());
    }

    @Test
    void refsForCollection_lendingBorrowed_label() {
        UUID txnId = UUID.randomUUID();
        Transaction t = txn(txnId);
        Lending l = lending(UUID.randomUUID(), t, LendingDirection.borrowed, "Priya", new BigDecimal("200.00"));
        when(lendingRepository.findWithCounterpartyByTransactionIdIn(List.of(txnId))).thenReturn(List.of(l));

        Map<UUID, List<ObligationRef>> result = service.refsFor(List.of(txnId));

        assertEquals("Borrowed · Priya", result.get(txnId).get(0).label());
    }

    @Test
    void refsForCollection_loanPaymentWithSeq_label() {
        UUID txnId = UUID.randomUUID();
        Transaction t = txn(txnId);
        Loan loan = loan(UUID.randomUUID(), "HDFC Home Loan");
        LoanPayment p = payment(UUID.randomUUID(), t, loan, 4, new BigDecimal("15000.00"));
        when(loanPaymentRepository.findWithLoanByTransactionIdIn(List.of(txnId))).thenReturn(List.of(p));

        ObligationRef ref = service.refsFor(List.of(txnId)).get(txnId).get(0);
        assertEquals(ObligationKind.LOAN_PAYMENT, ref.kind());
        assertEquals("EMI #4 · HDFC Home Loan", ref.label());
        assertEquals(loan.getId(), ref.parentId());
        assertEquals(new BigDecimal("15000.00"), ref.amount());
    }

    @Test
    void refsForCollection_loanPaymentNoSeq_label() {
        UUID txnId = UUID.randomUUID();
        Transaction t = txn(txnId);
        Loan loan = loan(UUID.randomUUID(), "HDFC Home Loan");
        LoanPayment p = payment(UUID.randomUUID(), t, loan, null, new BigDecimal("15000.00"));
        when(loanPaymentRepository.findWithLoanByTransactionIdIn(List.of(txnId))).thenReturn(List.of(p));

        ObligationRef ref = service.refsFor(List.of(txnId)).get(txnId).get(0);
        assertEquals("EMI · HDFC Home Loan", ref.label());
    }

    @Test
    void refsForCollection_loanEventLabels_perType() {
        UUID txnId1 = UUID.randomUUID();
        UUID txnId2 = UUID.randomUUID();
        UUID txnId3 = UUID.randomUUID();
        Loan loan = loan(UUID.randomUUID(), "ICICI Car Loan");

        LoanEvent rateChange = event(UUID.randomUUID(), txn(txnId1), loan, LoanEventType.rate_change, new BigDecimal("0"));
        LoanEvent prepayment = event(UUID.randomUUID(), txn(txnId2), loan, LoanEventType.prepayment, new BigDecimal("10000.00"));
        LoanEvent foreclosure = event(UUID.randomUUID(), txn(txnId3), loan, LoanEventType.foreclosure, new BigDecimal("50000.00"));

        when(loanEventRepository.findWithLoanByTransactionIdIn(List.of(txnId1)))
                .thenReturn(List.of(rateChange));
        when(loanEventRepository.findWithLoanByTransactionIdIn(List.of(txnId2)))
                .thenReturn(List.of(prepayment));
        when(loanEventRepository.findWithLoanByTransactionIdIn(List.of(txnId3)))
                .thenReturn(List.of(foreclosure));

        assertEquals("Rate change · ICICI Car Loan", service.refsFor(List.of(txnId1)).get(txnId1).get(0).label());
        assertEquals("Prepayment · ICICI Car Loan", service.refsFor(List.of(txnId2)).get(txnId2).get(0).label());
        assertEquals("Foreclosure · ICICI Car Loan", service.refsFor(List.of(txnId3)).get(txnId3).get(0).label());
    }

    @Test
    void refsForCollection_loanEvent_parentIdIsLoanId() {
        UUID txnId = UUID.randomUUID();
        Loan loan = loan(UUID.randomUUID(), "ICICI Car Loan");
        LoanEvent e = event(UUID.randomUUID(), txn(txnId), loan, LoanEventType.prepayment, new BigDecimal("10000.00"));
        when(loanEventRepository.findWithLoanByTransactionIdIn(List.of(txnId))).thenReturn(List.of(e));

        ObligationRef ref = service.refsFor(List.of(txnId)).get(txnId).get(0);
        assertEquals(ObligationKind.LOAN_EVENT, ref.kind());
        assertEquals(loan.getId(), ref.parentId());
    }

    @Test
    void refsForCollection_loanChargeLabels_perType() {
        Loan loan = loan(UUID.randomUUID(), "SBI Personal Loan");
        Map<LoanChargeType, String> expected = Map.of(
                LoanChargeType.processing_fee, "Processing fee",
                LoanChargeType.insurance_premium, "Insurance premium",
                LoanChargeType.foreclosure_charge, "Foreclosure charge",
                LoanChargeType.bounce_charge, "Bounce charge",
                LoanChargeType.late_fee, "Late fee",
                LoanChargeType.legal_valuation, "Legal / valuation",
                LoanChargeType.other, "Loan charge"
        );

        for (Map.Entry<LoanChargeType, String> e : expected.entrySet()) {
            UUID txnId = UUID.randomUUID();
            LoanCharge c = charge(UUID.randomUUID(), txn(txnId), loan, e.getKey(), new BigDecimal("100.00"));
            when(loanChargeRepository.findWithLoanByTransactionIdIn(List.of(txnId))).thenReturn(List.of(c));

            ObligationRef ref = service.refsFor(List.of(txnId)).get(txnId).get(0);
            assertEquals(ObligationKind.LOAN_CHARGE, ref.kind());
            assertEquals(e.getValue() + " · SBI Personal Loan", ref.label());
            assertEquals(loan.getId(), ref.parentId());
        }
    }

    @Test
    void refsForCollection_twoLendingsOnOneTransaction_bothPresent() {
        UUID txnId = UUID.randomUUID();
        Transaction t = txn(txnId);
        Lending l1 = lending(UUID.randomUUID(), t, LendingDirection.lent, "Rahul", new BigDecimal("500.00"));
        Lending l2 = lending(UUID.randomUUID(), t, LendingDirection.borrowed, "Priya", new BigDecimal("200.00"));
        when(lendingRepository.findWithCounterpartyByTransactionIdIn(List.of(txnId))).thenReturn(List.of(l1, l2));

        List<ObligationRef> refs = service.refsFor(List.of(txnId)).get(txnId);
        assertEquals(2, refs.size());
        assertTrue(refs.stream().anyMatch(r -> r.label().equals("Lent · Rahul")));
        assertTrue(refs.stream().anyMatch(r -> r.label().equals("Borrowed · Priya")));
    }

    // --- refsFor(UUID) / hasRefs -------------------------------------------------------------

    @Test
    void refsForSingle_null_returnsEmpty() {
        assertEquals(List.of(), service.refsFor((UUID) null));
        verifyNoInteractions(lendingRepository, loanPaymentRepository, loanEventRepository, loanChargeRepository);
    }

    @Test
    void refsForSingle_delegatesToBatch() {
        UUID txnId = UUID.randomUUID();
        Lending l = lending(UUID.randomUUID(), txn(txnId), LendingDirection.lent, "Rahul", new BigDecimal("500.00"));
        when(lendingRepository.findWithCounterpartyByTransactionIdIn(List.of(txnId))).thenReturn(List.of(l));

        assertEquals(1, service.refsFor(txnId).size());
    }

    @Test
    void hasRefs_true() {
        UUID txnId = UUID.randomUUID();
        Lending l = lending(UUID.randomUUID(), txn(txnId), LendingDirection.lent, "Rahul", new BigDecimal("500.00"));
        when(lendingRepository.findWithCounterpartyByTransactionIdIn(List.of(txnId))).thenReturn(List.of(l));

        assertTrue(service.hasRefs(txnId));
    }

    @Test
    void hasRefs_false() {
        UUID txnId = UUID.randomUUID();
        when(lendingRepository.findWithCounterpartyByTransactionIdIn(List.of(txnId))).thenReturn(List.of());
        when(loanPaymentRepository.findWithLoanByTransactionIdIn(List.of(txnId))).thenReturn(List.of());
        when(loanEventRepository.findWithLoanByTransactionIdIn(List.of(txnId))).thenReturn(List.of());
        when(loanChargeRepository.findWithLoanByTransactionIdIn(List.of(txnId))).thenReturn(List.of());

        assertFalse(service.hasRefs(txnId));
    }

    // --- repoint -----------------------------------------------------------------------------

    @Test
    void repoint_movesEveryRowAndSavesOnlyNonEmptyRepositories() {
        Transaction from = txn(UUID.randomUUID());
        Transaction to = txn(UUID.randomUUID());

        Lending l = lending(UUID.randomUUID(), from, LendingDirection.lent, "Rahul", new BigDecimal("500.00"));
        Loan loan = loan(UUID.randomUUID(), "HDFC Home Loan");
        LoanPayment p = payment(UUID.randomUUID(), from, loan, 2, new BigDecimal("15000.00"));

        when(lendingRepository.findWithCounterpartyByTransactionIdIn(List.of(from.getId()))).thenReturn(List.of(l));
        when(loanPaymentRepository.findWithLoanByTransactionIdIn(List.of(from.getId()))).thenReturn(List.of(p));
        when(loanEventRepository.findWithLoanByTransactionIdIn(List.of(from.getId()))).thenReturn(List.of());
        when(loanChargeRepository.findWithLoanByTransactionIdIn(List.of(from.getId()))).thenReturn(List.of());

        List<String> moved = service.repoint(from, to);

        assertSame(to, l.getTransaction());
        assertSame(to, p.getTransaction());
        assertEquals(List.of("Lent · Rahul", "EMI #2 · HDFC Home Loan"), moved);

        verify(lendingRepository).saveAll(List.of(l));
        verify(lendingRepository).flush();
        verify(loanPaymentRepository).saveAll(List.of(p));
        verify(loanPaymentRepository).flush();

        verify(loanEventRepository, never()).saveAll(any());
        verify(loanEventRepository, never()).flush();
        verify(loanChargeRepository, never()).saveAll(any());
        verify(loanChargeRepository, never()).flush();
    }

    // --- checkMergeCompatibility ---------------------------------------------------------------

    private Transaction txnWithType(TransactionType type) {
        Transaction t = txn(UUID.randomUUID());
        t.setType(type);
        return t;
    }

    private void stubNoRefs(UUID id) {
        when(lendingRepository.findWithCounterpartyByTransactionIdIn(List.of(id))).thenReturn(List.of());
        when(loanPaymentRepository.findWithLoanByTransactionIdIn(List.of(id))).thenReturn(List.of());
        when(loanEventRepository.findWithLoanByTransactionIdIn(List.of(id))).thenReturn(List.of());
        when(loanChargeRepository.findWithLoanByTransactionIdIn(List.of(id))).thenReturn(List.of());
    }

    @Test
    void checkMergeCompatibility_deletedHasNoRefs_returnsNullAndKeptNeverQueried() {
        Transaction kept = txnWithType(TransactionType.DEBIT);
        Transaction deleted = txnWithType(TransactionType.DEBIT);
        stubNoRefs(deleted.getId());

        assertNull(service.checkMergeCompatibility(kept, deleted));

        verify(lendingRepository, never()).findWithCounterpartyByTransactionIdIn(List.of(kept.getId()));
        verify(loanPaymentRepository, never()).findWithLoanByTransactionIdIn(List.of(kept.getId()));
        verify(loanEventRepository, never()).findWithLoanByTransactionIdIn(List.of(kept.getId()));
        verify(loanChargeRepository, never()).findWithLoanByTransactionIdIn(List.of(kept.getId()));
    }

    @Test
    void checkMergeCompatibility_typesDiffer_rejectsWithOppositeDirectionMessage() {
        Transaction kept = txnWithType(TransactionType.CREDIT);
        Transaction deleted = txnWithType(TransactionType.DEBIT);
        Lending l = lending(UUID.randomUUID(), deleted, LendingDirection.lent, "Rahul", new BigDecimal("500.00"));
        when(lendingRepository.findWithCounterpartyByTransactionIdIn(List.of(deleted.getId()))).thenReturn(List.of(l));

        String message = service.checkMergeCompatibility(kept, deleted);
        assertNotNull(message);
        assertTrue(message.contains("opposite direction"));
    }

    @Test
    void checkMergeCompatibility_deletedHasRefsKeptHasNone_returnsNull() {
        Transaction kept = txnWithType(TransactionType.DEBIT);
        Transaction deleted = txnWithType(TransactionType.DEBIT);
        Lending l = lending(UUID.randomUUID(), deleted, LendingDirection.lent, "Rahul", new BigDecimal("500.00"));
        when(lendingRepository.findWithCounterpartyByTransactionIdIn(List.of(deleted.getId()))).thenReturn(List.of(l));
        stubNoRefs(kept.getId());

        assertNull(service.checkMergeCompatibility(kept, deleted));
    }

    @Test
    void checkMergeCompatibility_bothLendingSameType_returnsNull() {
        Transaction kept = txnWithType(TransactionType.DEBIT);
        Transaction deleted = txnWithType(TransactionType.DEBIT);
        Lending keptLending = lending(UUID.randomUUID(), kept, LendingDirection.lent, "Amit", new BigDecimal("300.00"));
        Lending deletedLending = lending(UUID.randomUUID(), deleted, LendingDirection.lent, "Rahul", new BigDecimal("500.00"));
        when(lendingRepository.findWithCounterpartyByTransactionIdIn(List.of(deleted.getId()))).thenReturn(List.of(deletedLending));
        when(lendingRepository.findWithCounterpartyByTransactionIdIn(List.of(kept.getId()))).thenReturn(List.of(keptLending));

        assertNull(service.checkMergeCompatibility(kept, deleted));
    }

    @Test
    void checkMergeCompatibility_deletedHasLoanRef_rejectsWithUnlinkMessage() {
        Transaction kept = txnWithType(TransactionType.DEBIT);
        Transaction deleted = txnWithType(TransactionType.DEBIT);
        Loan loan = loan(UUID.randomUUID(), "HDFC Home Loan");
        LoanPayment deletedPayment = payment(UUID.randomUUID(), deleted, loan, 1, new BigDecimal("15000.00"));
        Lending keptLending = lending(UUID.randomUUID(), kept, LendingDirection.lent, "Amit", new BigDecimal("300.00"));

        when(loanPaymentRepository.findWithLoanByTransactionIdIn(List.of(deleted.getId()))).thenReturn(List.of(deletedPayment));
        when(lendingRepository.findWithCounterpartyByTransactionIdIn(List.of(deleted.getId()))).thenReturn(List.of());
        when(loanEventRepository.findWithLoanByTransactionIdIn(List.of(deleted.getId()))).thenReturn(List.of());
        when(loanChargeRepository.findWithLoanByTransactionIdIn(List.of(deleted.getId()))).thenReturn(List.of());
        when(lendingRepository.findWithCounterpartyByTransactionIdIn(List.of(kept.getId()))).thenReturn(List.of(keptLending));
        when(loanPaymentRepository.findWithLoanByTransactionIdIn(List.of(kept.getId()))).thenReturn(List.of());
        when(loanEventRepository.findWithLoanByTransactionIdIn(List.of(kept.getId()))).thenReturn(List.of());
        when(loanChargeRepository.findWithLoanByTransactionIdIn(List.of(kept.getId()))).thenReturn(List.of());

        String message = service.checkMergeCompatibility(kept, deleted);
        assertNotNull(message);
        assertTrue(message.contains("unlink one before merging"));
    }

    @Test
    void checkMergeCompatibility_deletedLendingKeptLoan_rejectsWithSameMessage() {
        Transaction kept = txnWithType(TransactionType.DEBIT);
        Transaction deleted = txnWithType(TransactionType.DEBIT);
        Loan loan = loan(UUID.randomUUID(), "HDFC Home Loan");
        LoanPayment keptPayment = payment(UUID.randomUUID(), kept, loan, 1, new BigDecimal("15000.00"));
        Lending deletedLending = lending(UUID.randomUUID(), deleted, LendingDirection.lent, "Rahul", new BigDecimal("500.00"));

        when(lendingRepository.findWithCounterpartyByTransactionIdIn(List.of(deleted.getId()))).thenReturn(List.of(deletedLending));
        when(loanPaymentRepository.findWithLoanByTransactionIdIn(List.of(deleted.getId()))).thenReturn(List.of());
        when(loanEventRepository.findWithLoanByTransactionIdIn(List.of(deleted.getId()))).thenReturn(List.of());
        when(loanChargeRepository.findWithLoanByTransactionIdIn(List.of(deleted.getId()))).thenReturn(List.of());
        when(loanPaymentRepository.findWithLoanByTransactionIdIn(List.of(kept.getId()))).thenReturn(List.of(keptPayment));
        when(lendingRepository.findWithCounterpartyByTransactionIdIn(List.of(kept.getId()))).thenReturn(List.of());
        when(loanEventRepository.findWithLoanByTransactionIdIn(List.of(kept.getId()))).thenReturn(List.of());
        when(loanChargeRepository.findWithLoanByTransactionIdIn(List.of(kept.getId()))).thenReturn(List.of());

        String message = service.checkMergeCompatibility(kept, deleted);
        assertNotNull(message);
        assertTrue(message.contains("unlink one before merging"));
    }
}
