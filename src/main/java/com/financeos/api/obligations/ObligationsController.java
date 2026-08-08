package com.financeos.api.obligations;

import com.financeos.api.loan.dto.InstallmentDto;
import com.financeos.api.loan.dto.LoanResponse;
import com.financeos.api.obligations.dto.ObligationItemDto;
import com.financeos.api.obligations.dto.ObligationsResponse;
import com.financeos.domain.lending.LendingService;
import com.financeos.domain.loan.LoanService;
import com.financeos.domain.loan.LoanStatus;

import org.springframework.data.domain.Pageable;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

@RestController
@RequestMapping("/api/v1/obligations")
public class ObligationsController {

    private final LoanService loanService;
    private final LendingService lendingService;

    public ObligationsController(LoanService loanService, LendingService lendingService) {
        this.loanService = loanService;
        this.lendingService = lendingService;
    }

    @GetMapping("/upcoming")
    public ObligationsResponse getUpcomingObligations(@RequestParam(defaultValue = "3") int months) {
        int windowMonths = Math.max(1, Math.min(12, months));
        LocalDate today = LocalDate.now();
        LocalDate maxDate = today.plusMonths(windowMonths);

        List<ObligationItemDto> items = new ArrayList<>();

        // 1. EMI Obligations from Active Loans
        List<LoanResponse> activeLoans = loanService.getLoans(LoanStatus.active, Pageable.unpaged()).getContent();

        for (LoanResponse loan : activeLoans) {
            List<InstallmentDto> installments = loanService.getLoanSchedule(loan.id());
            for (InstallmentDto inst : installments) {
                if (!"settled".equals(inst.status())) {
                    LocalDate due = inst.dueDate();
                    boolean isOverdue = due.isBefore(today);
                    boolean isUpcoming = !due.isBefore(today) && !due.isAfter(maxDate);

                    if (isOverdue || isUpcoming) {
                        items.add(new ObligationItemDto(
                                "emi",
                                due,
                                inst.emi(),
                                isOverdue ? "overdue" : "upcoming",
                                loan.id(),
                                loan.name(),
                                inst.seq(),
                                null,
                                null,
                                null,
                                null
                        ));
                    }
                }
            }
        }

        // 2. Lending Due Obligations
        List<ObligationItemDto> lendingItems = lendingService.getUpcomingLendingObligations(today, maxDate);
        items.addAll(lendingItems);

        // Sort: overdue first (oldest first), then upcoming by date ascending
        items.sort((a, b) -> {
            boolean aOverdue = "overdue".equalsIgnoreCase(a.status());
            boolean bOverdue = "overdue".equalsIgnoreCase(b.status());
            if (aOverdue && !bOverdue) return -1;
            if (!aOverdue && bOverdue) return 1;
            return a.date().compareTo(b.date());
        });

        return new ObligationsResponse(items);
    }
}
