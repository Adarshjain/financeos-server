package com.financeos.api.lending.dto;

import com.financeos.domain.lending.Counterparty;

import java.math.BigDecimal;
import java.util.UUID;

public record CounterpartyResponse(
        UUID id,
        String name,
        String notes,
        BigDecimal lentOutstanding,
        BigDecimal borrowedOutstanding,
        BigDecimal netPosition,
        long openLendingCount
) {
    public static CounterpartyResponse from(Counterparty cp, BigDecimal lentOutstanding, BigDecimal borrowedOutstanding, long openLendingCount) {
        BigDecimal lent = lentOutstanding != null ? lentOutstanding : BigDecimal.ZERO;
        BigDecimal borrowed = borrowedOutstanding != null ? borrowedOutstanding : BigDecimal.ZERO;
        BigDecimal net = lent.subtract(borrowed);
        return new CounterpartyResponse(
                cp.getId(),
                cp.getName(),
                cp.getNotes(),
                lent,
                borrowed,
                net,
                openLendingCount
        );
    }
}
