package com.financeos.domain.report.datasource;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.financeos.core.exception.ValidationException;
import com.financeos.domain.report.datasource.DatasourceCatalog.FieldDef;
import com.financeos.domain.report.datasource.DatasourceCatalog.ReportCatalogView;
import com.financeos.domain.report.datasource.DatasourceCatalog.SingleDatasourceView;
import com.financeos.domain.report.datasource.impl.DividendsDatasource;
import com.financeos.domain.report.datasource.impl.FnoTradesDatasource;
import com.financeos.domain.report.datasource.impl.InvestmentTradesDatasource;
import com.financeos.domain.report.datasource.impl.LendingsDatasource;
import com.financeos.domain.report.datasource.impl.LoanPaymentsDatasource;
import com.financeos.domain.report.datasource.impl.LoanTaxSummaryDatasource;
import com.financeos.domain.report.datasource.impl.PortfolioValueDatasource;
import com.financeos.domain.report.datasource.impl.PositionsDatasource;
import com.financeos.domain.report.datasource.impl.RealizedLotsDatasource;
import com.financeos.domain.report.datasource.impl.RewardEarningsDatasource;
import com.financeos.domain.report.datasource.impl.TransactionsDatasource;
import com.financeos.domain.report.engine.DateRangeResolver;
import com.financeos.domain.report.engine.SqlPredicates;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

class DatasourceRegistryTest {

    private DatasourceRegistry registry;

    @BeforeEach
    void setUp() {
        DatasourceCatalog catalog = new DatasourceCatalog();
        DateRangeResolver dateRangeResolver = new DateRangeResolver(4);
        SqlPredicates sqlPredicates = new SqlPredicates(dateRangeResolver);

        PositionsDatasource positionsDs = mock(PositionsDatasource.class);
        when(positionsDs.name()).thenReturn("positions");
        when(positionsDs.label()).thenReturn("Positions");
        when(positionsDs.fields()).thenReturn(List.of());

        RealizedLotsDatasource realizedLotsDs = mock(RealizedLotsDatasource.class);
        when(realizedLotsDs.name()).thenReturn("realized_lots");
        when(realizedLotsDs.label()).thenReturn("Realized P&L");
        when(realizedLotsDs.fields()).thenReturn(List.of());

        PortfolioValueDatasource portfolioValDs = mock(PortfolioValueDatasource.class);
        when(portfolioValDs.name()).thenReturn("portfolio_value");
        when(portfolioValDs.label()).thenReturn("Portfolio Value");
        when(portfolioValDs.fields()).thenReturn(List.of());

        LoanPaymentsDatasource loanPaymentsDs = mock(LoanPaymentsDatasource.class);
        when(loanPaymentsDs.name()).thenReturn("loan_payments");
        when(loanPaymentsDs.label()).thenReturn("Loan Payments");
        when(loanPaymentsDs.fields()).thenReturn(List.of());

        LoanTaxSummaryDatasource loanTaxSummaryDs = mock(LoanTaxSummaryDatasource.class);
        when(loanTaxSummaryDs.name()).thenReturn("loan_tax_summary");
        when(loanTaxSummaryDs.label()).thenReturn("Loan Tax Summary");
        when(loanTaxSummaryDs.fields()).thenReturn(List.of());

        LendingsDatasource lendingsDs = mock(LendingsDatasource.class);
        when(lendingsDs.name()).thenReturn("lendings");
        when(lendingsDs.label()).thenReturn("Lendings");
        when(lendingsDs.fields()).thenReturn(List.of());

        RewardEarningsDatasource rewardEarningsDs = mock(RewardEarningsDatasource.class);
        when(rewardEarningsDs.name()).thenReturn("reward_earnings");
        when(rewardEarningsDs.label()).thenReturn("Reward Earnings");
        when(rewardEarningsDs.fields()).thenReturn(List.of());

        List<ReportDatasource> datasources = List.of(
                new TransactionsDatasource(sqlPredicates, dateRangeResolver),
                new InvestmentTradesDatasource(sqlPredicates, dateRangeResolver),
                new DividendsDatasource(sqlPredicates, dateRangeResolver),
                new FnoTradesDatasource(sqlPredicates, dateRangeResolver),
                positionsDs,
                realizedLotsDs,
                portfolioValDs,
                loanPaymentsDs,
                loanTaxSummaryDs,
                lendingsDs,
                rewardEarningsDs
        );

        registry = new DatasourceRegistry(datasources, catalog);
    }

    @Test
    void allElevenDatasourcesRegistered() {
        assertTrue(registry.isKnown("transactions"));
        assertTrue(registry.isKnown("investment_trades"));
        assertTrue(registry.isKnown("dividends"));
        assertTrue(registry.isKnown("fno_trades"));
        assertTrue(registry.isKnown("positions"));
        assertTrue(registry.isKnown("realized_lots"));
        assertTrue(registry.isKnown("portfolio_value"));
        assertTrue(registry.isKnown("loan_payments"));
        assertTrue(registry.isKnown("loan_tax_summary"));
        assertTrue(registry.isKnown("lendings"));
        assertTrue(registry.isKnown("reward_earnings"));

        assertNotNull(registry.byName("transactions"));
        assertNotNull(registry.byName("investment_trades"));
        assertNotNull(registry.byName("dividends"));
        assertNotNull(registry.byName("fno_trades"));
        assertNotNull(registry.byName("positions"));
        assertNotNull(registry.byName("realized_lots"));
        assertNotNull(registry.byName("portfolio_value"));
        assertNotNull(registry.byName("loan_payments"));
        assertNotNull(registry.byName("loan_tax_summary"));
        assertNotNull(registry.byName("lendings"));
        assertNotNull(registry.byName("reward_earnings"));
    }

    @Test
    void unknownDatasourceThrowsValidationException() {
        ValidationException ex = assertThrows(ValidationException.class, () -> registry.byName("non_existent"));
        assertTrue(ex.getMessage().contains("Unknown report datasource: non_existent"));
    }

    @Test
    void catalogViewShapeAndOrder() {
        ReportCatalogView view = registry.view();
        assertNotNull(view);
        assertNotNull(view.operators());

        List<SingleDatasourceView> dsViews = view.datasources();
        assertEquals(11, dsViews.size());
        assertEquals("transactions", dsViews.get(0).name());
        assertEquals("investment_trades", dsViews.get(1).name());
        assertEquals("dividends", dsViews.get(2).name());
        assertEquals("fno_trades", dsViews.get(3).name());
        assertEquals("positions", dsViews.get(4).name());
        assertEquals("realized_lots", dsViews.get(5).name());
        assertEquals("portfolio_value", dsViews.get(6).name());
        assertEquals("loan_payments", dsViews.get(7).name());
        assertEquals("loan_tax_summary", dsViews.get(8).name());
        assertEquals("lendings", dsViews.get(9).name());
        assertEquals("reward_earnings", dsViews.get(10).name());

        // Check format hints present on money fields
        SingleDatasourceView tradesView = dsViews.get(1);
        FieldDef tradeValueField = tradesView.fields().stream()
                .filter(f -> "tradeValue".equals(f.name()))
                .findFirst().orElseThrow();
        assertEquals("currency", tradeValueField.format());

        FieldDef quantityField = tradesView.fields().stream()
                .filter(f -> "quantity".equals(f.name()))
                .findFirst().orElseThrow();
        assertEquals("number", quantityField.format());

        // Check new transaction fields: mcc, channel, isEmi, isInternational, instantDiscount, convenienceFee
        SingleDatasourceView txView = dsViews.get(0);
        FieldDef mccField = txView.fields().stream().filter(f -> "mcc".equals(f.name())).findFirst().orElseThrow();
        assertEquals(FieldType.STRING, mccField.type());
        assertEquals(FieldRole.DIMENSION, mccField.role());

        FieldDef channelField = txView.fields().stream().filter(f -> "channel".equals(f.name())).findFirst().orElseThrow();
        assertEquals(FieldType.ENUM, channelField.type());
        assertEquals(FieldRole.DIMENSION, channelField.role());
        assertEquals(List.of("ONLINE", "POS", "UPI", "CONTACTLESS", "OTHER"), channelField.values());

        FieldDef isEmiField = txView.fields().stream().filter(f -> "isEmi".equals(f.name())).findFirst().orElseThrow();
        assertEquals(FieldType.BOOLEAN, isEmiField.type());
        assertEquals(FieldRole.FILTER, isEmiField.role());

        FieldDef isInternationalField = txView.fields().stream().filter(f -> "isInternational".equals(f.name())).findFirst().orElseThrow();
        assertEquals(FieldType.BOOLEAN, isInternationalField.type());
        assertEquals(FieldRole.FILTER, isInternationalField.role());

        FieldDef instantDiscountField = txView.fields().stream().filter(f -> "instantDiscount".equals(f.name())).findFirst().orElseThrow();
        assertEquals(FieldType.NUMBER, instantDiscountField.type());
        assertEquals(FieldRole.MEASURE, instantDiscountField.role());
        assertEquals("currency", instantDiscountField.format());

        FieldDef convenienceFeeField = txView.fields().stream().filter(f -> "convenienceFee".equals(f.name())).findFirst().orElseThrow();
        assertEquals(FieldType.NUMBER, convenienceFeeField.type());
        assertEquals(FieldRole.MEASURE, convenienceFeeField.role());
        assertEquals("currency", convenienceFeeField.format());
    }
}
