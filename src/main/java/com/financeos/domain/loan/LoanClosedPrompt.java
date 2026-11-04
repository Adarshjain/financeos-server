package com.financeos.domain.loan;

import com.financeos.api.loan.dto.InstallmentDto;
import com.financeos.core.time.AppTime;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import org.springframework.lang.Nullable;

/**
 * "Is this loan closed?": asked when an active loan's oldest unpaid installment is
 * {@value #OVERDUE_DAYS} or more days overdue (the lender stopped billing and nobody recorded it), and not
 * answered "Not yet" within the last {@value #REST_DAYS} days. Pure.
 */
public final class LoanClosedPrompt {

    public static final int OVERDUE_DAYS = 45;
    public static final int REST_DAYS = 45;

    private LoanClosedPrompt() {
    }

    public static boolean ask(LoanStatus status, List<InstallmentDto> installments, @Nullable Instant dismissedAt, LocalDate today) {
        if (status != LoanStatus.active || installments == null) {
            return false;
        }
        LocalDate oldestUnpaidDue = null;
        for (InstallmentDto installment : installments) {
            if (!"settled".equals(installment.status()) && installment.dueDate() != null
                    && (oldestUnpaidDue == null || installment.dueDate().isBefore(oldestUnpaidDue))) {
                oldestUnpaidDue = installment.dueDate();
            }
        }
        if (oldestUnpaidDue == null || oldestUnpaidDue.isAfter(today.minusDays(OVERDUE_DAYS))) {
            return false;
        }
        if (dismissedAt == null) {
            return true;
        }
        LocalDate dismissedOn = LocalDate.ofInstant(dismissedAt, AppTime.zone());
        return !dismissedOn.isAfter(today.minusDays(REST_DAYS));
    }
}
