package com.financeos.domain.investment.dividend;

import com.financeos.api.investment.dto.DividendResponse;
import com.financeos.core.time.AppTime;
import com.financeos.domain.account.Account;
import com.financeos.domain.account.AccountType;
import com.financeos.domain.holding.Holding;
import com.financeos.domain.holding.HoldingRepository;
import com.financeos.domain.instrument.Instrument;
import com.financeos.domain.instrument.price.YahooDividendEventsClient;
import com.financeos.domain.investment.InvestmentService;
import com.financeos.domain.investment.InvestmentTransactionRepository;
import com.financeos.domain.transaction.Transaction;
import com.financeos.domain.transaction.TransactionRepository;
import com.financeos.domain.transaction.TransactionType;
import com.financeos.domain.user.UserRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.*;

/** The list endpoint's receipt filter: thresholds bound per source, statuses derived with bank coverage. */
class DividendServiceReceiptFilterTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 10, 7);

    private DividendRepository dividendRepository;
    private TransactionRepository transactionRepository;
    private DividendService dividendService;

    @BeforeEach
    void setUp() {
        dividendRepository = mock(DividendRepository.class);
        transactionRepository = mock(TransactionRepository.class);
        dividendService = new DividendService(
                dividendRepository, mock(HoldingRepository.class), mock(UserRepository.class),
                mock(InvestmentService.class), mock(YahooDividendEventsClient.class), mock(InvestmentTransactionRepository.class),
                new DividendReceiptStatusResolver(transactionRepository));
        AppTime.useClock(Clock.fixed(TODAY.atStartOfDay(AppTime.zone()).toInstant(), AppTime.zone()));
    }

    @AfterEach
    void tearDown() {
        AppTime.reset();
    }

    private Dividend dividend(String source, LocalDate payDate, Transaction linked) {
        Account broker = new Account();
        broker.setId(UUID.randomUUID());
        broker.setName("Zerodha");
        Instrument inst = new Instrument();
        inst.setId(UUID.randomUUID());
        inst.setName("ITC Limited");
        inst.setSymbol("ITC");
        Holding h = new Holding(broker, inst, null);
        h.setId(UUID.randomUUID());
        Dividend d = new Dividend();
        d.setId(UUID.randomUUID());
        d.setHolding(h);
        d.setType(DividendType.dividend);
        d.setAmount(new BigDecimal("100"));
        d.setSource(source);
        d.setPayDate(payDate);
        d.setTransaction(linked);
        return d;
    }

    @Test
    void receiptFilter_bindsNameAndPerSourceThresholdsFromTodayAndCoverage() {
        when(transactionRepository.findMaxDateByAccountType(AccountType.bank_account)).thenReturn(TODAY.minusDays(3));
        when(dividendRepository.findFilteredDividends(any(), any(), any(), any(), any(), any(), any(),
                any(), any(), any(), any(), any(), any(), any(Pageable.class))).thenReturn(Page.empty());
        Pageable pageable = PageRequest.of(0, 25);

        dividendService.getDividends(null, null, null, null, null, null, DividendReceiptStatus.overdue, pageable);

        // cutoff = min(today, coverage + 1) = today − 2
        verify(dividendRepository).findFilteredDividends(isNull(), isNull(), isNull(), isNull(), isNull(), isNull(),
                eq("overdue"),
                eq(TODAY.minusDays(60)), eq(TODAY.minusDays(5)), eq(TODAY.minusDays(10)),
                eq(TODAY.minusDays(62)), eq(TODAY.minusDays(7)), eq(TODAY.minusDays(12)),
                eq(pageable));
    }

    @Test
    void noReceiptFilter_bindsNullReceiptButStillBindsThresholds() {
        when(transactionRepository.findMaxDateByAccountType(AccountType.bank_account)).thenReturn(null);
        when(dividendRepository.findFilteredDividends(any(), any(), any(), any(), any(), any(), any(),
                any(), any(), any(), any(), any(), any(), any(Pageable.class))).thenReturn(Page.empty());

        dividendService.getDividends(null, null, null, null, null, null, null, PageRequest.of(0, 25));

        verify(dividendRepository).findFilteredDividends(isNull(), isNull(), isNull(), isNull(), isNull(), isNull(),
                isNull(),
                eq(TODAY.minusDays(60)), eq(TODAY.minusDays(5)), eq(TODAY.minusDays(10)),
                eq(DividendReceiptWindows.NO_COVERAGE.minusDays(60)), eq(DividendReceiptWindows.NO_COVERAGE.minusDays(5)),
                eq(DividendReceiptWindows.NO_COVERAGE.minusDays(10)),
                any(Pageable.class));
    }

    @Test
    void listRows_carryDerivedStatusAndLinkedTransaction() {
        when(transactionRepository.findMaxDateByAccountType(AccountType.bank_account)).thenReturn(TODAY);
        Account bank = new Account();
        bank.setId(UUID.randomUUID());
        bank.setName("HDFC Savings");
        Transaction credit = new Transaction();
        credit.setId(UUID.randomUUID());
        credit.setAccount(bank);
        credit.setType(TransactionType.CREDIT);
        credit.setAmount(new BigDecimal("90"));
        credit.setDate(TODAY.minusDays(1));
        credit.setDescription("ITC DIV");

        Dividend received = dividend("manual", TODAY.minusDays(1), credit);
        Dividend overdue = dividend("manual", TODAY.minusDays(40), null);
        Dividend awaiting = dividend("manual", TODAY, null);
        Dividend unverifiable = dividend("manual", TODAY.minusDays(40), null);
        unverifiable.setReceiptStatus(null);
        when(dividendRepository.findFilteredDividends(any(), any(), any(), any(), any(), any(), any(),
                any(), any(), any(), any(), any(), any(), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(received, overdue, awaiting)));

        List<DividendResponse> rows = dividendService.getDividends(null, null, null, null, null, null, null, PageRequest.of(0, 25)).getContent();

        assertEquals(DividendReceiptStatus.received, rows.get(0).receiptStatus());
        assertEquals("HDFC Savings", rows.get(0).transaction().accountName());
        assertEquals(0, rows.get(0).transaction().signedAmount().compareTo(new BigDecimal("90")));
        assertEquals(DividendReceiptStatus.overdue, rows.get(1).receiptStatus());
        assertNull(rows.get(1).transaction());
        assertEquals(DividendReceiptStatus.awaiting, rows.get(2).receiptStatus());
    }

    @Test
    void listRows_areUnverifiableBeyondBankCoverage() {
        when(transactionRepository.findMaxDateByAccountType(AccountType.bank_account)).thenReturn(TODAY.minusDays(100));
        when(dividendRepository.findFilteredDividends(any(), any(), any(), any(), any(), any(), any(),
                any(), any(), any(), any(), any(), any(), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(dividend("manual", TODAY.minusDays(40), null))));

        List<DividendResponse> rows = dividendService.getDividends(null, null, null, null, null, null, null, PageRequest.of(0, 25)).getContent();

        assertEquals(DividendReceiptStatus.unverifiable, rows.get(0).receiptStatus());
    }
}
