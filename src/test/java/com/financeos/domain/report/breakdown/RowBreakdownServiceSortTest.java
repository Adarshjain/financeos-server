package com.financeos.domain.report.breakdown;

import com.financeos.core.exception.ValidationException;
import com.financeos.domain.report.datasource.DatasourceRegistry;
import com.financeos.domain.report.datasource.ReportDatasource;
import com.financeos.domain.report.definition.SortClause;
import com.financeos.domain.report.definition.SortDirection;
import com.financeos.domain.report.engine.ReportData;
import com.financeos.domain.report.engine.TableData;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/** The {@code sort} parameter of a breakdown section page: parsed like report tables', passed to the provider. */
class RowBreakdownServiceSortTest {

    private DatasourceRegistry registry;
    private RowBreakdownProvider provider;
    private RowBreakdownService service;

    @BeforeEach
    void setUp() {
        registry = mock(DatasourceRegistry.class);
        provider = mock(RowBreakdownProvider.class);
        when(provider.datasource()).thenReturn("net_worth");
        ReportDatasource ds = mock(ReportDatasource.class);
        when(ds.name()).thenReturn("net_worth");
        when(registry.byName("net_worth")).thenReturn(ds);
        service = new RowBreakdownService(registry, List.of(provider));
    }

    @Test
    void aSortIsParsedAndPassedWithThePaging() {
        ReportData table = new TableData("TABLE", "raw", List.of(), List.of(), new TableData.Page(1, 10, 0, 1));
        when(provider.section("r", "transactions", 1, 10, new SortClause("amount", SortDirection.DESC))).thenReturn(table);

        assertSame(table, service.section("net_worth", "r", "transactions", 1, 10, " amount , DESC "));
    }

    @Test
    void pagingIsClampedForASortedPageToo() {
        service.section("net_worth", "r", "entries", -3, 1000, "date,asc");

        verify(provider).section("r", "entries", 0, 200, new SortClause("date", SortDirection.ASC));
    }

    @Test
    void noOrABlankSortKeepsTheSectionsDefaultOrder() {
        service.section("net_worth", "r", "entries", null, null, null);
        service.section("net_worth", "r", "lots", null, null, "  ");

        verify(provider).section("r", "entries", 0, 25);
        verify(provider).section("r", "lots", 0, 25);
        verify(provider, never()).section(anyString(), anyString(), anyInt(), anyInt(), any());
    }

    @Test
    void aMalformedSortIs400BeforeAnythingIsLoaded() {
        for (String bad : List.of("amount", "amount,up", "a,asc,b", ",asc", "amount,")) {
            ValidationException e = assertThrows(ValidationException.class,
                    () -> service.section("net_worth", "r", "transactions", 0, 25, bad));
            assertEquals("Invalid sort '" + bad + "': expected one '<column>,asc' or '<column>,desc' clause", e.getMessage());
        }
        verifyNoInteractions(registry);
        verify(provider, never()).section(anyString(), anyString(), anyInt(), anyInt());
        verify(provider, never()).section(anyString(), anyString(), anyInt(), anyInt(), any());
    }
}
