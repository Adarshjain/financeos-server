package com.financeos.api.investment.dto;

import org.springframework.lang.Nullable;

import java.math.BigDecimal;

public record ItemizedChargesDto(
        @Nullable BigDecimal brokerage,
        @Nullable BigDecimal stt,
        @Nullable BigDecimal exchangeTxnCharges,
        @Nullable BigDecimal sebiCharges,
        @Nullable BigDecimal stampDuty,
        @Nullable BigDecimal gst,
        @Nullable BigDecimal dpCharges,
        @Nullable BigDecimal otherCharges
) {
    public static ItemizedChargesDto empty() {
        return new ItemizedChargesDto(null, null, null, null, null, null, null, null);
    }
}
