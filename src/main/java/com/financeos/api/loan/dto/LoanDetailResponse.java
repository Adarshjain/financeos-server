package com.financeos.api.loan.dto;

import java.util.List;

public record LoanDetailResponse(
        LoanResponse loan,
        List<LoanEventResponse> events,
        List<LoanChargeResponse> charges
) {}
