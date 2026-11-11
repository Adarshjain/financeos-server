package com.financeos.domain.investment;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.financeos.api.investment.dto.PositionDto;
import com.financeos.domain.account.Account;
import com.financeos.domain.holding.Holding;
import com.financeos.domain.holding.HoldingRepository;
import com.financeos.domain.instrument.Instrument;
import com.financeos.domain.instrument.InstrumentPrice;
import com.financeos.domain.instrument.InstrumentPriceRepository;
import com.financeos.domain.instrument.InstrumentType;
import com.financeos.domain.instrument.corporateaction.CorporateAction;
import com.financeos.domain.instrument.corporateaction.CorporateActionRepository;
import com.financeos.domain.instrument.corporateaction.CorporateActionType;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** A delisted holding stays open at zero value; sells beyond the lots are recorded on the position. */
class DelistedAndOversoldPositionTest {

    private InvestmentTransactionRepository transactionRepository;
    private CorporateActionRepository corporateActionRepository;
    private InstrumentPriceRepository priceRepository;
    private InvestmentService investmentService;
    private Instrument instrument;
    private Holding holding;

    @BeforeEach
    void setUp() {
        transactionRepository = mock(InvestmentTransactionRepository.class);
        corporateActionRepository = mock(CorporateActionRepository.class);
        priceRepository = mock(InstrumentPriceRepository.class);
        investmentService = new InvestmentService(
                transactionRepository,
                mock(HoldingRepository.class),
                mock(com.financeos.domain.account.AccountRepository.class),
                mock(com.financeos.domain.instrument.InstrumentRepository.class),
                priceRepository,
                mock(com.financeos.domain.user.UserRepository.class),
                corporateActionRepository,
                mock(com.financeos.domain.investment.dividend.DividendRepository.class),
                mock(com.financeos.domain.investment.TradeSettlementClassificationRepository.class),
                mock(org.springframework.context.ApplicationEventPublisher.class),
                mock(com.financeos.domain.investment.fno.FnoTradeRepository.class));
        Account broker = new Account();
        broker.setId(UUID.randomUUID());
        instrument = new Instrument();
        instrument.setId(UUID.randomUUID());
        instrument.setName("Acme Ltd");
        instrument.setSymbol("ACME");
        instrument.setType(InstrumentType.stock);
        holding = new Holding(broker, instrument, null);
        holding.setId(UUID.randomUUID());
    }

    private static InvestmentTransaction trade(InvestmentTransactionType type, String qty, String price, LocalDate date) {
        InvestmentTransaction t = new InvestmentTransaction();
        t.setType(type);
        t.setQuantity(new BigDecimal(qty));
        t.setPrice(new BigDecimal(price));
        t.setTradeDate(date);
        return t;
    }

    private CorporateAction delisted(LocalDate exDate) {
        CorporateAction ca = new CorporateAction();
        ca.setId(UUID.randomUUID());
        ca.setInstrument(instrument);
        ca.setType(CorporateActionType.delisted);
        ca.setRatioFrom(1);
        ca.setRatioTo(1);
        ca.setExDate(exDate);
        return ca;
    }

    private void given(List<InvestmentTransaction> txns, List<CorporateAction> actions, String price) {
        when(transactionRepository.findByHoldingIdOrderByTradeDateAscCreatedAtAsc(holding.getId())).thenReturn(txns);
        when(corporateActionRepository.findByUser_IdAndInstrument_IdOrderByExDateAsc(null, instrument.getId())).thenReturn(actions);
        when(corporateActionRepository.findByUser_IdAndTargetInstrument_IdOrderByExDateAsc(null, instrument.getId())).thenReturn(List.of());
        InstrumentPrice p = new InstrumentPrice();
        p.setClose(new BigDecimal(price));
        p.setAsOf(LocalDate.of(2024, 5, 30));
        when(priceRepository.findLatestVisible(eq(instrument.getId()), any())).thenReturn(price == null ? List.of() : List.of(p));
    }

    @Test
    void aDelistedHoldingStaysOpenAtZeroValueWithAnUnrealisedLossAndNoRealisedPnl() {
        given(List.of(trade(InvestmentTransactionType.buy, "100", "100", LocalDate.of(2024, 1, 1))),
                List.of(delisted(LocalDate.of(2024, 6, 1))), "50");

        HoldingPosition pos = investmentService.calculateHoldingPosition(holding);

        assertTrue(pos.delisted());
        assertEquals(0, new BigDecimal("100").compareTo(pos.openQty()));
        assertEquals(0, new BigDecimal("10000").compareTo(pos.openCost()));
        assertEquals(0, BigDecimal.ZERO.compareTo(pos.latestPrice()));
        assertEquals(0, BigDecimal.ZERO.compareTo(pos.currentValue()));
        assertEquals(0, new BigDecimal("-10000").compareTo(pos.unrealized()));
        assertEquals(0, BigDecimal.ZERO.compareTo(pos.realized()));
        assertEquals(0, BigDecimal.ZERO.compareTo(pos.intradayRealized()));
        PositionDto dto = pos.toPositionDto();
        assertTrue(dto.delisted());
        assertFalse(dto.notTrading());
        assertNotNull(dto.lastPrice(), "a delisted holding has a price (zero), so it is not 'no price'");
        assertEquals(0, BigDecimal.ZERO.compareTo(dto.lastPrice()));
    }

    @Test
    void aHoldingWithoutADelistingIsUnchanged() {
        given(List.of(trade(InvestmentTransactionType.buy, "100", "100", LocalDate.of(2024, 1, 1))), List.of(), "50");

        HoldingPosition pos = investmentService.calculateHoldingPosition(holding);

        assertFalse(pos.delisted());
        assertEquals(0, new BigDecimal("50").compareTo(pos.latestPrice()));
        assertEquals(0, new BigDecimal("5000").compareTo(pos.currentValue().setScale(0)));
        assertEquals(0, new BigDecimal("-5000").compareTo(pos.unrealized().setScale(0)));
        assertNull(pos.oversoldQty());
        assertNull(pos.oversoldDate());
        assertFalse(pos.toPositionDto().delisted());
    }

    @Test
    void aDelistingStillInTheFutureChangesNothing() {
        given(List.of(trade(InvestmentTransactionType.buy, "100", "100", LocalDate.of(2024, 1, 1))),
                List.of(delisted(LocalDate.of(2099, 1, 1))), "50");

        HoldingPosition pos = investmentService.calculateHoldingPosition(holding);

        assertFalse(pos.delisted());
        assertEquals(0, new BigDecimal("50").compareTo(pos.latestPrice()));
    }

    @Test
    void aStockTheFeedNoLongerQuotesIsNotTradingUnlessItIsDelisted() {
        instrument.setPriceStatus("not_trading");
        instrument.setPriceStatusAt(Instant.now());
        given(List.of(trade(InvestmentTransactionType.buy, "100", "100", LocalDate.of(2024, 1, 1))), List.of(), "50");
        assertTrue(investmentService.calculateHoldingPosition(holding).toPositionDto().notTrading());

        given(List.of(trade(InvestmentTransactionType.buy, "100", "100", LocalDate.of(2024, 1, 1))),
                List.of(delisted(LocalDate.of(2024, 6, 1))), "50");
        PositionDto dto = investmentService.calculateHoldingPosition(holding).toPositionDto();
        assertTrue(dto.delisted());
        assertFalse(dto.notTrading());
    }

    @Test
    void aSellBeyondTheLotsRecordsTheExcessAndTheFirstDate() {
        given(List.of(
                trade(InvestmentTransactionType.buy, "10", "100", LocalDate.of(2024, 1, 1)),
                trade(InvestmentTransactionType.sell, "15", "120", LocalDate.of(2024, 2, 1)),
                trade(InvestmentTransactionType.sell, "3", "120", LocalDate.of(2024, 3, 1))), List.of(), "50");

        HoldingPosition pos = investmentService.calculateHoldingPosition(holding);

        assertEquals(0, new BigDecimal("8").compareTo(pos.oversoldQty()));
        assertEquals(LocalDate.of(2024, 2, 1), pos.oversoldDate());
        assertEquals(0, BigDecimal.ZERO.compareTo(pos.openQty()));
    }

    @Test
    void aSellWithinTheLotsRecordsNothing() {
        given(List.of(
                trade(InvestmentTransactionType.buy, "10", "100", LocalDate.of(2024, 1, 1)),
                trade(InvestmentTransactionType.sell, "10", "120", LocalDate.of(2024, 2, 1))), List.of(), "50");

        HoldingPosition pos = investmentService.calculateHoldingPosition(holding);

        assertNull(pos.oversoldQty());
        assertNull(pos.oversoldDate());
    }
}
