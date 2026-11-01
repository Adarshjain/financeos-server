package com.financeos.domain.categorization;

import com.financeos.domain.investment.dividend.Dividend;
import com.financeos.domain.investment.dividend.DividendRepository;
import com.financeos.domain.lending.Lending;
import com.financeos.domain.lending.LendingDirection;
import com.financeos.domain.lending.LendingKind;
import com.financeos.domain.lending.LendingRepository;
import com.financeos.domain.loan.LoanPayment;
import com.financeos.domain.loan.LoanPaymentRepository;
import com.financeos.domain.transaction.Transaction;
import com.financeos.domain.transaction.TransactionType;
import com.financeos.domain.transaction.link.LinkType;
import com.financeos.domain.transaction.link.TransactionLink;
import com.financeos.domain.transaction.link.TransactionLinkMember;
import com.financeos.domain.transaction.link.TransactionLinkRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** What each link type implies for its members, and the batched lookup of a transaction's existing links. */
class LinkCategoriesTest {

    private LinkCategories links;
    private TransactionLinkRepository linkRepository;
    private LendingRepository lendingRepository;
    private DividendRepository dividendRepository;
    private LoanPaymentRepository loanPaymentRepository;

    @BeforeEach
    void setUp() {
        linkRepository = mock(TransactionLinkRepository.class);
        lendingRepository = mock(LendingRepository.class);
        dividendRepository = mock(DividendRepository.class);
        loanPaymentRepository = mock(LoanPaymentRepository.class);
        links = new LinkCategories(linkRepository, lendingRepository, dividendRepository, loanPaymentRepository);
    }

    private static Transaction txn(TransactionType type) {
        Transaction t = new Transaction();
        t.setId(UUID.randomUUID());
        t.setType(type);
        return t;
    }

    private static TransactionLink link(LinkType type, Transaction anchor, Transaction... others) {
        TransactionLink l = new TransactionLink();
        l.setId(UUID.randomUUID());
        l.setType(type);
        l.getMembers().add(new TransactionLinkMember(l, anchor, true));
        for (Transaction o : others) {
            l.getMembers().add(new TransactionLinkMember(l, o, false));
        }
        return l;
    }

    @Test
    void transferMakesBothLegsSelfTransfers() {
        Transaction out = txn(TransactionType.DEBIT);
        Transaction in = txn(TransactionType.CREDIT);
        Map<UUID, LinkCategories.Target> m = links.forLink(link(LinkType.TRANSFER, out, in), false);
        assertEquals(new LinkCategories.Target("self_transfer", null), m.get(out.getId()));
        assertEquals(new LinkCategories.Target("self_transfer", null), m.get(in.getId()));
    }

    @Test
    void cardPaymentIsCardBillCreditCardOnTheDebitAndCardBillOnTheCredit() {
        Transaction bank = txn(TransactionType.DEBIT);
        Transaction card = txn(TransactionType.CREDIT);
        Map<UUID, LinkCategories.Target> m = links.forLink(link(LinkType.CC_PAYMENT, bank, card), false);
        assertEquals(new LinkCategories.Target("card_bill", "card_bill.credit_card"), m.get(bank.getId()));
        assertEquals(new LinkCategories.Target("card_bill", null), m.get(card.getId()));
    }

    @Test
    void emiAndFeeOnlyTouchTheInstallmentAndFeeSide() {
        Transaction purchase = txn(TransactionType.DEBIT);
        Transaction installment = txn(TransactionType.DEBIT);
        Map<UUID, LinkCategories.Target> emi = links.forLink(link(LinkType.EMI, purchase, installment), false);
        assertEquals(new LinkCategories.Target("emi", null), emi.get(installment.getId()));
        assertNull(emi.get(purchase.getId()));

        Map<UUID, LinkCategories.Target> fee = links.forLink(link(LinkType.FEE, purchase, installment), false);
        assertEquals(new LinkCategories.Target("fees", null), fee.get(installment.getId()));
        assertNull(fee.get(purchase.getId()));
    }

    @Test
    void refundAndReversalTakeTheOriginalsCategory() {
        Transaction original = txn(TransactionType.DEBIT);
        original.setCategoryKey("food");
        original.setSubcategoryKey("food.dining");
        Transaction credit = txn(TransactionType.CREDIT);

        TransactionLink refund = link(LinkType.REFUND, original, credit);
        assertEquals(new LinkCategories.Target("food", "food.dining"), links.forLink(refund, true).get(credit.getId()));
        assertFalse(links.forLink(refund, false).containsKey(credit.getId()), "a refund inherits only when asked to");
        assertEquals(new LinkCategories.Target("food", "food.dining"),
                links.forLink(link(LinkType.REVERSAL, original, credit), false).get(credit.getId()));

        original.setCategoryKey(null);
        assertFalse(links.forLink(refund, true).containsKey(credit.getId()), "an uncategorised original gives nothing");
    }

    @Test
    void lendingMapsLentBorrowedAndRepaid() {
        Lending lent = new Lending();
        lent.setDirection(LendingDirection.lent);
        lent.setKind(LendingKind.principal);
        Lending borrowed = new Lending();
        borrowed.setDirection(LendingDirection.borrowed);
        borrowed.setKind(LendingKind.principal);
        Lending repaidOut = new Lending();
        repaidOut.setDirection(LendingDirection.lent);
        repaidOut.setKind(LendingKind.settlement);
        Lending repaidIn = new Lending();
        repaidIn.setDirection(LendingDirection.borrowed);
        repaidIn.setKind(LendingKind.settlement);
        assertEquals("lent", LinkCategories.forLending(lent).categoryKey());
        assertEquals("borrowed", LinkCategories.forLending(borrowed).categoryKey());
        assertEquals("repaid", LinkCategories.forLending(repaidOut).categoryKey());
        assertEquals("repaid", LinkCategories.forLending(repaidIn).categoryKey());
    }

    @Test
    void targetsForReadsTransactionLinksAndRecordLinksInBatches() {
        Transaction a = txn(TransactionType.DEBIT);
        Transaction b = txn(TransactionType.CREDIT);
        Transaction lendingTxn = txn(TransactionType.DEBIT);
        Transaction dividendTxn = txn(TransactionType.CREDIT);
        Transaction loanTxn = txn(TransactionType.DEBIT);
        Transaction none = txn(TransactionType.DEBIT);
        TransactionLink transfer = link(LinkType.TRANSFER, a, b);
        when(linkRepository.findLinkIdsByMemberTransactionIds(any(Collection.class))).thenReturn(List.of(transfer.getId()));
        when(linkRepository.findWithMembersByIdIn(any(Collection.class))).thenReturn(List.of(transfer));
        Lending lending = new Lending();
        lending.setDirection(LendingDirection.lent);
        lending.setKind(LendingKind.principal);
        lending.setTransaction(lendingTxn);
        when(lendingRepository.findWithCounterpartyByTransactionIdIn(any(Collection.class))).thenReturn(List.of(lending));
        Dividend dividend = new Dividend();
        dividend.setTransaction(dividendTxn);
        when(dividendRepository.findWithHoldingByTransactionIdIn(any(Collection.class))).thenReturn(List.of(dividend));
        LoanPayment payment = new LoanPayment();
        payment.setTransaction(loanTxn);
        when(loanPaymentRepository.findWithLoanByTransactionIdIn(any(Collection.class))).thenReturn(List.of(payment));

        Map<UUID, LinkCategories.Target> m = links.targetsFor(List.of(a, b, lendingTxn, dividendTxn, loanTxn, none), true);

        assertEquals("self_transfer", m.get(a.getId()).categoryKey());
        assertEquals("self_transfer", m.get(b.getId()).categoryKey());
        assertEquals("lent", m.get(lendingTxn.getId()).categoryKey());
        assertEquals("dividends", m.get(dividendTxn.getId()).categoryKey());
        assertEquals("emi", m.get(loanTxn.getId()).categoryKey());
        assertFalse(m.containsKey(none.getId()));
        // one query of each kind for the whole batch, not one per transaction
        verify(linkRepository, times(1)).findLinkIdsByMemberTransactionIds(any(Collection.class));
        verify(lendingRepository, times(1)).findWithCounterpartyByTransactionIdIn(any(Collection.class));
        verify(dividendRepository, times(1)).findWithHoldingByTransactionIdIn(any(Collection.class));
        verify(loanPaymentRepository, times(1)).findWithLoanByTransactionIdIn(any(Collection.class));
    }

    @Test
    void noPersistedTransactionsMeansNoQueries() {
        Transaction fresh = new Transaction();
        fresh.setType(TransactionType.DEBIT);
        assertEquals(Map.of(), links.targetsFor(List.of(fresh), true));
        verify(linkRepository, times(0)).findLinkIdsByMemberTransactionIds(any(Collection.class));
    }
}
