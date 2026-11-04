package com.financeos.api.loan.dto;

import java.util.List;

public record LoanDetailResponse(
        LoanResponse loan,
        List<LoanEventResponse> events,
        List<LoanChargeResponse> charges,
        /** Ask "Is this loan closed?": active, oldest unpaid installment 45+ days overdue, not dismissed in the last 45 days. */
        boolean askClosed
) {}
