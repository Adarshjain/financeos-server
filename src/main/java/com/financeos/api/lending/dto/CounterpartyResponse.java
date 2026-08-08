package com.financeos.api.lending.dto;

import com.financeos.domain.lending.Counterparty;

import java.math.BigDecimal;
import java.util.UUID;

public record CounterpartyResponse(
        UUID id,
        String name,
        String notes,
        BigDecimal totalLent,
        BigDecimal totalBorrowed,
        BigDecimal netPosition,
        long entryCount
) {
    public static CounterpartyResponse from(Counterparty cp, BigDecimal totalLent, BigDecimal totalBorrowed, long entryCount) {
        BigDecimal lent = totalLent != null ? totalLent : BigDecimal.ZERO;
        BigDecimal borrowed = totalBorrowed != null ? totalBorrowed : BigDecimal.ZERO;
        BigDecimal net = lent.subtract(borrowed);
        return new CounterpartyResponse(
                cp.getId(),
                cp.getName(),
                cp.getNotes(),
                lent,
                borrowed,
                net,
                entryCount
        );
    }
}
