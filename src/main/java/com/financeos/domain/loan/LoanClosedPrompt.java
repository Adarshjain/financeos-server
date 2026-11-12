package com.financeos.domain.loan;

import com.financeos.api.loan.dto.InstallmentDto;
import com.financeos.api.loan.dto.LoanCloseEvidence;
import com.financeos.core.time.AppTime;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
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
        LocalDate oldestUnpaidDue = oldestUnpaid(installments) == null ? null : oldestUnpaid(installments).dueDate();
        if (oldestUnpaidDue == null || oldestUnpaidDue.isAfter(today.minusDays(OVERDUE_DAYS))) {
            return false;
        }
        if (dismissedAt == null) {
            return true;
        }
        LocalDate dismissedOn = LocalDate.ofInstant(dismissedAt, AppTime.zone());
        return !dismissedOn.isAfter(today.minusDays(REST_DAYS));
    }

    /** The unsettled installment with the earliest due date, or null. */
    @Nullable
    private static InstallmentDto oldestUnpaid(List<InstallmentDto> installments) {
        InstallmentDto oldest = null;
        for (InstallmentDto installment : installments) {
            if (!"settled".equals(installment.status()) && installment.dueDate() != null
                    && (oldest == null || installment.dueDate().isBefore(oldest.dueDate()))) {
                oldest = installment;
            }
        }
        return oldest;
    }

    /** The facts behind the prompt, from the same schedule; null when there is no unpaid installment. */
    @Nullable
    public static LoanCloseEvidence evidence(List<InstallmentDto> installments, BigDecimal outstandingPrincipal, LocalDate today) {
        if (installments == null) {
            return null;
        }
        InstallmentDto oldest = oldestUnpaid(installments);
        if (oldest == null) {
            return null;
        }
        InstallmentDto.PaymentInfo last = null;
        int settled = 0;
        for (InstallmentDto installment : installments) {
            if ("settled".equals(installment.status())) {
                settled++;
            }
            InstallmentDto.PaymentInfo payment = installment.payment();
            if (payment != null && payment.paymentDate() != null
                    && (last == null || payment.paymentDate().isAfter(last.paymentDate()))) {
                last = payment;
            }
        }
        return new LoanCloseEvidence(oldest.seq(), oldest.dueDate(), (int) ChronoUnit.DAYS.between(oldest.dueDate(), today),
                last == null ? null : last.paymentDate(), last == null ? null : last.amount(),
                settled, installments.size(), outstandingPrincipal);
    }
}
