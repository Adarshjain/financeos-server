package com.financeos.domain.notification.bill;

/**
 * The per-statement notification sequence, encoded as the string kept in
 * {@code statement_credit_card_details.last_notified_kind}:
 *
 * <pre>RECEIVED / DUE_MISSING  →  DUE_n (larger n first)  →  OVERDUE (repeats daily)  →  PAID</pre>
 *
 * A kind is sent only when it ranks later than the one recorded, which is what makes the
 * hourly tick idempotent and gives catch-up for free: after downtime the most urgent
 * applicable kind goes out once and the ones it overtook are never sent.
 */
public final class BillNotificationKinds {

    public static final String RECEIVED = "RECEIVED";
    public static final String DUE_MISSING = "DUE_MISSING";
    public static final String OVERDUE = "OVERDUE";
    public static final String PAID = "PAID";
    private static final String DUE_PREFIX = "DUE_";

    public static final int RANK_NONE = -1;
    public static final int RANK_RECEIVED = 0;
    public static final int RANK_DUE_BASE = 1000;
    public static final int RANK_OVERDUE = 2000;
    public static final int RANK_PAID = 3000;

    private BillNotificationKinds() {
    }

    /** {@code DUE_7}, {@code DUE_0}, … the reminder that fires {@code daysBeforeDue} days out. */
    public static String dueIn(int daysBeforeDue) {
        if (daysBeforeDue < 0 || daysBeforeDue >= RANK_DUE_BASE) {
            throw new IllegalArgumentException("Reminder offset out of range: " + daysBeforeDue);
        }
        return DUE_PREFIX + daysBeforeDue;
    }

    public static boolean isDue(String kind) {
        return dueOffset(kind) >= 0;
    }

    /** The offset of a {@code DUE_n} kind, or -1 for anything else. */
    public static int dueOffset(String kind) {
        if (kind == null || !kind.startsWith(DUE_PREFIX) || DUE_MISSING.equals(kind)) {
            return -1;
        }
        try {
            int n = Integer.parseInt(kind.substring(DUE_PREFIX.length()));
            return n >= 0 && n < RANK_DUE_BASE ? n : -1;
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    /** Position in the sequence; unknown or null kinds rank before everything. */
    public static int rank(String kind) {
        if (kind == null) {
            return RANK_NONE;
        }
        switch (kind) {
            case RECEIVED:
            case DUE_MISSING:
                return RANK_RECEIVED;
            case OVERDUE:
                return RANK_OVERDUE;
            case PAID:
                return RANK_PAID;
            default:
                int offset = dueOffset(kind);
                return offset >= 0 ? RANK_DUE_BASE + (RANK_DUE_BASE - 1 - offset) : RANK_NONE;
        }
    }

    public static boolean isLater(String kind, String than) {
        return rank(kind) > rank(than);
    }
}
