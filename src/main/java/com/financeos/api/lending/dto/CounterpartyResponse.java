package com.financeos.api.lending.dto;

import com.financeos.domain.lending.Counterparty;

import org.springframework.lang.Nullable;

import com.financeos.domain.lending.LendingDirection;
import com.financeos.domain.lending.LendingKind;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

/**
 * Per-person ledger totals. {@code totalLent} / {@code totalBorrowed} are principal only
 * (new money); {@code repaidToYou} / {@code repaidByYou} are settlements (money in / money
 * out that cleared a balance). {@code netPosition} nets every entry by money direction:
 * positive = they owe you. {@code lastEntry*} describe the most recent entry (ledger order);
 * {@code firstEntryDate} is the earliest entry date (null with no entries) and {@code linkedEntryCount}
 * counts entries that reference a transaction; {@code expectedReturnDate} is the earliest expected return among principal entries in the
 * outstanding direction, null when settled or none set.
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
        long entryCount,
        @Nullable LocalDate lastEntryDate,
        @Nullable LendingDirection lastEntryType,
        @Nullable LendingKind lastEntryKind,
        @Nullable BigDecimal lastEntryAmount,
        @Nullable LocalDate expectedReturnDate,
        @Nullable LocalDate firstEntryDate,
        int linkedEntryCount
) {
    /** Totals only, without the last-entry / expected-return fields. */
    public CounterpartyResponse(UUID id, String name, @Nullable String notes, BigDecimal totalLent, BigDecimal totalBorrowed,
                                BigDecimal repaidToYou, BigDecimal repaidByYou, BigDecimal netPosition, long entryCount) {
        this(id, name, notes, totalLent, totalBorrowed, repaidToYou, repaidByYou, netPosition, entryCount, null, null, null, null, null, null, 0);
    }

    public static CounterpartyResponse from(Counterparty cp, BigDecimal totalLent, BigDecimal totalBorrowed,
                                            BigDecimal repaidToYou, BigDecimal repaidByYou, long entryCount) {
        return from(cp, totalLent, totalBorrowed, repaidToYou, repaidByYou, entryCount, null, null, null, null, null, null, 0);
    }

    public static CounterpartyResponse from(Counterparty cp, BigDecimal totalLent, BigDecimal totalBorrowed,
                                            BigDecimal repaidToYou, BigDecimal repaidByYou, long entryCount,
                                            @Nullable LocalDate lastEntryDate, @Nullable LendingDirection lastEntryType,
                                            @Nullable LendingKind lastEntryKind,
                                            @Nullable BigDecimal lastEntryAmount, @Nullable LocalDate expectedReturnDate,
                                            @Nullable LocalDate firstEntryDate, int linkedEntryCount) {
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
                entryCount,
                lastEntryDate,
                lastEntryType,
                lastEntryKind,
                lastEntryAmount,
                net.signum() == 0 ? null : expectedReturnDate,
                firstEntryDate,
                linkedEntryCount
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
