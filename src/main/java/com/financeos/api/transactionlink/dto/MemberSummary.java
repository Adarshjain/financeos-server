package com.financeos.api.transactionlink.dto;

import org.springframework.lang.Nullable;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

public record MemberSummary(
        UUID transactionId,
        LocalDate date,
        BigDecimal signedAmount,
        @Nullable String description,
        UUID accountId,
        boolean isAnchor,
        String roleLabel
) {}
