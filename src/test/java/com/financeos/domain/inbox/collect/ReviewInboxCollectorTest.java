package com.financeos.domain.inbox.collect;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.financeos.api.inbox.dto.InboxActionResponse;
import com.financeos.api.inbox.dto.InboxItemResponse;
import com.financeos.domain.transaction.ReviewType;
import com.financeos.domain.transaction.TransactionRepository;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class ReviewInboxCollectorTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 10, 20);

    private final TransactionRepository repository = mock(TransactionRepository.class);
    private final ReviewInboxCollector collector = new ReviewInboxCollector(repository);
    private final UUID userId = UUID.randomUUID();

    private void queue(long total) {
        when(repository.countByUserIdAndReviewType(userId, ReviewType.NEEDS_REVIEW)).thenReturn(total);
    }

    @Test
    void anEmptyQueueHasNoRowAndSkipsTheReasonCount() {
        queue(0);
        assertEquals(List.of(), collector.collect(userId, TODAY));
        verify(repository, never()).countByUserIdAndReviewTypeAndReason(any(), any(), any());
    }

    @Test
    void theQueueIsOneSummaryRowLinkingToReview() {
        queue(5);

        List<InboxItemResponse> rows = collector.collect(userId, TODAY);

        assertEquals(1, rows.size());
        InboxItemResponse row = rows.get(0);
        assertEquals("review", row.key());
        assertEquals("review", row.kind());
        assertEquals(InboxItemResponse.ROW_SUMMARY, row.rowType());
        assertEquals("warning", row.severity());
        assertEquals("needs_look", row.section());
        assertEquals("Transactions to review", row.title());
        assertEquals("5 waiting for review", row.subtitle());
        assertEquals("/transactions/review", row.href());
        assertEquals(5, row.count());
        assertEquals(List.of(InboxActionResponse.navigate("review", "Review", "/transactions/review"), InboxRows.dismiss()), row.actions());
    }
}
