package com.financeos.domain.inbox.collect;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.financeos.api.inbox.dto.InboxItemResponse;
import com.financeos.domain.inbox.InboxKinds;
import com.financeos.domain.transaction.autolink.LinkSuggestionService;
import com.financeos.domain.transaction.autolink.SuggestionKind;
import java.time.LocalDate;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class LinkSuggestionInboxCollectorTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 10, 30);

    private final LinkSuggestionService store = mock(LinkSuggestionService.class);
    private final LinkSuggestionInboxCollector collector = new LinkSuggestionInboxCollector(store);
    private final UUID userId = UUID.randomUUID();

    @Test
    void noSuggestionsMeansNoRows() {
        when(store.openCounts(userId)).thenReturn(new EnumMap<>(SuggestionKind.class));

        assertEquals(List.of(), collector.collect(userId, TODAY));
    }

    @Test
    void oneGroupedNeedsLookRowPerKindNeverOnePerSuggestion() {
        Map<SuggestionKind, Integer> counts = new EnumMap<>(SuggestionKind.class);
        counts.put(SuggestionKind.TRANSFER, 3);
        counts.put(SuggestionKind.REFUND, 1);
        when(store.openCounts(userId)).thenReturn(counts);

        List<InboxItemResponse> rows = collector.collect(userId, TODAY);

        assertEquals(2, rows.size());
        InboxItemResponse transfers = rows.get(0);
        assertEquals("link-suggestion:TRANSFER", transfers.key());
        assertEquals("link_suggestion", transfers.kind());
        assertEquals(InboxItemResponse.SECTION_NEEDS_LOOK, transfers.section());
        assertEquals(InboxItemResponse.ROW_SUMMARY, transfers.rowType());
        assertEquals("3 possible transfers", transfers.title());
        assertEquals(3, transfers.count());
        assertEquals("1 possible refund", rows.get(1).title());
        assertEquals("link-suggestion:REFUND", rows.get(1).key());
    }

    @Test
    void theRowsAreSummaryKeysSoADismissalComesBackWhenTheCountChanges() {
        assertTrue(InboxKinds.isSummaryKey(InboxKinds.linkSuggestionKey("CC_PAYMENT")));
        assertEquals("2 possible card payments", LinkSuggestionInboxCollector.title(SuggestionKind.CC_PAYMENT, 2));
    }
}
