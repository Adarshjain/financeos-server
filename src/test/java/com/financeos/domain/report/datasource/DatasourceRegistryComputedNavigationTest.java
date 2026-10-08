package com.financeos.domain.report.datasource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.financeos.domain.report.datasource.DatasourceCatalog.SingleDatasourceView;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/** net_worth, obligations and attention are listed after the reward datasources, in that order. */
class DatasourceRegistryComputedNavigationTest {

    private static ReportDatasource ds(String name) {
        ReportDatasource ds = mock(ReportDatasource.class);
        when(ds.name()).thenReturn(name);
        when(ds.label()).thenReturn(name);
        when(ds.fields()).thenReturn(List.of());
        return ds;
    }

    @Test
    void theThreeNewDatasourcesAreKnownAndResolvable() {
        ReportDatasource netWorth = ds("net_worth");
        ReportDatasource obligations = ds("obligations");
        ReportDatasource attention = ds("attention");
        DatasourceRegistry registry = new DatasourceRegistry(List.of(netWorth, obligations, attention), new DatasourceCatalog());
        assertTrue(registry.isKnown("net_worth"));
        assertTrue(registry.isKnown("obligations"));
        assertTrue(registry.isKnown("attention"));
        assertSame(netWorth, registry.byName("net_worth"));
        assertSame(obligations, registry.byName("obligations"));
        assertSame(attention, registry.byName("attention"));
    }

    @Test
    void catalogOrderPutsNetWorthObligationsAttentionAfterRewardCapsAndBeforeUnlisted() {
        List<String> ordered = List.of("transactions", "investment_trades", "dividends", "fno_trades", "positions",
                "realized_lots", "portfolio_value", "loan_payments", "loan_tax_summary", "lendings",
                "reward_earnings", "reward_milestones", "reward_caps", "net_worth", "obligations", "attention");
        List<ReportDatasource> all = new ArrayList<>();
        all.add(ds("zz_unlisted"));
        for (int i = ordered.size() - 1; i >= 0; i--) {
            all.add(ds(ordered.get(i))); // registered in reverse on purpose
        }

        List<String> names = new DatasourceRegistry(all, new DatasourceCatalog()).view().datasources().stream()
                .map(SingleDatasourceView::name).toList();

        List<String> expected = new ArrayList<>(ordered);
        expected.add("zz_unlisted");
        assertEquals(expected, names);
    }

    @Test
    void onlyRegisteredDatasourcesAppearInTheView() {
        List<String> names = new DatasourceRegistry(List.of(ds("attention"), ds("transactions"), ds("net_worth")),
                new DatasourceCatalog()).view().datasources().stream().map(SingleDatasourceView::name).toList();
        assertEquals(List.of("transactions", "net_worth", "attention"), names);
    }
}
