package com.financeos.domain.inbox.collect;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.financeos.api.inbox.dto.InboxActionPayloadResponse;
import com.financeos.api.inbox.dto.InboxActionResponse;
import com.financeos.api.inbox.dto.InboxItemResponse;
import com.financeos.api.inbox.dto.InboxRefsResponse;
import com.financeos.core.time.AppTime;
import com.financeos.domain.notification.bill.BillStatus;
import com.financeos.domain.notification.bill.CardBill;
import com.financeos.domain.notification.bill.CardBillService;
import com.financeos.domain.notification.bill.PaidSource;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class BillInboxCollectorTest {

    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");
    private static final LocalDate TODAY = LocalDate.of(2026, 10, 20);

    private final CardBillService cardBillService = mock(CardBillService.class);
    private final BillInboxCollector collector = new BillInboxCollector(cardBillService);
    private final UUID userId = UUID.randomUUID();
    private final UUID accountId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        AppTime.useClock(Clock.fixed(TODAY.atTime(10, 5).atZone(IST).toInstant(), IST));
    }

    @AfterEach
    void tearDown() {
        AppTime.reset();
    }

    /** A live statement imported a few days after it closed, so it is never stale. */
    private CardBill bill(BillStatus status, LocalDate due, BigDecimal minimum, BigDecimal paid, BigDecimal remaining,
                          List<CardBill.PossiblePayment> possible, boolean muted) {
        Long days = due == null ? null : ChronoUnit.DAYS.between(TODAY, due);
        return new CardBill(accountId, "HDFC Regalia", "1234", UUID.randomUUID(), LocalDate.of(2026, 9, 1), LocalDate.of(2026, 9, 30),
                due, new BigDecimal("15000"), minimum, paid, remaining, PaidSource.NONE, status, days, null, possible, muted,
                LocalDate.of(2026, 10, 2).atStartOfDay(IST).toInstant(), null, null, null, null, null);
    }

    private CardBill open(BillStatus status, int daysUntilDue) {
        return bill(status, TODAY.plusDays(daysUntilDue), new BigDecimal("750"), null, new BigDecimal("15000"), List.of(), false);
    }

    private List<InboxItemResponse> collect(CardBill... bills) {
        when(cardBillService.listBills(userId)).thenReturn(List.of(bills));
        return collector.collect(userId, TODAY);
    }

    // ---------------------------------------------------------------- which bills show

    @Test
    void openAndPartialBillsShowOnlyWithinSevenDaysOfTheDueDate() {
        CardBill dueIn7 = open(BillStatus.OPEN, 7);
        CardBill partialIn3 = open(BillStatus.PARTIAL, 3);
        CardBill dueIn8 = open(BillStatus.OPEN, 8);

        List<InboxItemResponse> rows = collect(dueIn7, partialIn3, dueIn8);

        assertEquals(List.of("bill:" + dueIn7.statementId(), "bill:" + partialIn3.statementId()),
                rows.stream().map(InboxItemResponse::key).toList());
        assertTrue(rows.stream().allMatch(r -> "warning".equals(r.severity()) && "act_now".equals(r.section())));
    }

    @Test
    void anOpenBillWithoutDaysUntilDueIsLeftOut() {
        CardBill noDays = bill(BillStatus.OPEN, null, null, null, new BigDecimal("15000"), List.of(), false);
        assertEquals(List.of(), collect(noDays));
    }

    @Test
    void overdueBillIsCritical() {
        InboxItemResponse row = collect(open(BillStatus.OVERDUE, -3)).get(0);
        assertEquals("critical", row.severity());
        assertEquals("act_now", row.section());
        assertEquals("Overdue by 3 days · 17 Oct · Min ₹750", row.subtitle());
    }

    @Test
    void paidNoDueAndAwaitingStatementRowsAreNothingToActOn() {
        CardBill paid = bill(BillStatus.PAID, TODAY.plusDays(2), null, new BigDecimal("15000"), BigDecimal.ZERO, List.of(), false);
        CardBill noDue = bill(BillStatus.NO_DUE, TODAY.plusDays(2), null, null, BigDecimal.ZERO, List.of(), false);
        CardBill awaiting = new CardBill(accountId, "HDFC Regalia", "1234", null, null, null, null, null, null, null, null,
                PaidSource.NONE, BillStatus.AWAITING_STATEMENT, null, null, List.of(), false, null, null, null, null,
                new BigDecimal("4200"), TODAY.plusDays(10));

        assertEquals(List.of(), collect(paid, noDue, awaiting));
    }

    @Test
    void mutedCardsStayOutEvenWhenOverdue() {
        CardBill muted = bill(BillStatus.OVERDUE, TODAY.minusDays(2), null, null, new BigDecimal("15000"), List.of(), true);
        assertEquals(List.of(), collect(muted));
    }

    @Test
    void staleBackfilledBillsStayOut() {
        // Imported today for a bill that fell due 20 days ago: history, not something to act on.
        LocalDate due = TODAY.minusDays(20);
        CardBill stale = new CardBill(accountId, "HDFC Regalia", "1234", UUID.randomUUID(), LocalDate.of(2026, 8, 1),
                LocalDate.of(2026, 8, 31), due, new BigDecimal("15000"), null, null, new BigDecimal("15000"), PaidSource.NONE,
                BillStatus.OVERDUE, -20L, null, List.of(), false, TODAY.atTime(10, 0).atZone(IST).toInstant(), null, null, null, null, null);
        assertEquals(List.of(), collect(stale));
    }

    // ---------------------------------------------------------------- row shape

    @Test
    void dueRowLinksToUpcomingWithTheRemainingAmountAndRefs() {
        CardBill b = open(BillStatus.OPEN, 4);

        InboxItemResponse row = collect(b).get(0);

        assertEquals("bill:" + b.statementId(), row.key());
        assertEquals("bill", row.kind());
        assertEquals(InboxItemResponse.ROW_ITEM, row.rowType());
        assertEquals("HDFC Regalia ••1234 bill", row.title(), "the amount is its own field, never in the title");
        assertEquals("Due in 4 days · 24 Oct · Min ₹750", row.subtitle());
        assertEquals("/upcoming?bill=" + b.statementId(), row.href());
        assertEquals(new BigDecimal("15000"), row.amount());
        assertEquals(TODAY.plusDays(4), row.date());
        assertEquals(InboxRefsResponse.ofStatement(b.statementId(), accountId), row.refs());
    }

    @Test
    void titleDropsTheLast4WhenTheCardHasNone() {
        CardBill b = new CardBill(accountId, "Amex Gold", null, UUID.randomUUID(), null, null, TODAY, new BigDecimal("100"), null,
                null, new BigDecimal("100"), PaidSource.NONE, BillStatus.OPEN, 0L, null, List.of(), false, null, null, null, null, null, null);
        InboxItemResponse row = collect(b).get(0);
        assertEquals("Amex Gold bill", row.title());
        assertEquals("Due today · 20 Oct", row.subtitle(), "no minimum and nothing paid: no third part");
    }

    @Test
    void subtitlePrefersWhatIsAlreadyPaidOverTheMinimum() {
        CardBill partial = bill(BillStatus.PARTIAL, TODAY.plusDays(1), new BigDecimal("750"), new BigDecimal("2000"),
                new BigDecimal("13000"), List.of(), false);
        CardBill zeroPaid = bill(BillStatus.OPEN, TODAY.plusDays(1), new BigDecimal("750"), BigDecimal.ZERO,
                new BigDecimal("15000"), List.of(), false);

        List<InboxItemResponse> rows = collect(partial, zeroPaid);

        assertEquals("Due tomorrow · 21 Oct · ₹2,000 already paid", rows.get(0).subtitle());
        assertEquals("Due tomorrow · 21 Oct · Min ₹750", rows.get(1).subtitle(), "a zero paid amount is not 'already paid'");
    }

    @Test
    void dueRowActionsAreMarkPaidThenSnoozeWhenNoPaymentLooksLikeIt() {
        CardBill noPossible = bill(BillStatus.OPEN, TODAY.plusDays(2), null, null, new BigDecimal("15000"), null, false);
        CardBill emptyPossible = open(BillStatus.OPEN, 2);

        List<InboxItemResponse> rows = collect(noPossible, emptyPossible);

        assertEquals(List.of(
                InboxActionResponse.mutate("mark_paid", "Mark paid", InboxActionPayloadResponse.forStatement(noPossible.statementId(), new BigDecimal("15000"))),
                InboxRows.snooze()), rows.get(0).actions());
        assertEquals(List.of("mark_paid", "snooze"), rows.get(1).actions().stream().map(InboxActionResponse::type).toList());
    }

    @Test
    void confirmPaymentOffersTheFirstPossiblePayment() {
        UUID first = UUID.randomUUID();
        List<CardBill.PossiblePayment> possible = List.of(
                new CardBill.PossiblePayment(first, TODAY.minusDays(1), new BigDecimal("12000"), "NEFT HDFC CARD"),
                new CardBill.PossiblePayment(UUID.randomUUID(), TODAY, new BigDecimal("3000"), "UPI"));
        CardBill b = bill(BillStatus.OPEN, TODAY.plusDays(2), null, null, new BigDecimal("15000"), possible, false);

        List<InboxActionResponse> actions = collect(b).get(0).actions();

        assertEquals(3, actions.size());
        assertEquals("mark_paid", actions.get(0).type());
        assertEquals(InboxActionResponse.mutate("confirm_payment", "Confirm payment ₹12,000",
                InboxActionPayloadResponse.forPayment(b.statementId(), first, new BigDecimal("12000"), TODAY.minusDays(1))), actions.get(1));
        assertEquals(InboxRows.snooze(), actions.get(2));
    }

    @Test
    void dueUnknownAsksForTheDetailsAndCanBeSnoozed() {
        CardBill unknown = new CardBill(accountId, "HDFC Regalia", "1234", UUID.randomUUID(), LocalDate.of(2026, 9, 1),
                LocalDate.of(2026, 9, 30), null, new BigDecimal("8000"), null, null, new BigDecimal("8000"), PaidSource.NONE,
                BillStatus.DUE_UNKNOWN, null, null, List.of(), false, LocalDate.of(2026, 10, 2).atStartOfDay(IST).toInstant(),
                null, null, null, null, null);

        InboxItemResponse row = collect(unknown).get(0);

        assertEquals("warning", row.severity());
        assertEquals("act_now", row.section());
        assertEquals("HDFC Regalia ••1234 bill", row.title());
        assertEquals("Due date or amount missing on the statement · set it so reminders can start", row.subtitle());
        assertEquals("/upcoming?bill=" + unknown.statementId(), row.href());
        assertNull(row.date());
        assertEquals(List.of(
                InboxActionResponse.mutate("set_details", "Set details", InboxActionPayloadResponse.forStatement(unknown.statementId(), new BigDecimal("8000"))),
                InboxRows.snooze()), row.actions());
    }
}
