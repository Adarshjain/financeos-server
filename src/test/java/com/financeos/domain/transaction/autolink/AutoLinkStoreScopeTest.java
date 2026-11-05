package com.financeos.domain.transaction.autolink;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.sql.ResultSet;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowCallbackHandler;

/** SQL shape and row handling of the scoped suggestion sync and the single-query liveness check (no database). */
class AutoLinkStoreScopeTest {

    private static final LocalDate D = LocalDate.of(2026, 10, 10);

    private final JdbcTemplate jdbc = mock(JdbcTemplate.class);
    private final AutoLinkStore store = new AutoLinkStore(jdbc);
    private final UUID userId = UUID.randomUUID();
    private final List<String> sqls = new ArrayList<>();
    private final List<Object[]> argLists = new ArrayList<>();

    /** Every query(sql, handler, args...) feeds the given rows (id, kind, txn, candidates) to the handler. */
    private void queryReturns(List<String[]> rows) throws Exception {
        doAnswer(inv -> {
            sqls.add(inv.getArgument(0));
            argLists.add(Arrays.copyOfRange(inv.getArguments(), 2, inv.getArguments().length));
            RowCallbackHandler handler = inv.getArgument(1);
            for (String[] r : rows) {
                ResultSet rs = mock(ResultSet.class);
                for (int i = 0; i < r.length; i++) {
                    when(rs.getString(i + 1)).thenReturn(r[i]);
                }
                handler.processRow(rs);
            }
            return null;
        }).when(jdbc).query(anyString(), any(RowCallbackHandler.class), any(Object[].class));
    }

    @Test
    void aFullSyncReadsEveryRowOfTheKindsWithoutAJoin() throws Exception {
        queryReturns(List.of());

        store.syncSuggestions(userId, Set.of(SuggestionKind.REFUND), Map.of());

        assertThat(sqls).hasSize(1);
        assertThat(sqls.get(0)).doesNotContain("JOIN").doesNotContain("transaction_date");
    }

    @Test
    void aScopedSyncReadsOnlyRowsWhoseAnchorIsDatedInRangeAndDeletesOnlyThose() throws Exception {
        UUID staleInRange = UUID.randomUUID();
        UUID staleTxn = UUID.randomUUID();
        UUID keptTxn = UUID.randomUUID();
        UUID keptRowId = UUID.randomUUID();
        UUID cand = UUID.randomUUID();
        // The query (date filtered in SQL) returns the in-range rows only: one stale, one still wanted.
        queryReturns(List.of(
                new String[] { staleInRange.toString(), "REFUND", staleTxn.toString(), cand.toString() },
                new String[] { keptRowId.toString(), "REFUND", keptTxn.toString(), cand.toString() }));

        store.syncSuggestions(userId, Set.of(SuggestionKind.REFUND),
                Map.of(SuggestionKind.REFUND, Map.of(keptTxn, List.of(cand))), new LinkScope(D, D.plusDays(3)));

        assertThat(sqls.get(0)).contains("JOIN transactions").contains("transaction_date BETWEEN ? AND ?");
        assertThat(argLists.get(0)).containsExactly(userId.toString(), java.sql.Date.valueOf(D), java.sql.Date.valueOf(D.plusDays(3)));
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<Object[]>> deletes = ArgumentCaptor.forClass(List.class);
        verify(jdbc).batchUpdate(eq("DELETE FROM link_suggestions WHERE id = ? AND user_id = ?"), deletes.capture());
        assertThat(deletes.getValue()).hasSize(1);
        assertThat(deletes.getValue().get(0)[0]).isEqualTo(staleInRange.toString());
        verify(jdbc, never()).batchUpdate(eq("UPDATE link_suggestions SET candidate_ids = ? WHERE id = ? AND user_id = ?"), anyList());
    }

    @Test
    void aScopedSyncInsertsANewAnchorInRange() throws Exception {
        UUID newTxn = UUID.randomUUID();
        UUID cand = UUID.randomUUID();
        queryReturns(List.of());

        store.syncSuggestions(userId, Set.of(SuggestionKind.REFUND),
                Map.of(SuggestionKind.REFUND, Map.of(newTxn, List.of(cand))), new LinkScope(D, D));

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<Object[]>> inserts = ArgumentCaptor.forClass(List.class);
        verify(jdbc).batchUpdate(org.mockito.ArgumentMatchers.startsWith("INSERT INTO link_suggestions"), inserts.capture());
        assertThat(inserts.getValue()).hasSize(1);
        assertThat(inserts.getValue().get(0)[3]).isEqualTo(newTxn.toString());
    }

    @Test
    void livenessIsOneQueryWithNoBoundIdList() throws Exception {
        UUID live = UUID.randomUUID();
        queryReturns(java.util.Collections.singletonList(new String[] { live.toString() }));

        Set<UUID> out = store.liveSuggestionTransactionIds(userId);

        assertThat(out).containsExactly(live);
        assertThat(sqls).hasSize(1);
        assertThat(sqls.get(0)).contains("FROM link_suggestions").contains("SUBSTR(s2.candidate_ids, n.k * 37 + 1, 36)").contains("kind <> 'LENDING'")
                .contains("transaction_link_members");
        // the user id only: three placeholders, no id per row
        assertThat(argLists.get(0)).containsExactly(userId.toString(), userId.toString(), userId.toString());
        assertThat(sqls.get(0).chars().filter(c -> c == '?').count()).isEqualTo(3);
    }
}
