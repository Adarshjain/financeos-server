package com.financeos.domain.report.breakdown;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.when;

import com.financeos.core.exception.ValidationException;
import com.financeos.domain.report.datasource.DatasourceRegistry;
import com.financeos.domain.report.datasource.ReportDatasource;
import com.financeos.domain.report.engine.ReportData;
import com.financeos.domain.report.engine.TableData;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class RowBreakdownServiceTest {

    private DatasourceRegistry registry;
    private RowBreakdownProvider provider;
    private RowBreakdownService service;

    @BeforeEach
    void setUp() {
        registry = mock(DatasourceRegistry.class);
        provider = mock(RowBreakdownProvider.class);
        when(provider.datasource()).thenReturn("net_worth");
        service = new RowBreakdownService(registry, List.of(provider));
        datasource("net_worth", "Net worth");
        datasource("transactions", "Transactions");
        when(registry.byName("nope")).thenThrow(new ValidationException("Unknown report datasource: nope"));
    }

    @Test
    void supportsOnlyDatasourcesWithAProvider() {
        assertTrue(service.supports("net_worth"));
        assertFalse(service.supports("transactions"));
        assertFalse(service.supports(null));
    }

    @Test
    void breakdownGoesToTheDatasourcesProvider() {
        RowBreakdownResponse response = new RowBreakdownResponse("net_worth", "r", "t", null, null, BigDecimal.ONE, "x",
                "currency", LocalDate.of(2026, 10, 8), List.of(), List.of(), List.of());
        when(provider.breakdown("r", 40)).thenReturn(response);

        assertSame(response, service.breakdown("net_worth", "r", 40));
    }

    @Test
    void sizeDefaultsTo25AndIsClampedTo1Through200() {
        service.breakdown("net_worth", "a", null);
        service.breakdown("net_worth", "b", 500);
        service.breakdown("net_worth", "c", 0);
        service.breakdown("net_worth", "d", 200);

        verify(provider).breakdown("a", 25);
        verify(provider).breakdown("b", 200);
        verify(provider).breakdown("c", 1);
        verify(provider).breakdown("d", 200);
    }

    @Test
    void sectionGoesToTheProviderWithPageDefaultingToZeroAndNeverNegative() {
        ReportData table = new TableData("TABLE", "raw", List.of(), List.of(), new TableData.Page(3, 10, 0, 1));
        when(provider.section("r", "transactions", 3, 10)).thenReturn(table);

        assertSame(table, service.section("net_worth", "r", "transactions", 3, 10));

        service.section("net_worth", "r", "entries", null, null);
        service.section("net_worth", "r", "holdings", -2, 1000);
        verify(provider).section("r", "entries", 0, 25);
        verify(provider).section("r", "holdings", 0, 200);
    }

    @Test
    void datasourceWithoutABreakdownIsRejectedByItsLabel() {
        ValidationException e = assertThrows(ValidationException.class,
                () -> service.breakdown("transactions", "r", null));
        assertEquals("No breakdown for Transactions", e.getMessage());
        assertThrows(ValidationException.class, () -> service.section("transactions", "r", "s", 0, 25));
    }

    @Test
    void unknownDatasourceIsTheRegistrysError() {
        ValidationException e = assertThrows(ValidationException.class, () -> service.breakdown("nope", "r", null));
        assertEquals("Unknown report datasource: nope", e.getMessage());
        verify(provider, never()).breakdown(any(), anyInt());
    }

    private void datasource(String name, String label) {
        ReportDatasource ds = mock(ReportDatasource.class);
        when(ds.name()).thenReturn(name);
        when(ds.label()).thenReturn(label);
        when(registry.byName(name)).thenReturn(ds);
    }
}
