package com.financeos.domain.notification.emi;

import com.financeos.api.loan.dto.InstallmentDto;
import com.financeos.domain.loan.Loan;
import com.financeos.domain.notification.MessageFormat;
import com.financeos.domain.notification.bill.BillNotificationKinds;
import com.financeos.domain.notification.push.PushMessage;

/**
 * Push texts for EMI reminders. EMIs are normally auto-debited, so the wording is "debits on
 * {date} from {account}", not "pay your EMI"; the overdue text asks to record the payment.
 */
public final class EmiMessages {

    private EmiMessages() {
    }

    public static PushMessage forKind(String kind, Loan loan, InstallmentDto installment, int totalInstallments, long daysUntilDue) {
        if (BillNotificationKinds.OVERDUE.equals(kind)) {
            return overdue(loan, installment, totalInstallments, daysUntilDue);
        }
        if (BillNotificationKinds.isDue(kind)) {
            return due(loan, installment, totalInstallments, daysUntilDue);
        }
        throw new IllegalArgumentException("No EMI message for kind " + kind);
    }

    public static PushMessage due(Loan loan, InstallmentDto installment, int totalInstallments, long daysUntilDue) {
        StringBuilder body = new StringBuilder(MessageFormat.money(installment.emi()))
                .append(" on ").append(MessageFormat.date(installment.dueDate()));
        if (loan.getPaymentAccount() != null && loan.getPaymentAccount().getName() != null) {
            body.append(" from ").append(loan.getPaymentAccount().getName());
        }
        body.append(" · ").append(position(installment, totalInstallments));
        return message(loan, installment, loan.getName() + ": EMI " + MessageFormat.inDays("debits", daysUntilDue), body.toString());
    }

    public static PushMessage overdue(Loan loan, InstallmentDto installment, int totalInstallments, long daysUntilDue) {
        long days = Math.max(1, -daysUntilDue);
        String body = MessageFormat.money(installment.emi()) + " was due " + MessageFormat.date(installment.dueDate())
                + ". Record the payment once it's done. · " + position(installment, totalInstallments);
        return message(loan, installment, loan.getName() + ": EMI overdue by " + MessageFormat.days(days), body);
    }

    private static String position(InstallmentDto installment, int total) {
        return "#" + installment.seq() + " of " + total;
    }

    private static PushMessage message(Loan loan, InstallmentDto installment, String title, String body) {
        return new PushMessage(title, body, "/loans/" + loan.getId() + "?installment=" + installment.seq(), "emi-" + loan.getId());
    }
}
