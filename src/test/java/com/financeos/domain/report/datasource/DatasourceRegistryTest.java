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
import com.financeos.domain.report.datasource.impl.PortfolioValueDatasource;
import com.financeos.domain.report.datasource.impl.PositionsDatasource;
import com.financeos.domain.report.datasource.impl.RealizedLotsDatasource;
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

        List<ReportDatasource> datasources = List.of(
                new TransactionsDatasource(sqlPredicates, dateRangeResolver),
                new InvestmentTradesDatasource(sqlPredicates, dateRangeResolver),
                new DividendsDatasource(sqlPredicates, dateRangeResolver),
                new FnoTradesDatasource(sqlPredicates, dateRangeResolver),
                positionsDs,
                realizedLotsDs,
                portfolioValDs
        );

        registry = new DatasourceRegistry(datasources, catalog);
    }

    @Test
    void allSevenDatasourcesRegistered() {
        assertTrue(registry.isKnown("transactions"));
        assertTrue(registry.isKnown("investment_trades"));
        assertTrue(registry.isKnown("dividends"));
        assertTrue(registry.isKnown("fno_trades"));
        assertTrue(registry.isKnown("positions"));
        assertTrue(registry.isKnown("realized_lots"));
        assertTrue(registry.isKnown("portfolio_value"));

        assertNotNull(registry.byName("transactions"));
        assertNotNull(registry.byName("investment_trades"));
        assertNotNull(registry.byName("dividends"));
        assertNotNull(registry.byName("fno_trades"));
        assertNotNull(registry.byName("positions"));
        assertNotNull(registry.byName("realized_lots"));
        assertNotNull(registry.byName("portfolio_value"));
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
        assertEquals(7, dsViews.size());
        assertEquals("transactions", dsViews.get(0).name());
        assertEquals("investment_trades", dsViews.get(1).name());
        assertEquals("dividends", dsViews.get(2).name());
        assertEquals("fno_trades", dsViews.get(3).name());
        assertEquals("positions", dsViews.get(4).name());
        assertEquals("realized_lots", dsViews.get(5).name());
        assertEquals("portfolio_value", dsViews.get(6).name());

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
    }
}
