package com.financeos.domain.obligation;

import com.financeos.api.transaction.dto.ObligationRef;
import com.financeos.domain.holding.Holding;
import com.financeos.domain.instrument.Instrument;
import com.financeos.domain.investment.dividend.Dividend;
import com.financeos.domain.investment.dividend.DividendRepository;
import com.financeos.domain.investment.dividend.DividendType;
import com.financeos.domain.lending.Counterparty;
import com.financeos.domain.lending.Lending;
import com.financeos.domain.lending.LendingDirection;
import com.financeos.domain.lending.LendingKind;
import com.financeos.domain.lending.LendingRepository;
import com.financeos.domain.loan.Loan;
import com.financeos.domain.loan.LoanChargeRepository;
import com.financeos.domain.loan.LoanEventRepository;
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
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/** DIVIDEND family behaviour of the obligation-ref service; loan/lending cases live in ObligationRefServiceTest. */
class ObligationRefServiceDividendTest {

    private LendingRepository lendingRepository;
    private LoanPaymentRepository loanPaymentRepository;
    private DividendRepository dividendRepository;
    private ObligationRefService service;

    @BeforeEach
    void setUp() {
        lendingRepository = mock(LendingRepository.class);
        loanPaymentRepository = mock(LoanPaymentRepository.class);
        dividendRepository = mock(DividendRepository.class);
        service = new ObligationRefService(lendingRepository, loanPaymentRepository,
                mock(LoanEventRepository.class), mock(LoanChargeRepository.class), dividendRepository);
    }

    private Transaction txn(TransactionType type) {
        Transaction t = new Transaction();
        t.setId(UUID.randomUUID());
        t.setType(type);
        return t;
    }

    private Dividend dividend(Transaction t, DividendType type, String name, String symbol, String amount) {
        Instrument i = new Instrument();
        i.setId(UUID.randomUUID());
        i.setName(name);
        i.setSymbol(symbol);
        Holding h = new Holding();
        h.setId(UUID.randomUUID());
        h.setInstrument(i);
        Dividend d = new Dividend();
        d.setId(UUID.randomUUID());
        d.setHolding(h);
        d.setTransaction(t);
        d.setType(type);
        d.setAmount(new BigDecimal(amount));
        return d;
    }

    private Lending lending(Transaction t) {
        Lending l = new Lending();
        l.setId(UUID.randomUUID());
        l.setTransaction(t);
        l.setDirection(LendingDirection.borrowed);
        l.setKind(LendingKind.principal);
        l.setAmount(new BigDecimal("500"));
        Counterparty cp = new Counterparty();
        cp.setId(UUID.randomUUID());
        cp.setName("Rahul");
        l.setCounterparty(cp);
        return l;
    }

    private LoanPayment payment(Transaction t) {
        Loan loan = new Loan();
        loan.setId(UUID.randomUUID());
        loan.setName("Home Loan");
        LoanPayment p = new LoanPayment();
        p.setId(UUID.randomUUID());
        p.setTransaction(t);
        p.setLoan(loan);
        p.setInstallmentSeq(4);
        p.setAmount(new BigDecimal("500"));
        return p;
    }

    // --- refsFor -------------------------------------------------------------------------------------

    @Test
    void refsFor_includesDividendRowsWithTickerLabelAndInstrumentAsParent() {
        Transaction t = txn(TransactionType.CREDIT);
        Dividend d = dividend(t, DividendType.dividend, "Infosys Limited", "INFY", "1234.50");
        when(dividendRepository.findWithHoldingByTransactionIdIn(List.of(t.getId()))).thenReturn(List.of(d));

        Map<UUID, List<ObligationRef>> map = service.refsFor(List.of(t.getId()));

        ObligationRef ref = map.get(t.getId()).get(0);
        assertEquals(ObligationKind.DIVIDEND, ref.kind());
        assertEquals(d.getId(), ref.id());
        assertEquals(d.getHolding().getInstrument().getId(), ref.parentId());
        assertEquals("Dividend · INFY", ref.label());
        assertEquals(0, ref.amount().compareTo(new BigDecimal("1234.50")));
    }

    @Test
    void refsFor_usesTheInstrumentNameWhenTheSymbolIsAnIsinOrAmfiCode() {
        Transaction t = txn(TransactionType.CREDIT);
        Dividend d = dividend(t, DividendType.dividend, "ICICI Pru Bluechip IDCW", "INF109K01BL4", "100");
        when(dividendRepository.findWithHoldingByTransactionIdIn(List.of(t.getId()))).thenReturn(List.of(d));
        assertEquals("Dividend · ICICI Pru Bluechip IDCW", service.refsFor(t.getId()).get(0).label());
    }

    @Test
    void refsFor_labelsInterestAndOtherPayouts() {
        Transaction a = txn(TransactionType.CREDIT);
        Transaction b = txn(TransactionType.CREDIT);
        when(dividendRepository.findWithHoldingByTransactionIdIn(List.of(a.getId())))
                .thenReturn(List.of(dividend(a, DividendType.interest, "Some Bond", "BOND1", "10")));
        when(dividendRepository.findWithHoldingByTransactionIdIn(List.of(b.getId())))
                .thenReturn(List.of(dividend(b, DividendType.other, "Some REIT", "REIT1", "10")));
        assertEquals("Interest · BOND1", service.refsFor(a.getId()).get(0).label());
        assertEquals("Payout · REIT1", service.refsFor(b.getId()).get(0).label());
    }

    @Test
    void refsFor_toleratesADividendWithoutHolding() {
        Transaction t = txn(TransactionType.CREDIT);
        Dividend d = dividend(t, DividendType.dividend, "x", "X", "1");
        d.setHolding(null);
        when(dividendRepository.findWithHoldingByTransactionIdIn(List.of(t.getId()))).thenReturn(List.of(d));
        ObligationRef ref = service.refsFor(t.getId()).get(0);
        assertNull(ref.parentId());
        assertEquals("Dividend · ", ref.label());
    }

    // --- repoint -----------------------------------------------------------------------------------

    @Test
    void repoint_movesDividendRowsOntoTheKeptTransaction() {
        Transaction from = txn(TransactionType.CREDIT);
        Transaction to = txn(TransactionType.CREDIT);
        Dividend d = dividend(from, DividendType.dividend, "ITC Limited", "ITC", "50");
        when(dividendRepository.findWithHoldingByTransactionIdIn(List.of(from.getId()))).thenReturn(List.of(d));

        List<String> moved = service.repoint(from, to);

        assertSame(to, d.getTransaction());
        assertEquals(List.of("Dividend · ITC"), moved);
        verify(dividendRepository).saveAll(List.of(d));
        verify(dividendRepository).flush();
    }

    @Test
    void repoint_withNoDividendRowsDoesNotTouchTheRepository() {
        Transaction from = txn(TransactionType.CREDIT);
        Transaction to = txn(TransactionType.CREDIT);
        service.repoint(from, to);
        verify(dividendRepository, never()).saveAll(org.mockito.ArgumentMatchers.anyList());
        verify(dividendRepository, never()).flush();
    }

    // --- merge compatibility ------------------------------------------------------------------------

    @Test
    void merge_dividendOntoAnUnreferencedCreditIsFine() {
        Transaction kept = txn(TransactionType.CREDIT);
        Transaction deleted = txn(TransactionType.CREDIT);
        when(dividendRepository.findWithHoldingByTransactionIdIn(List.of(deleted.getId())))
                .thenReturn(List.of(dividend(deleted, DividendType.dividend, "ITC Limited", "ITC", "50")));
        assertNull(service.checkMergeCompatibility(kept, deleted));
    }

    @Test
    void merge_dividendPlusDividendIsFine() {
        Transaction kept = txn(TransactionType.CREDIT);
        Transaction deleted = txn(TransactionType.CREDIT);
        when(dividendRepository.findWithHoldingByTransactionIdIn(List.of(deleted.getId())))
                .thenReturn(List.of(dividend(deleted, DividendType.dividend, "ITC Limited", "ITC", "50")));
        when(dividendRepository.findWithHoldingByTransactionIdIn(List.of(kept.getId())))
                .thenReturn(List.of(dividend(kept, DividendType.dividend, "ITC Limited", "ITC", "20")));
        assertNull(service.checkMergeCompatibility(kept, deleted));
    }

    @Test
    void merge_dividendVersusLendingIsRejected() {
        Transaction kept = txn(TransactionType.CREDIT);
        Transaction deleted = txn(TransactionType.CREDIT);
        when(dividendRepository.findWithHoldingByTransactionIdIn(List.of(deleted.getId())))
                .thenReturn(List.of(dividend(deleted, DividendType.dividend, "ITC Limited", "ITC", "50")));
        when(lendingRepository.findWithCounterpartyByTransactionIdIn(List.of(kept.getId()))).thenReturn(List.of(lending(kept)));

        String message = service.checkMergeCompatibility(kept, deleted);

        assertNotNull(message);
        assertTrue(message.contains("unlink one before merging"));
        assertTrue(message.contains("dividend"));
    }

    @Test
    void merge_lendingOntoDividendIsRejectedTheOtherWayRoundToo() {
        Transaction kept = txn(TransactionType.CREDIT);
        Transaction deleted = txn(TransactionType.CREDIT);
        when(lendingRepository.findWithCounterpartyByTransactionIdIn(List.of(deleted.getId()))).thenReturn(List.of(lending(deleted)));
        when(dividendRepository.findWithHoldingByTransactionIdIn(List.of(kept.getId())))
                .thenReturn(List.of(dividend(kept, DividendType.dividend, "ITC Limited", "ITC", "50")));
        assertNotNull(service.checkMergeCompatibility(kept, deleted));
    }

    @Test
    void merge_dividendVersusLoanIsRejected() {
        Transaction kept = txn(TransactionType.CREDIT);
        Transaction deleted = txn(TransactionType.CREDIT);
        when(dividendRepository.findWithHoldingByTransactionIdIn(List.of(deleted.getId())))
                .thenReturn(List.of(dividend(deleted, DividendType.dividend, "ITC Limited", "ITC", "50")));
        when(loanPaymentRepository.findWithLoanByTransactionIdIn(List.of(kept.getId()))).thenReturn(List.of(payment(kept)));
        String message = service.checkMergeCompatibility(kept, deleted);
        assertNotNull(message);
        assertTrue(message.contains("unlink one before merging"));
    }

    @Test
    void merge_oppositeDirectionIsRejectedBeforeFamilyChecks() {
        Transaction kept = txn(TransactionType.DEBIT);
        Transaction deleted = txn(TransactionType.CREDIT);
        when(dividendRepository.findWithHoldingByTransactionIdIn(List.of(deleted.getId())))
                .thenReturn(List.of(dividend(deleted, DividendType.dividend, "ITC Limited", "ITC", "50")));
        String message = service.checkMergeCompatibility(kept, deleted);
        assertNotNull(message);
        assertTrue(message.contains("opposite direction"));
        verify(dividendRepository, never()).findWithHoldingByTransactionIdIn(eq(List.of(kept.getId())));
    }

    @Test
    void merge_existingLoanLendingMessageIsUnchangedWhenNoDividendIsInvolved() {
        Transaction kept = txn(TransactionType.CREDIT);
        Transaction deleted = txn(TransactionType.CREDIT);
        when(lendingRepository.findWithCounterpartyByTransactionIdIn(List.of(deleted.getId()))).thenReturn(List.of(lending(deleted)));
        when(loanPaymentRepository.findWithLoanByTransactionIdIn(List.of(kept.getId()))).thenReturn(List.of(payment(kept)));
        String message = service.checkMergeCompatibility(kept, deleted);
        assertTrue(message.startsWith("Both transactions are linked to loan/lending records ("));
    }
}
