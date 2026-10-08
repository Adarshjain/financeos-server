package com.financeos.domain.notification.bill;

import com.financeos.domain.notification.push.PushMessage;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Push texts for every bill kind. Pure; bodies stay short because the OS truncates them. */
public final class BillMessages {

    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("d MMM", Locale.ENGLISH);

    private BillMessages() {
    }

    public static PushMessage forKind(String kind, CardBill bill) {
        if (BillNotificationKinds.RECEIVED.equals(kind)) {
            return received(bill);
        }
        if (BillNotificationKinds.DUE_MISSING.equals(kind)) {
            return dueMissing(bill);
        }
        if (BillNotificationKinds.OVERDUE.equals(kind)) {
            return overdue(bill);
        }
        if (BillNotificationKinds.isDue(kind)) {
            return dueReminder(bill);
        }
        throw new IllegalArgumentException("No message for kind " + kind);
    }

    /** The statement digest. */
    public static PushMessage received(CardBill bill) {
        if (bill.status() == BillStatus.DUE_UNKNOWN) {
            return dueMissing(bill);
        }
        String title;
        if (bill.status() == BillStatus.NO_DUE) {
            title = card(bill) + ": statement in, nothing due";
        } else {
            title = card(bill) + ": " + money(bill.totalAmountDue()) + " due " + date(bill.paymentDueDate());
        }
        List<String> parts = new ArrayList<>();
        if (bill.status() != BillStatus.NO_DUE && bill.minimumAmountDue() != null) {
            parts.add("Min " + money(bill.minimumAmountDue()));
        }
        CardBill.Digest d = bill.digest();
        if (d != null) {
            if (d.totalPurchases() != null) {
                parts.add("Spent " + money(d.totalPurchases()));
            }
            if (d.paymentsReceived() != null && d.paymentsReceived().signum() > 0) {
                parts.add("Paid " + money(d.paymentsReceived()));
            }
            BigDecimal charges = nz(d.financeCharges()).add(nz(d.feesAndCharges()));
            if (charges.signum() > 0) {
                parts.add("Charges " + money(charges));
            }
            if (d.rewardPointsEarned() != null) {
                parts.add(points(d.rewardPointsEarned()) + " pts earned");
            }
            if (d.rewardPointsBalance() != null) {
                parts.add(points(d.rewardPointsBalance()) + " pts balance");
            }
        }
        String body = parts.isEmpty() ? "Tap to see the statement." : String.join(" · ", parts);
        return message(bill, title, body);
    }

    public static PushMessage dueMissing(CardBill bill) {
        return message(bill, card(bill) + ": statement imported",
                "The due date or amount wasn't found on the statement. Tap to set it so reminders can start.");
    }

    public static PushMessage dueReminder(CardBill bill) {
        long days = bill.daysUntilDue() == null ? 0 : bill.daysUntilDue();
        String phrase = days <= 0 ? "due today" : days == 1 ? "due tomorrow" : "due in " + days + " days";
        StringBuilder body = new StringBuilder(money(bill.remainingAmount()))
                .append(" by ").append(date(bill.paymentDueDate()));
        if (bill.paidAmount() != null && bill.paidAmount().signum() > 0) {
            body.append(" · ").append(money(bill.paidAmount())).append(" already paid");
        } else if (bill.minimumAmountDue() != null) {
            body.append(" · Min ").append(money(bill.minimumAmountDue()));
        }
        return message(bill, card(bill) + ": bill " + phrase, body.toString());
    }

    public static PushMessage overdue(CardBill bill) {
        long days = bill.daysUntilDue() == null ? 1 : Math.max(1, -bill.daysUntilDue());
        StringBuilder body = new StringBuilder(money(bill.remainingAmount()))
                .append(" was due ").append(date(bill.paymentDueDate())).append(". Mark it paid once it's done.");
        if (bill.paidAmount() != null && bill.paidAmount().signum() > 0) {
            body.append(" ").append(money(bill.paidAmount())).append(" already paid.");
        }
        return message(bill, card(bill) + ": bill overdue by " + days + (days == 1 ? " day" : " days"), body.toString());
    }

    // ---------------------------------------------------------------- formatting

    static String card(CardBill bill) {
        String name = bill.accountName() == null ? "Card" : bill.accountName();
        return bill.last4() == null || bill.last4().isBlank() ? name : name + " ••" + bill.last4();
    }

    static String date(LocalDate date) {
        return date == null ? "—" : DATE.format(date);
    }

    /** Indian grouping (12,34,567.50); whole amounts drop the paise. JDK formatters cannot do the 2-2-3 grouping. */
    static String money(BigDecimal amount) {
        if (amount == null) {
            return "₹—";
        }
        BigDecimal abs = amount.abs().setScale(2, RoundingMode.HALF_UP);
        String[] split = abs.toPlainString().split("\\.");
        String whole = split[0];
        StringBuilder grouped = new StringBuilder();
        if (whole.length() > 3) {
            String head = whole.substring(0, whole.length() - 3);
            String tail = whole.substring(whole.length() - 3);
            StringBuilder headGrouped = new StringBuilder();
            for (int i = head.length(); i > 0; i -= 2) {
                int start = Math.max(0, i - 2);
                if (headGrouped.length() > 0) {
                    headGrouped.insert(0, ',');
                }
                headGrouped.insert(0, head, start, i);
            }
            grouped.append(headGrouped).append(',').append(tail);
        } else {
            grouped.append(whole);
        }
        String paise = split.length > 1 ? split[1] : "00";
        String text = "00".equals(paise) ? grouped.toString() : grouped + "." + paise;
        return (amount.signum() < 0 ? "-₹" : "₹") + text;
    }

    static String points(BigDecimal points) {
        return points.setScale(0, RoundingMode.HALF_UP).toPlainString();
    }

    private static BigDecimal nz(BigDecimal value) {
        return value == null ? BigDecimal.ZERO : value;
    }

    private static PushMessage message(CardBill bill, String title, String body) {
        return new PushMessage(title, body, "/dashboard?bill=" + bill.statementId(), "bill-" + bill.statementId());
    }
}
