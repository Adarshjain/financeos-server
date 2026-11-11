package com.financeos.domain.inbox.collect;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.financeos.api.inbox.dto.InboxItemResponse;
import com.financeos.domain.instrument.Instrument;
import com.financeos.domain.instrument.corporateaction.CorporateActionRepository;
import com.financeos.domain.instrument.corporateaction.detect.CorporateActionSuggestion;
import com.financeos.domain.instrument.corporateaction.detect.CorporateActionSuggestionRepository;
import com.financeos.domain.instrument.corporateaction.detect.SuggestionKind;
import com.financeos.domain.investment.InvestmentTransactionRepository;
import java.time.LocalDate;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class StockHealthInboxCollectorsTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 11, 10);
    private final UUID userId = UUID.randomUUID();

    @Test
    void notTradingRowsCoverHeldStocksWithoutAMergerOrDelisting() {
        InvestmentTransactionRepository transactions = mock(InvestmentTransactionRepository.class);
        CorporateActionRepository actions = mock(CorporateActionRepository.class);
        UUID open = UUID.randomUUID();
        UUID answered = UUID.randomUUID();
        when(transactions.findHeldNotTradingInstruments(userId))
                .thenReturn(List.of(new Object[] {open, "Acme Ltd"}, new Object[] {answered, "Old Co"}));
        when(actions.findInstrumentIdsWithTypes(eq(userId), any())).thenReturn(List.of(answered));

        List<InboxItemResponse> rows = new StockNotTradingInboxCollector(transactions, actions).collect(userId, TODAY);

        assertThat(rows).hasSize(1);
        InboxItemResponse r = rows.get(0);
        assertThat(r.kind()).isEqualTo("stock_not_trading");
        assertThat(r.key()).isEqualTo("stock_not_trading:" + open);
        assertThat(r.title()).isEqualTo("Acme Ltd no longer trades");
        assertThat(r.subtitle()).isEqualTo("Record a merger or mark it delisted");
        assertThat(r.section()).isEqualTo(InboxItemResponse.SECTION_NEEDS_LOOK);
        assertThat(r.severity()).isEqualTo(InboxItemResponse.SEVERITY_WARNING);
        assertThat(r.actions()).extracting(a -> a.type()).contains("dismiss");
        assertThat(r.refs().instrumentId()).isEqualTo(open);
        assertThat(r.refs().suggestionId()).isNull();
    }

    @Test
    void noHeldNotTradingStockMeansNoRowsAndNoSecondQuery() {
        InvestmentTransactionRepository transactions = mock(InvestmentTransactionRepository.class);
        CorporateActionRepository actions = mock(CorporateActionRepository.class);
        when(transactions.findHeldNotTradingInstruments(userId)).thenReturn(List.of());

        assertThat(new StockNotTradingInboxCollector(transactions, actions).collect(userId, TODAY)).isEmpty();
        verify(actions, never()).findInstrumentIdsWithTypes(any(), any());
    }

    @Test
    void oversoldRowsComeFromPendingOversoldSuggestions() {
        CorporateActionSuggestionRepository repo = mock(CorporateActionSuggestionRepository.class);
        Instrument acme = new Instrument();
        acme.setId(UUID.randomUUID());
        acme.setName("Acme Ltd");
        CorporateActionSuggestion s = new CorporateActionSuggestion();
        s.setId(UUID.randomUUID());
        s.setInstrument(acme);
        s.setKind(SuggestionKind.oversold);
        s.setExDate(LocalDate.of(2026, 10, 28));
        when(repo.findPendingOfKinds(userId, Set.of(SuggestionKind.oversold))).thenReturn(List.of(s));

        List<InboxItemResponse> rows = new HoldingOversoldInboxCollector(repo).collect(userId, TODAY);

        assertThat(rows).hasSize(1);
        InboxItemResponse r = rows.get(0);
        assertThat(r.kind()).isEqualTo("holding_oversold");
        assertThat(r.key()).isEqualTo("holding_oversold:" + s.getId());
        assertThat(r.title()).isEqualTo("Sold more Acme Ltd than you held");
        assertThat(r.subtitle()).isEqualTo("A split, bonus or earlier buy may be missing");
        assertThat(r.section()).isEqualTo(InboxItemResponse.SECTION_NEEDS_LOOK);
        assertThat(r.severity()).isEqualTo(InboxItemResponse.SEVERITY_WARNING);
        assertThat(r.actions()).extracting(a -> a.type()).contains("dismiss");
        assertThat(r.refs().suggestionId()).isEqualTo(s.getId());
        assertThat(r.refs().instrumentId()).isEqualTo(acme.getId());
    }

    @Test
    void theReviewCollectorStillIgnoresOversold() {
        assertThat(CorporateActionReviewInboxCollector.KINDS).doesNotContain(SuggestionKind.oversold);
    }
}
