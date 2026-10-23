package com.financeos.domain.investment.sip;

import com.financeos.api.investment.dto.SipProgressDto;
import com.financeos.api.investment.dto.SipResponse;
import com.financeos.core.security.UserContext;
import com.financeos.core.time.AppTime;
import com.financeos.domain.account.Account;
import com.financeos.domain.account.AccountRepository;
import com.financeos.domain.account.AccountType;
import com.financeos.domain.holding.Holding;
import com.financeos.domain.holding.HoldingRepository;
import com.financeos.domain.instrument.AssetClassOverrideService;
import com.financeos.domain.instrument.Instrument;
import com.financeos.domain.instrument.InstrumentOverrides;
import com.financeos.domain.instrument.InstrumentRepository;
import com.financeos.domain.investment.InvestmentTransaction;
import com.financeos.domain.investment.InvestmentTransactionRepository;
import com.financeos.domain.investment.InvestmentTransactionType;
import com.financeos.domain.user.User;
import com.financeos.domain.user.UserRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.PageImpl;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The SIP list computes every SIP's progress from one read of the user's holdings and one of their
 * transactions (never a pair of queries per SIP), with the same numbers the single-SIP path gives.
 */
class SipServiceBatchTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 10, 9);
    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");

    private SipRepository sipRepository;
    private HoldingRepository holdingRepository;
    private InvestmentTransactionRepository transactionRepository;
    private SipService service;
    private User owner;

    @BeforeEach
    void setUp() {
        AppTime.useClock(Clock.fixed(TODAY.atStartOfDay(IST).toInstant(), IST));
        sipRepository = mock(SipRepository.class);
        holdingRepository = mock(HoldingRepository.class);
        transactionRepository = mock(InvestmentTransactionRepository.class);
        AssetClassOverrideService overrideService = mock(AssetClassOverrideService.class);
        when(overrideService.overridesForCurrentUser()).thenReturn(InstrumentOverrides.NONE);
        service = new SipService(sipRepository, mock(AccountRepository.class), mock(InstrumentRepository.class),
                holdingRepository, transactionRepository, mock(UserRepository.class), overrideService);
        owner = new User();
        owner.setId(UUID.randomUUID());
        UserContext.setCurrentUserId(owner.getId());
    }

    @AfterEach
    void tearDown() {
        AppTime.reset();
        UserContext.clear();
    }

    private Account broker(String name) {
        Account a = new Account();
        a.setId(UUID.randomUUID());
        a.setType(AccountType.broker);
        a.setName(name);
        return a;
    }

    private Instrument instrument(String name) {
        Instrument i = new Instrument();
        i.setId(UUID.randomUUID());
        i.setName(name);
        return i;
    }

    private Holding holding(Account broker, Instrument instrument) {
        Holding h = new Holding(broker, instrument, null);
        h.setId(UUID.randomUUID());
        h.setUser(owner);
        return h;
    }

    private Sip sip(Account broker, Instrument instrument, LocalDate start) {
        Sip s = new Sip();
        s.setId(UUID.randomUUID());
        s.setUser(owner);
        s.setBrokerAccount(broker);
        s.setInstrument(instrument);
        s.setAmount(new BigDecimal("1000"));
        s.setFrequency(SipFrequency.monthly);
        s.setDayOfMonth(5);
        s.setStartDate(start);
        s.setActive(true);
        return s;
    }

    private InvestmentTransaction txn(Holding h, InvestmentTransactionType type, String qty, String price, String charges,
                                      LocalDate date) {
        InvestmentTransaction t = new InvestmentTransaction();
        t.setId(UUID.randomUUID());
        t.setHolding(h);
        t.setType(type);
        t.setQuantity(new BigDecimal(qty));
        t.setPrice(new BigDecimal(price));
        t.setTotalCharges(new BigDecimal(charges));
        t.setTradeDate(date);
        return t;
    }

    @Test
    void listedProgressMatchesTheSingleSipPathWithAConstantNumberOfQueries() {
        Account zerodha = broker("Zerodha");
        Account groww = broker("Groww");
        Instrument nifty = instrument("Nifty fund");
        Instrument gold = instrument("Gold fund");
        Holding niftyAtZerodha = holding(zerodha, nifty);
        Holding goldAtGroww = holding(groww, gold);
        Holding niftyAtGroww = holding(groww, nifty);   // same instrument, another broker: not this SIP's
        Sip sipA = sip(zerodha, nifty, LocalDate.of(2026, 5, 5));
        Sip sipB = sip(groww, gold, LocalDate.of(2026, 7, 5));
        Sip sipNoHolding = sip(zerodha, gold, LocalDate.of(2026, 8, 5));   // nothing bought yet

        List<InvestmentTransaction> all = new ArrayList<>(List.of(
                txn(niftyAtZerodha, InvestmentTransactionType.buy, "10", "100", "5", LocalDate.of(2026, 5, 6)),
                txn(niftyAtZerodha, InvestmentTransactionType.buy, "5", "110", "0", LocalDate.of(2026, 6, 6)),
                txn(niftyAtZerodha, InvestmentTransactionType.sell, "3", "120", "1", LocalDate.of(2026, 6, 20)),
                txn(niftyAtZerodha, InvestmentTransactionType.buy, "2", "90", "0", LocalDate.of(2026, 1, 1)),   // before the SIP
                txn(goldAtGroww, InvestmentTransactionType.buy, "4", "50", "2", LocalDate.of(2026, 7, 7)),
                txn(goldAtGroww, InvestmentTransactionType.buy, "6", "55", "0", LocalDate.of(2026, 9, 8)),
                txn(niftyAtGroww, InvestmentTransactionType.buy, "99", "1", "0", LocalDate.of(2026, 6, 1))));

        // Batch path
        when(sipRepository.findFilteredSips(null, null, null)).thenReturn(List.of(sipA, sipB, sipNoHolding));
        when(holdingRepository.findAllWithDetailsOfUser(owner.getId()))
                .thenReturn(List.of(niftyAtZerodha, goldAtGroww, niftyAtGroww));
        when(transactionRepository.findByUser_IdOrderByTradeDateAscCreatedAtAsc(owner.getId())).thenReturn(all);

        List<SipResponse> listed = service.getSips(null, null, null);

        verify(holdingRepository, times(1)).findAllWithDetailsOfUser(owner.getId());
        verify(transactionRepository, times(1)).findByUser_IdOrderByTradeDateAscCreatedAtAsc(owner.getId());
        verify(holdingRepository, never()).findByBrokerAccountIdAndInstrumentId(any(), any());
        verify(transactionRepository, never()).findFilteredTransactions(any(), any(), any(), any(), any());

        // Per-SIP path (the single-SIP read), same data
        for (Sip s : List.of(sipA, sipB, sipNoHolding)) {
            when(sipRepository.findById(s.getId())).thenReturn(Optional.of(s));
        }
        mockPerSip(sipA, niftyAtZerodha, all);
        mockPerSip(sipB, goldAtGroww, all);
        when(holdingRepository.findByBrokerAccountIdAndInstrumentId(zerodha.getId(), gold.getId())).thenReturn(Optional.empty());

        assertEquals(3, listed.size());
        for (SipResponse r : listed) {
            assertEquals(service.getSip(r.id()).progress(), r.progress());
        }

        SipProgressDto a = listed.get(0).progress();
        assertEquals(6, a.expectedInstallments());     // May..Oct 2026
        assertEquals(2, a.executedInstallments());
        assertEquals(0, new BigDecimal("1555.00").compareTo(a.investedSoFar()));   // 10×100+5 + 5×110
        assertEquals(0, new BigDecimal("15").compareTo(a.unitsAccumulated()));
        SipProgressDto b = listed.get(1).progress();
        assertEquals(2, b.executedInstallments());
        assertEquals(0, new BigDecimal("532.00").compareTo(b.investedSoFar()));   // 4×50+2 + 6×55
        SipProgressDto none = listed.get(2).progress();
        assertEquals(0, none.executedInstallments());
        assertEquals(0, BigDecimal.ZERO.compareTo(none.investedSoFar()));
    }

    private void mockPerSip(Sip s, Holding h, List<InvestmentTransaction> all) {
        when(holdingRepository.findByBrokerAccountIdAndInstrumentId(s.getBrokerAccount().getId(), s.getInstrument().getId()))
                .thenReturn(Optional.of(h));
        List<InvestmentTransaction> own = all.stream().filter(t -> t.getHolding() == h).toList();
        when(transactionRepository.findFilteredTransactions(s.getBrokerAccount().getId(), s.getInstrument().getId(),
                null, null, org.springframework.data.domain.Pageable.unpaged())).thenReturn(new PageImpl<>(own));
    }
}
