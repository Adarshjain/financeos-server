package com.financeos.domain.inbox.collect;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.financeos.api.inbox.dto.InboxActionResponse;
import com.financeos.api.inbox.dto.InboxItemResponse;
import com.financeos.api.inbox.dto.InboxRefsResponse;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class InboxRowsTest {

    @Test
    void itemRowCarriesEveryFieldWithNoCountAndNoSnoozeState() {
        UUID statementId = UUID.randomUUID();
        UUID accountId = UUID.randomUUID();
        InboxRefsResponse refs = InboxRefsResponse.ofStatement(statementId, accountId);
        List<InboxActionResponse> actions = List.of(InboxRows.snooze());

        InboxItemResponse row = InboxRows.item("bill:" + statementId, "bill", "warning", "act_now", "Card bill", "Due today",
                "/upcoming", new BigDecimal("1200"), LocalDate.of(2026, 10, 20), actions, refs);

        assertEquals("bill:" + statementId, row.key());
        assertEquals("bill", row.kind());
        assertEquals(InboxItemResponse.ROW_ITEM, row.rowType());
        assertFalse(row.isSummary());
        assertEquals("warning", row.severity());
        assertEquals("act_now", row.section());
        assertEquals("Card bill", row.title());
        assertEquals("Due today", row.subtitle());
        assertEquals("/upcoming", row.href());
        assertEquals(new BigDecimal("1200"), row.amount());
        assertEquals(LocalDate.of(2026, 10, 20), row.date());
        assertNull(row.count());
        assertEquals(actions, row.actions());
        assertNull(row.snoozedUntil(), "collectors never apply snooze state");
        assertEquals(refs, row.refs());
    }

    @Test
    void summaryRowIsACountBehindOneLinkWithNoAmountDateOrRefs() {
        List<InboxActionResponse> actions = List.of(InboxRows.open("/transactions/review"));

        InboxItemResponse row = InboxRows.summary("review", "review", "warning", "needs_look", "Transactions to review",
                "4 waiting", "/transactions/review", 4, actions);

        assertEquals(InboxItemResponse.ROW_SUMMARY, row.rowType());
        assertTrue(row.isSummary());
        assertEquals(4, row.count());
        assertNull(row.amount());
        assertNull(row.date());
        assertNull(row.snoozedUntil());
        assertEquals(InboxRefsResponse.NONE, row.refs());
        assertEquals("/transactions/review", row.href());
        assertEquals(actions, row.actions());
    }

    @Test
    void openIsANavigationWhileSnoozeAndDismissAreMutationsWithoutPayload() {
        assertEquals(new InboxActionResponse("open", "Open", "/rewards", null), InboxRows.open("/rewards"));
        assertEquals(new InboxActionResponse("snooze", "Snooze", null, null), InboxRows.snooze());
        assertEquals(new InboxActionResponse("dismiss", "Dismiss", null, null), InboxRows.dismiss());
    }

    @Test
    void cardLabelAddsTheLast4OnlyWhenPresentAndFallsBackToCard() {
        assertEquals("Infinia ••1234", InboxRows.cardLabel("Infinia", "1234"));
        assertEquals("Infinia", InboxRows.cardLabel("Infinia", null));
        assertEquals("Infinia", InboxRows.cardLabel("Infinia", "  "));
        assertEquals("Card ••1234", InboxRows.cardLabel(null, "1234"));
        assertEquals("Card", InboxRows.cardLabel(null, null));
    }

    @Test
    void dueTextReadsTodayTomorrowInNDaysAndOverdueWithSingularDay() {
        assertEquals("Due today", InboxRows.dueText(0));
        assertEquals("Due tomorrow", InboxRows.dueText(1));
        assertEquals("Due in 5 days", InboxRows.dueText(5));
        assertEquals("Overdue by 1 day", InboxRows.dueText(-1));
        assertEquals("Overdue by 12 days", InboxRows.dueText(-12));
    }

    @Test
    void moneyAndDateUseThePushFormattingIncludingNulls() {
        assertEquals("₹1,23,456.50", InboxRows.money(new BigDecimal("123456.5")));
        assertEquals("₹—", InboxRows.money(null));
        assertEquals("5 Oct", InboxRows.date(LocalDate.of(2026, 10, 5)));
        assertEquals("—", InboxRows.date(null));
    }
}
