package com.financeos.domain.notification.bill;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.financeos.domain.notification.push.PushMessage;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class BillMessagesTest {

    private static final UUID STATEMENT = UUID.fromString("11111111-1111-1111-1111-111111111111");

    private static CardBill bill(BillStatus status, BigDecimal total, BigDecimal paid, Long days, CardBill.Digest digest) {
        BigDecimal remaining = total == null ? null : total.subtract(paid == null ? BigDecimal.ZERO : paid).max(BigDecimal.ZERO);
        return new CardBill(UUID.randomUUID(), "HDFC Regalia", "4321", STATEMENT,
                LocalDate.of(2026, 9, 11), LocalDate.of(2026, 10, 10), LocalDate.of(2026, 10, 28),
                total, new BigDecimal("2500"), paid, remaining,
                paid != null && paid.signum() > 0 ? PaidSource.LINK : PaidSource.NONE, status, days, null,
                List.of(), false, Instant.now(), null, null, digest);
    }

    @Test
    void moneyUsesIndianGroupingAndDropsWholeRupeePaise() {
        assertEquals("₹0", BillMessages.money(BigDecimal.ZERO));
        assertEquals("₹999", BillMessages.money(new BigDecimal("999")));
        assertEquals("₹1,000", BillMessages.money(new BigDecimal("1000")));
        assertEquals("₹12,345.50", BillMessages.money(new BigDecimal("12345.5")));
        assertEquals("₹1,23,456", BillMessages.money(new BigDecimal("123456")));
        assertEquals("₹12,34,567.89", BillMessages.money(new BigDecimal("1234567.891")));
        assertEquals("₹1,00,00,000", BillMessages.money(new BigDecimal("10000000")));
        assertEquals("-₹2,500", BillMessages.money(new BigDecimal("-2500")));
        assertEquals("₹—", BillMessages.money(null));
    }

    @Test
    void receivedDigestNamesTotalDueDateAndTheStatementFigures() {
        CardBill.Digest digest = new CardBill.Digest(new BigDecimal("48250"), new BigDecimal("30000"),
                new BigDecimal("0"), new BigDecimal("500"), new BigDecimal("1205"), new BigDecimal("18400"),
                new BigDecimal("200000"), new BigDecimal("24.1"), 42);
        PushMessage m = BillMessages.received(bill(BillStatus.OPEN, new BigDecimal("48250"), BigDecimal.ZERO, 18L, digest));

        assertEquals("HDFC Regalia ••4321: ₹48,250 due 28 Oct", m.title());
        assertEquals("Min ₹2,500 · Spent ₹48,250 · Paid ₹30,000 · Charges ₹500 · 1205 pts earned · 18400 pts balance", m.body());
        assertEquals("/dashboard?bill=" + STATEMENT, m.url());
        assertEquals("bill-" + STATEMENT, m.tag());
    }

    @Test
    void receivedWithNothingDueSaysSo() {
        PushMessage m = BillMessages.received(bill(BillStatus.NO_DUE, new BigDecimal("-120"), BigDecimal.ZERO, 18L,
                new CardBill.Digest(null, null, null, null, null, null, null, null, null)));
        assertEquals("HDFC Regalia ••4321: statement in, nothing due", m.title());
        assertEquals("Tap to see the statement.", m.body());
    }

    @Test
    void receivedWithoutDueDateBecomesTheDueMissingNudge() {
        PushMessage m = BillMessages.received(bill(BillStatus.DUE_UNKNOWN, null, BigDecimal.ZERO, null, null));
        assertEquals("HDFC Regalia ••4321: statement imported", m.title());
        assertTrue(m.body().contains("due date or amount wasn't found"));
    }

    @Test
    void dueReminderPhrasesTodayTomorrowAndNDays() {
        assertEquals("HDFC Regalia ••4321: bill due today",
                BillMessages.dueReminder(bill(BillStatus.OPEN, new BigDecimal("48250"), BigDecimal.ZERO, 0L, null)).title());
        assertEquals("HDFC Regalia ••4321: bill due tomorrow",
                BillMessages.dueReminder(bill(BillStatus.OPEN, new BigDecimal("48250"), BigDecimal.ZERO, 1L, null)).title());
        PushMessage m = BillMessages.dueReminder(bill(BillStatus.OPEN, new BigDecimal("48250"), BigDecimal.ZERO, 5L, null));
        assertEquals("HDFC Regalia ••4321: bill due in 5 days", m.title());
        assertEquals("₹48,250 by 28 Oct · Min ₹2,500", m.body());
    }

    @Test
    void partialPaymentReminderShowsRemainingAndWhatWasPaid() {
        PushMessage m = BillMessages.dueReminder(bill(BillStatus.PARTIAL, new BigDecimal("48250"), new BigDecimal("20000"), 2L, null));
        assertEquals("₹28,250 by 28 Oct · ₹20,000 already paid", m.body());
    }

    @Test
    void overdueCountsDaysAndAsksToMarkPaid() {
        PushMessage one = BillMessages.overdue(bill(BillStatus.OVERDUE, new BigDecimal("48250"), BigDecimal.ZERO, -1L, null));
        assertEquals("HDFC Regalia ••4321: bill overdue by 1 day", one.title());
        assertEquals("₹48,250 was due 28 Oct. Mark it paid once it's done.", one.body());
        PushMessage many = BillMessages.overdue(bill(BillStatus.OVERDUE, new BigDecimal("48250"), new BigDecimal("1000"), -4L, null));
        assertEquals("HDFC Regalia ••4321: bill overdue by 4 days", many.title());
        assertTrue(many.body().endsWith("₹1,000 already paid."));
    }

    @Test
    void cardLabelOmitsLast4WhenUnknown() {
        CardBill noLast4 = new CardBill(UUID.randomUUID(), "Amex", null, STATEMENT, null, null, LocalDate.of(2026, 10, 28),
                new BigDecimal("10"), null, BigDecimal.ZERO, new BigDecimal("10"), PaidSource.NONE, BillStatus.OPEN, 3L, null,
                List.of(), false, null, null, null, null);
        assertEquals("Amex: bill due in 3 days", BillMessages.dueReminder(noLast4).title());
    }

    @Test
    void forKindDispatchesEveryKindAndRejectsPaid() {
        CardBill open = bill(BillStatus.OPEN, new BigDecimal("100"), BigDecimal.ZERO, 3L, null);
        assertTrue(BillMessages.forKind("RECEIVED", open).title().contains("due 28 Oct"));
        assertTrue(BillMessages.forKind("DUE_MISSING", open).title().endsWith("statement imported"));
        assertTrue(BillMessages.forKind("DUE_3", open).title().endsWith("due in 3 days"));
        assertTrue(BillMessages.forKind("OVERDUE", bill(BillStatus.OVERDUE, new BigDecimal("100"), BigDecimal.ZERO, -2L, null))
                .title().contains("overdue by 2 days"));
        assertThrows(IllegalArgumentException.class, () -> BillMessages.forKind("PAID", open));
    }
}
