package com.financeos.api.lending.dto;

import com.financeos.domain.lending.Counterparty;

import org.springframework.lang.Nullable;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * Per-person ledger totals. {@code totalLent} / {@code totalBorrowed} are principal only
 * (new money); {@code repaidToYou} / {@code repaidByYou} are settlements (money in / money
 * out that cleared a balance). {@code netPosition} nets every entry by money direction:
 * positive = they owe you.
 */
public record CounterpartyResponse(
        UUID id,
        String name,
        @Nullable String notes,
        BigDecimal totalLent,
        BigDecimal totalBorrowed,
        BigDecimal repaidToYou,
        BigDecimal repaidByYou,
        BigDecimal netPosition,
        long entryCount
) {
    public static CounterpartyResponse from(Counterparty cp, BigDecimal totalLent, BigDecimal totalBorrowed,
                                            BigDecimal repaidToYou, BigDecimal repaidByYou, long entryCount) {
        BigDecimal lent = orZero(totalLent);
        BigDecimal borrowed = orZero(totalBorrowed);
        BigDecimal toYou = orZero(repaidToYou);
        BigDecimal byYou = orZero(repaidByYou);
        // money out (lent + you repaid) minus money in (borrowed + they repaid)
        BigDecimal net = lent.add(byYou).subtract(borrowed).subtract(toYou);
        return new CounterpartyResponse(
                cp.getId(),
                cp.getName(),
                cp.getNotes(),
                lent,
                borrowed,
                toYou,
                byYou,
                net,
                entryCount
        );
    }

    /** Totals with no settlements (a freshly created person, or callers that only track principal). */
    public static CounterpartyResponse from(Counterparty cp, BigDecimal totalLent, BigDecimal totalBorrowed, long entryCount) {
        return from(cp, totalLent, totalBorrowed, BigDecimal.ZERO, BigDecimal.ZERO, entryCount);
    }

    private static BigDecimal orZero(@Nullable BigDecimal value) {
        return value != null ? value : BigDecimal.ZERO;
    }
}
