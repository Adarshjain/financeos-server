package com.financeos.domain.inbox.collect;

import static com.financeos.api.inbox.dto.InboxItemResponse.SECTION_ACT_NOW;
import static com.financeos.api.inbox.dto.InboxItemResponse.SEVERITY_CRITICAL;
import static com.financeos.api.inbox.dto.InboxItemResponse.SEVERITY_WARNING;

import com.financeos.api.inbox.dto.InboxActionPayloadResponse;
import com.financeos.api.inbox.dto.InboxActionResponse;
import com.financeos.api.inbox.dto.InboxItemResponse;
import com.financeos.api.inbox.dto.InboxRefsResponse;
import com.financeos.domain.inbox.InboxKinds;
import com.financeos.domain.notification.bill.BillNotificationService;
import com.financeos.domain.notification.bill.CardBill;
import com.financeos.domain.notification.bill.CardBillService;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * Card bills: due within a week, overdue, or missing the figures the reminders need. Paid,
 * nothing-due and not-yet-billed cards stay out, as do muted cards and stale (backfilled) bills.
 */
@Component
public class BillInboxCollector implements InboxCollector {

    private final CardBillService cardBillService;

    public BillInboxCollector(CardBillService cardBillService) {
        this.cardBillService = cardBillService;
    }

    @Override
    public List<InboxItemResponse> collect(UUID userId, LocalDate today) {
        List<InboxItemResponse> rows = new ArrayList<>();
        for (CardBill bill : cardBillService.listBills(userId)) {
            // Stale = backfilled history the push producer ignores too; it is not something to act on now.
            if (bill.muted() || bill.statementId() == null || BillNotificationService.isStale(bill)) {
                continue;
            }
            InboxItemResponse row = switch (bill.status()) {
                case OPEN, PARTIAL -> bill.daysUntilDue() != null && bill.daysUntilDue() <= InboxKinds.DUE_SOON_DAYS
                        ? due(bill, SEVERITY_WARNING) : null;
                case OVERDUE -> due(bill, SEVERITY_CRITICAL);
                case DUE_UNKNOWN -> dueUnknown(bill);
                default -> null; // PAID, NO_DUE, AWAITING_STATEMENT: nothing to act on
            };
            if (row != null) {
                rows.add(row);
            }
        }
        return rows;
    }

    private static InboxItemResponse due(CardBill bill, String severity) {
        long days = bill.daysUntilDue() == null ? 0 : bill.daysUntilDue();
        StringBuilder subtitle = new StringBuilder(InboxRows.dueText(days)).append(" · ").append(InboxRows.date(bill.paymentDueDate()));
        if (bill.paidAmount() != null && bill.paidAmount().signum() > 0) {
            subtitle.append(" · ").append(InboxRows.money(bill.paidAmount())).append(" already paid");
        } else if (bill.minimumAmountDue() != null) {
            subtitle.append(" · Min ").append(InboxRows.money(bill.minimumAmountDue()));
        }
        List<InboxActionResponse> actions = new ArrayList<>();
        actions.add(InboxActionResponse.mutate("mark_paid", "Mark paid",
                InboxActionPayloadResponse.forStatement(bill.statementId(), bill.remainingAmount())));
        if (bill.possiblePayments() != null && !bill.possiblePayments().isEmpty()) {
            CardBill.PossiblePayment payment = bill.possiblePayments().get(0);
            actions.add(InboxActionResponse.mutate("confirm_payment", "Confirm payment " + InboxRows.money(payment.amount()),
                    InboxActionPayloadResponse.forPayment(bill.statementId(), payment.transactionId(), payment.amount(), payment.date())));
        }
        actions.add(InboxRows.snooze());
        return row(bill, severity, title(bill), subtitle.toString(), actions);
    }

    private static InboxItemResponse dueUnknown(CardBill bill) {
        List<InboxActionResponse> actions = List.of(
                InboxActionResponse.mutate("set_details", "Set details",
                        InboxActionPayloadResponse.forStatement(bill.statementId(), bill.totalAmountDue())),
                InboxRows.snooze());
        return row(bill, SEVERITY_WARNING, title(bill),
                "Due date or amount missing on the statement · set it so reminders can start", actions);
    }

    private static String title(CardBill bill) {
        String card = InboxRows.cardLabel(bill.accountName(), bill.last4());
        return card + " bill";
    }

    private static InboxItemResponse row(CardBill bill, String severity, String title, String subtitle,
                                         List<InboxActionResponse> actions) {
        return InboxRows.item(InboxKinds.billKey(bill.statementId()), InboxKinds.BILL, severity, SECTION_ACT_NOW, title, subtitle,
                "/upcoming?bill=" + bill.statementId(), bill.remainingAmount(), bill.paymentDueDate(), actions,
                InboxRefsResponse.ofStatement(bill.statementId(), bill.accountId()));
    }
}
