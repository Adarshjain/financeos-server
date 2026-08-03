package com.financeos.domain.investment.charges;

import com.financeos.api.investment.dto.ItemizedChargesDto;
import com.financeos.domain.investment.InvestmentTransactionType;
import com.financeos.domain.investment.SettlementType;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;

import static org.junit.jupiter.api.Assertions.*;

class ChargeCalculatorTest {

    private final ChargeCalculator calculator = new ChargeCalculator();

    @Test
    void testCalculateGrowwChargesDeliveryBuy() {
        ItemizedChargesDto charges = calculator.calculateGrowwCharges(
                InvestmentTransactionType.buy,
                SettlementType.delivery,
                new BigDecimal("100"),
                new BigDecimal("500"), // trade value = 50,000
                LocalDate.of(2023, 5, 10),
                "NSE"
        );

        assertNotNull(charges);
        // Brokerage: min(0.1% * 50000, 20) = min(50, 20) = 20.00
        assertEquals(new BigDecimal("20.0000"), charges.brokerage());
        // STT: 0.1% * 50000 = 50.00
        assertEquals(new BigDecimal("50.0000"), charges.stt());
        // Stamp duty: 0.015% * 50000 = 7.50
        assertEquals(new BigDecimal("7.5000"), charges.stampDuty());
        // DP charges: 0 for buy
        assertEquals(BigDecimal.ZERO, charges.dpCharges());
    }

    @Test
    void testCalculateGrowwChargesIntradaySell() {
        ItemizedChargesDto charges = calculator.calculateGrowwCharges(
                InvestmentTransactionType.sell,
                SettlementType.intraday,
                new BigDecimal("50"),
                new BigDecimal("200"), // trade value = 10,000
                LocalDate.of(2023, 5, 10),
                "NSE"
        );

        assertNotNull(charges);
        // Brokerage: min(0.1% * 10000, 20) = 10.00
        assertEquals(new BigDecimal("10.0000"), charges.brokerage());
        // STT: 0.025% * 10000 = 2.50
        assertEquals(new BigDecimal("2.5000"), charges.stt());
        // Stamp duty: 0 for sell
        assertEquals(BigDecimal.ZERO, charges.stampDuty());
        // DP charges: 0 for intraday
        assertEquals(BigDecimal.ZERO, charges.dpCharges());
    }
}
