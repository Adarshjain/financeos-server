package com.financeos.domain.notification.lending;

import com.financeos.domain.lending.Lending;
import com.financeos.domain.lending.LendingDirection;
import com.financeos.domain.notification.MessageFormat;
import com.financeos.domain.notification.bill.BillNotificationKinds;
import com.financeos.domain.notification.push.PushMessage;
import java.math.BigDecimal;
import java.util.UUID;

/**
 * Push texts for lending returns. Lent money taps into the person's ledger with the share dialog
 * open (the natural action is to nudge them); borrowed money taps into the ledger to settle up.
 */
public final class LendingMessages {

    private LendingMessages() {
    }

    public static PushMessage forKind(String kind, Lending subject, String counterpartyName, UUID counterpartyId,
                                      BigDecimal outstanding, LendingDirection direction, long daysUntilDue) {
        boolean lent = direction == LendingDirection.lent;
        String amount = MessageFormat.money(outstanding);
        String title;
        String body;
        if (BillNotificationKinds.OVERDUE.equals(kind)) {
            long late = Math.max(1, -daysUntilDue);
            if (lent) {
                title = counterpartyName + ": " + amount + " overdue by " + MessageFormat.days(late);
                body = "Expected back " + MessageFormat.date(subject.getExpectedReturnDate()) + ". Tap to share the ledger.";
            } else {
                title = counterpartyName + ": you owe " + amount + ", " + MessageFormat.days(late) + " late";
                body = "Was due back " + MessageFormat.date(subject.getExpectedReturnDate()) + ". Settle up and record it.";
            }
        } else {
            title = counterpartyName + ": " + amount + " due back today";
            body = lent
                    ? "You lent it on " + MessageFormat.date(subject.getEntryDate()) + ". Tap to share the ledger."
                    : "You borrowed it on " + MessageFormat.date(subject.getEntryDate()) + ". Time to settle up.";
        }
        String url = "/loans/lendings/" + counterpartyId + (lent ? "?export=1" : "");
        return new PushMessage(title, body, url, "lending-" + counterpartyId);
    }
}
