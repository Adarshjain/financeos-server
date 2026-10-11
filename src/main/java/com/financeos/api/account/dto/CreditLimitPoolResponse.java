package com.financeos.api.account.dto;

import com.financeos.domain.account.CreditLimitPoolUsage;
import org.springframework.lang.Nullable;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * The shared limit a credit card account draws on, with live usage across all of its accounts.
 * {@code owed} counts every member (closed ones too, since an unpaid closed card still holds part of
 * the limit); {@code openMemberCount} is what decides whether the pool counts towards total limit.
 */
public record CreditLimitPoolResponse(
        UUID id,
        String name,
        BigDecimal creditLimit,
        BigDecimal owed,
        BigDecimal available,
        @Nullable BigDecimal utilizationPct,
        int memberCount,
        int openMemberCount
) {
    @Nullable
    public static CreditLimitPoolResponse from(@Nullable CreditLimitPoolUsage usage) {
        if (usage == null) {
            return null;
        }
        return new CreditLimitPoolResponse(usage.id(), usage.name(), usage.creditLimit(), usage.owed(),
                usage.available(), usage.utilizationPct(), usage.memberCount(), usage.openMemberCount());
    }
}
