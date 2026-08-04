package com.financeos.domain.holding;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.financeos.domain.account.Account;
import com.financeos.domain.instrument.Instrument;
import com.financeos.domain.investment.HoldingPosition;
import com.financeos.domain.investment.InvestmentService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

class HoldingValuationServiceTest {

    private HoldingRepository holdingRepository;
    private InvestmentService investmentService;
    private HoldingValuationService holdingValuationService;

    private UUID brokerAccountId;

    @BeforeEach
    void setUp() {
        holdingRepository = mock(HoldingRepository.class);
        investmentService = mock(InvestmentService.class);
        holdingValuationService = new HoldingValuationService(holdingRepository, investmentService);
        brokerAccountId = UUID.randomUUID();
    }

    @Test
    void testGetBrokerMarketValueUsesCorporateActionAdjustedPosition() {
        Account brokerAccount = new Account();
        brokerAccount.setId(brokerAccountId);

        Instrument instrument = new Instrument();
        instrument.setId(UUID.randomUUID());

        Holding holding = new Holding(brokerAccount, instrument, null);
        holding.setId(UUID.randomUUID());

        // 100 shares originally bought, but after a 1:2 split the CA-adjusted openQty is 200 @ LTP 50.00
        HoldingPosition mockPosition = new HoldingPosition(
                holding,
                new BigDecimal("200.00000000"), // openQty after split
                new BigDecimal("50.0000"),       // avgCost
                new BigDecimal("10000.0000"),    // openCost
                new BigDecimal("50.0000"),       // priceClose (LTP)
                null,
                null,
                new BigDecimal("10000.0000"),    // currentValue = 200 * 50 = 10000.00
                new BigDecimal("0.0000"),
                new BigDecimal("0.00"),
                new BigDecimal("0.0000"),
                new BigDecimal("0.0000"),
                new BigDecimal("0.0000"),
                new BigDecimal("0.00"),
                null,
                null
        );

        when(holdingRepository.findByBrokerAccountId(brokerAccountId))
                .thenReturn(List.of(holding));
        when(investmentService.calculateHoldingPosition(holding))
                .thenReturn(mockPosition);

        BigDecimal marketValue = holdingValuationService.getBrokerMarketValue(brokerAccountId);

        // With CA-adjusted position (200 @ 50), market value is 10000.00 (not 100 * 50 = 5000.00)
        assertEquals(0, marketValue.compareTo(new BigDecimal("10000.0000")));
    }
}
