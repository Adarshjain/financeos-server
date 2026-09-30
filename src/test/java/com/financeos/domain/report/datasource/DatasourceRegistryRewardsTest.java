package com.financeos.domain.report.datasource;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.financeos.domain.report.datasource.DatasourceCatalog.SingleDatasourceView;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

class DatasourceRegistryRewardsTest {

    private static ReportDatasource ds(String name) {
        ReportDatasource ds = mock(ReportDatasource.class);
        when(ds.name()).thenReturn(name);
        when(ds.label()).thenReturn(name);
        when(ds.fields()).thenReturn(List.of());
        return ds;
    }

    @Test
    void catalogViewListsTheThreeRewardDatasourcesLastInOrder() {
        // registered out of order on purpose: the view order comes from the registry, not registration
        List<ReportDatasource> all = List.of(
                ds("reward_caps"), ds("reward_milestones"), ds("reward_earnings"),
                ds("lendings"), ds("transactions"));

        DatasourceRegistry registry = new DatasourceRegistry(all, new DatasourceCatalog());

        List<String> names = registry.view().datasources().stream().map(SingleDatasourceView::name).toList();
        assertEquals(List.of("transactions", "lendings", "reward_earnings", "reward_milestones", "reward_caps"), names);
    }

    @Test
    void allThreeRewardDatasourcesAreKnown() {
        DatasourceRegistry registry = new DatasourceRegistry(
                List.of(ds("reward_earnings"), ds("reward_milestones"), ds("reward_caps")), new DatasourceCatalog());
        assertTrue(registry.isKnown("reward_earnings"));
        assertTrue(registry.isKnown("reward_milestones"));
        assertTrue(registry.isKnown("reward_caps"));
    }

    @Test
    void rewardDatasourcesFollowEveryOtherOrderedDatasourceAndPrecedeUnlistedOnes() {
        List<String> ordered = List.of("transactions", "investment_trades", "dividends", "fno_trades", "positions",
                "realized_lots", "portfolio_value", "loan_payments", "loan_tax_summary", "lendings",
                "reward_earnings", "reward_milestones", "reward_caps");
        List<ReportDatasource> all = new ArrayList<>();
        all.add(ds("zz_unlisted"));
        for (int i = ordered.size() - 1; i >= 0; i--) {
            all.add(ds(ordered.get(i)));
        }

        List<String> names = new DatasourceRegistry(all, new DatasourceCatalog()).view().datasources().stream()
                .map(SingleDatasourceView::name).toList();

        List<String> expected = new ArrayList<>(ordered);
        expected.add("zz_unlisted");
        assertEquals(expected, names);
        assertEquals("reward_caps", names.get(names.size() - 2));
    }
}
