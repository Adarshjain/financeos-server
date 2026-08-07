package com.financeos.domain.investment.dividend;

import com.financeos.api.investment.dto.DividendSummaryResponse;
import com.financeos.domain.holding.HoldingRepository;
import com.financeos.domain.instrument.price.YahooDividendEventsClient;
import com.financeos.domain.investment.InvestmentService;
import com.financeos.domain.investment.InvestmentTransactionRepository;
import com.financeos.domain.user.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

class DividendFySummaryTest {

    private DividendRepository dividendRepository;
    private HoldingRepository holdingRepository;
    private UserRepository userRepository;
    private InvestmentService investmentService;
    private YahooDividendEventsClient yahooClient;
    private InvestmentTransactionRepository transactionRepository;
    private DividendService dividendService;

    @BeforeEach
    void setUp() {
        dividendRepository = Mockito.mock(DividendRepository.class);
        holdingRepository = Mockito.mock(HoldingRepository.class);
        userRepository = Mockito.mock(UserRepository.class);
        investmentService = Mockito.mock(InvestmentService.class);
        yahooClient = Mockito.mock(YahooDividendEventsClient.class);
        transactionRepository = Mockito.mock(InvestmentTransactionRepository.class);

        dividendService = new DividendService(
                dividendRepository, holdingRepository, userRepository,
                investmentService, yahooClient, transactionRepository
        );
    }

    @Test
    void testFyBucketingAndNetCalculation() {
        // Given dividend rows spanning two Indian FYs:
        // FY 2024-25: 2025-03-31, amount 1000, tds 100
        // FY 2025-26: 2025-04-01, amount 2000, tds null
        // FY 2025-26: 2025-08-15, amount 500, tds 50
        List<Object[]> rows = List.of(
                new Object[]{LocalDate.of(2025, 3, 31), new BigDecimal("1000.00"), new BigDecimal("100.00")},
                new Object[]{LocalDate.of(2025, 4, 1), new BigDecimal("2000.00"), null},
                new Object[]{LocalDate.of(2025, 8, 15), new BigDecimal("500.00"), new BigDecimal("50.00")}
        );

        when(dividendRepository.findDividendRowsForSummary(any(), any(), any(), any())).thenReturn(rows);

        DividendSummaryResponse summary = dividendService.getSummary(null, null, null, null);

        assertNotNull(summary);
        assertEquals(new BigDecimal("3500.00"), summary.totalAmount());
        assertEquals(new BigDecimal("150.00"), summary.totalTds());
        assertEquals(new BigDecimal("3350.00"), summary.totalNet());
        assertEquals(3, summary.totalCount());

        List<DividendSummaryResponse.FyBucket> buckets = summary.buckets();
        assertEquals(2, buckets.size());

        // First bucket should be newest FY (FY 2025-26)
        DividendSummaryResponse.FyBucket fy2526 = buckets.get(0);
        assertEquals("FY 2025-26", fy2526.label());
        assertEquals(LocalDate.of(2025, 4, 1), fy2526.fromDate());
        assertEquals(LocalDate.of(2026, 3, 31), fy2526.toDate());
        assertEquals(new BigDecimal("2500.00"), fy2526.amount());
        assertEquals(new BigDecimal("50.00"), fy2526.tds());
        assertEquals(new BigDecimal("2450.00"), fy2526.net());
        assertEquals(2, fy2526.count());

        // Second bucket (FY 2024-25)
        DividendSummaryResponse.FyBucket fy2425 = buckets.get(1);
        assertEquals("FY 2024-25", fy2425.label());
        assertEquals(LocalDate.of(2024, 4, 1), fy2425.fromDate());
        assertEquals(LocalDate.of(2025, 3, 31), fy2425.toDate());
        assertEquals(new BigDecimal("1000.00"), fy2425.amount());
        assertEquals(new BigDecimal("100.00"), fy2425.tds());
        assertEquals(new BigDecimal("900.00"), fy2425.net());
        assertEquals(1, fy2425.count());
    }
}
