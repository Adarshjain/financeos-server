package com.financeos.domain.obligation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.financeos.api.transaction.dto.ObligationRef;
import com.financeos.domain.instrument.Instrument;
import com.financeos.domain.holding.Holding;
import com.financeos.domain.investment.dividend.Dividend;
import com.financeos.domain.investment.dividend.DividendRepository;
import com.financeos.domain.investment.dividend.DividendType;
import com.financeos.domain.lending.Counterparty;
import com.financeos.domain.lending.Lending;
import com.financeos.domain.lending.LendingDirection;
import com.financeos.domain.lending.LendingRepository;
import com.financeos.domain.loan.Loan;
import com.financeos.domain.loan.LoanChargeRepository;
import com.financeos.domain.loan.LoanEventRepository;
import com.financeos.domain.loan.LoanPayment;
import com.financeos.domain.loan.LoanPaymentRepository;
import com.financeos.domain.transaction.Transaction;
import com.financeos.domain.transaction.link.LinkOrigin;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** The origin of the record's link rides on each ObligationRef, from one batched query per table. */
class ObligationRefOriginTest {

    private final LendingRepository lendings = mock(LendingRepository.class);
    private final LoanPaymentRepository payments = mock(LoanPaymentRepository.class);
    private final DividendRepository dividends = mock(DividendRepository.class);
    private final ObligationRefService service = new ObligationRefService(lendings, payments,
            mock(LoanEventRepository.class), mock(LoanChargeRepository.class), dividends);

    private static Transaction tx() {
        Transaction t = new Transaction();
        t.setId(UUID.randomUUID());
        return t;
    }

    @Test
    void loanPaymentLendingAndDividendCarryTheirOriginAndAreBatched() {
        Transaction a = tx();
        Transaction b = tx();
        Transaction c = tx();
        Transaction d = tx();

        Counterparty cp = new Counterparty();
        cp.setId(UUID.randomUUID());
        cp.setName("Rahul");
        Lending autoLending = new Lending();
        autoLending.setId(UUID.randomUUID());
        autoLending.setCounterparty(cp);
        autoLending.setDirection(LendingDirection.lent);
        autoLending.setAmount(BigDecimal.TEN);
        autoLending.setTransaction(a);
        autoLending.setOrigin(LinkOrigin.AUTO);
        Lending userLending = new Lending();
        userLending.setId(UUID.randomUUID());
        userLending.setCounterparty(cp);
        userLending.setDirection(LendingDirection.lent);
        userLending.setAmount(BigDecimal.TEN);
        userLending.setTransaction(b);
        when(lendings.findWithCounterpartyByTransactionIdIn(any())).thenReturn(List.of(autoLending, userLending));

        Loan loan = new Loan();
        loan.setId(UUID.randomUUID());
        loan.setName("Home");
        LoanPayment payment = new LoanPayment();
        payment.setId(UUID.randomUUID());
        payment.setLoan(loan);
        payment.setInstallmentSeq(2);
        payment.setAmount(BigDecimal.ONE);
        payment.setTransaction(c);
        payment.setOrigin(LinkOrigin.AUTO);
        when(payments.findWithLoanByTransactionIdIn(any())).thenReturn(List.of(payment));

        Instrument instrument = new Instrument();
        instrument.setId(UUID.randomUUID());
        instrument.setSymbol("INFY");
        Holding holding = new Holding();
        holding.setInstrument(instrument);
        Dividend dividend = new Dividend();
        dividend.setId(UUID.randomUUID());
        dividend.setHolding(holding);
        dividend.setType(DividendType.dividend);
        dividend.setAmount(BigDecimal.TEN);
        dividend.setTransaction(d);
        dividend.setTransactionOrigin(LinkOrigin.AUTO);
        when(dividends.findWithHoldingByTransactionIdIn(any())).thenReturn(List.of(dividend));

        Map<UUID, List<ObligationRef>> refs = service.refsFor(List.of(a.getId(), b.getId(), c.getId(), d.getId()));

        assertThat(refs.get(a.getId()).get(0).origin()).isEqualTo(LinkOrigin.AUTO);
        assertThat(refs.get(b.getId()).get(0).origin()).isEqualTo(LinkOrigin.USER);
        assertThat(refs.get(c.getId()).get(0).origin()).isEqualTo(LinkOrigin.AUTO);
        assertThat(refs.get(d.getId()).get(0).origin()).isEqualTo(LinkOrigin.AUTO);
        verify(lendings, times(1)).findWithCounterpartyByTransactionIdIn(any());
        verify(payments, times(1)).findWithLoanByTransactionIdIn(any());
        verify(dividends, times(1)).findWithHoldingByTransactionIdIn(any());
    }

    @Test
    void aReferenceBuiltWithoutAnOriginIsAUserReference() {
        assertThat(new ObligationRef(ObligationKind.LOAN_EVENT, UUID.randomUUID(), null, "x", null).origin()).isEqualTo(LinkOrigin.USER);
    }
}
