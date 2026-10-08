package com.financeos.domain.inbox.collect;

import static com.financeos.api.inbox.dto.InboxItemResponse.SECTION_ACT_NOW;
import static com.financeos.api.inbox.dto.InboxItemResponse.SEVERITY_CRITICAL;
import static com.financeos.api.inbox.dto.InboxItemResponse.SEVERITY_WARNING;

import com.financeos.api.inbox.dto.InboxItemResponse;
import com.financeos.api.inbox.dto.InboxRefsResponse;
import com.financeos.api.loan.dto.InstallmentDto;
import com.financeos.domain.inbox.InboxKinds;
import com.financeos.domain.loan.Loan;
import com.financeos.domain.loan.LoanService;
import com.financeos.domain.loan.LoanStatus;
import com.financeos.domain.notification.emi.EmiNotificationService;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * The current installment of every active loan: overdue (unless nobody has been recording this
 * loan's payments for ages) or due within a week. Muted loans stay out.
 */
@Component
public class EmiInboxCollector implements InboxCollector {

    private final LoanService loanService;

    public EmiInboxCollector(LoanService loanService) {
        this.loanService = loanService;
    }

    @Override
    public List<InboxItemResponse> collect(UUID userId, LocalDate today) {
        List<InboxItemResponse> rows = new ArrayList<>();
        for (LoanService.LoanWithSchedule entry : loanService.getAllLoansWithSchedule()) {
            Loan loan = entry.loan();
            if (loan.getStatus() != LoanStatus.active || Boolean.TRUE.equals(loan.getNotificationsMuted())) {
                continue;
            }
            InstallmentDto current = EmiNotificationService.currentInstallment(entry.schedule().installments());
            if (current == null || current.dueDate() == null) {
                continue;
            }
            long days = ChronoUnit.DAYS.between(today, current.dueDate());
            String severity;
            if (days < 0) {
                if (EmiNotificationService.isStale(loan, current, today)) {
                    continue;
                }
                severity = SEVERITY_CRITICAL;
            } else if (days <= InboxKinds.DUE_SOON_DAYS) {
                severity = SEVERITY_WARNING;
            } else {
                continue;
            }
            rows.add(row(loan, current, days, severity));
        }
        return rows;
    }

    private static InboxItemResponse row(Loan loan, InstallmentDto installment, long days, String severity) {
        String title = loan.getName() + ": EMI #" + installment.seq();
        StringBuilder subtitle = new StringBuilder();
        if (days < 0) {
            subtitle.append(InboxRows.dueText(days)).append(" · was due ").append(InboxRows.date(installment.dueDate()))
                    .append(" · record the payment once it's done");
        } else {
            String debits = days == 0 ? "Debits today" : days == 1 ? "Debits tomorrow" : "Debits in " + days + " days";
            subtitle.append(debits).append(" · ").append(InboxRows.date(installment.dueDate()));
            if (loan.getPaymentAccount() != null && loan.getPaymentAccount().getName() != null) {
                subtitle.append(" from ").append(loan.getPaymentAccount().getName());
            }
        }
        String href = "/loans/" + loan.getId() + "?installment=" + installment.seq();
        return InboxRows.item(InboxKinds.emiKey(loan.getId(), installment.seq()), InboxKinds.EMI, severity, SECTION_ACT_NOW,
                title, subtitle.toString(), href, installment.emi(), installment.dueDate(),
                List.of(InboxRows.open(href), InboxRows.snooze()), InboxRefsResponse.ofLoan(loan.getId()));
    }
}
