package com.financeos.domain.notification.reminder;

import java.util.UUID;

/** The feed dedupe keys of the in-place reminders: one row per card bill, one per EMI installment. */
public final class ReminderKeys {

    private ReminderKeys() {
    }

    public static String bill(UUID statementId) {
        return "bill:" + statementId;
    }

    public static String emi(UUID loanId, int seq) {
        return "emi:" + loanId + ":" + seq;
    }
}
