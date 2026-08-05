package com.financeos.domain.investment;

import com.financeos.api.investment.dto.SummaryResponse;
import com.financeos.domain.holding.HoldingRepository;
import com.financeos.domain.investment.dividend.DividendRepository;
import com.financeos.domain.investment.fno.FnoTradeRepository;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class InvestmentServiceFnoTest {

    @Test
    void testGetSummaryIncludesFnoRealizedPnlFromFnoTradeRepository() {
        HoldingRepository holdingRepo = mock(HoldingRepository.class);
        FnoTradeRepository fnoRepo = mock(FnoTradeRepository.class);
        DividendRepository divRepo = mock(DividendRepository.class);

        when(holdingRepo.findAll()).thenReturn(List.of());
        when(fnoRepo.sumRealizedPnl()).thenReturn(new BigDecimal("12500.50"));
        when(divRepo.sumTotalUserDividends()).thenReturn(BigDecimal.ZERO);

        InvestmentService investmentService = new InvestmentService(
                null,
                holdingRepo,
                null,
                null,
                null,
                null,
                null,
                divRepo,
                null,
                null,
                fnoRepo
        );

        SummaryResponse summary = investmentService.getSummary();

        assertNotNull(summary);
        assertEquals(new BigDecimal("12500.50"), summary.totalFnoRealized());
    }
}
