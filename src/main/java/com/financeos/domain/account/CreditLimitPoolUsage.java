package com.financeos.domain.account;

import org.springframework.lang.Nullable;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * A shared limit with what its accounts owe on it right now.
 *
 * @param owed        the sum of {@link CardUtilization#owed} over every member account, closed ones
 *                    included (a closed card with an unpaid balance still holds part of the limit;
 *                    once paid it owes zero)
 * @param memberCount every account in the pool, open or closed
 * @param openMemberCount accounts in the pool that are not closed
 */
public record CreditLimitPoolUsage(
        UUID id,
        String name,
        BigDecimal creditLimit,
        BigDecimal owed,
        int memberCount,
        int openMemberCount
) {
    /** What is left to spend across the pool, never negative. */
    public BigDecimal available() {
        return creditLimit.subtract(owed).max(BigDecimal.ZERO);
    }

    /** {@code owed ÷ creditLimit × 100} at {@link CardUtilization#SCALE}. */
    @Nullable
    public BigDecimal utilizationPct() {
        return CardUtilization.pct(owed.negate(), creditLimit, null);
    }
}
