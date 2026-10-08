package com.financeos.domain.notification;

import com.financeos.domain.notification.bill.BillNotificationKinds;
import java.time.LocalDate;

/**
 * The shared "due → overdue" decision for producers whose subject carries a {@code DUE_n} /
 * {@code OVERDUE} marker (EMIs, lending returns): a kind is sent only when it ranks later than the
 * recorded one, and OVERDUE additionally repeats every {@code renagDays}.
 */
public final class ReminderSequence {

    private ReminderSequence() {
    }

    /** OVERDUE once the date has passed; otherwise the smallest configured offset already reached. */
    public static String applicableKind(long daysUntilDue, Iterable<Integer> offsets) {
        if (daysUntilDue < 0) {
            return BillNotificationKinds.OVERDUE;
        }
        Integer best = null;
        for (Integer offset : offsets) {
            if (offset != null && offset >= daysUntilDue && (best == null || offset < best)) {
                best = offset;
            }
        }
        return best == null ? null : BillNotificationKinds.dueIn(best);
    }

    public static boolean shouldSend(String kind, String lastKind, LocalDate lastOn, LocalDate today, int renagDays) {
        if (kind == null) {
            return false;
        }
        if (BillNotificationKinds.OVERDUE.equals(kind)) {
            if (BillNotificationKinds.OVERDUE.equals(lastKind)) {
                return lastOn == null || !lastOn.isAfter(today.minusDays(renagDays));
            }
            return BillNotificationKinds.rank(lastKind) < BillNotificationKinds.RANK_OVERDUE;
        }
        return BillNotificationKinds.isLater(kind, lastKind);
    }
}
