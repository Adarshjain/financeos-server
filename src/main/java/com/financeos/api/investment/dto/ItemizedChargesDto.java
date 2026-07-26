package com.financeos.api.investment.dto;

import java.math.BigDecimal;

public record ItemizedChargesDto(
        BigDecimal brokerage,
        BigDecimal stt,
        BigDecimal exchangeTxnCharges,
        BigDecimal sebiCharges,
        BigDecimal stampDuty,
        BigDecimal gst,
        BigDecimal dpCharges,
        BigDecimal otherCharges
) {
    public static ItemizedChargesDto empty() {
        return new ItemizedChargesDto(null, null, null, null, null, null, null, null);
    }
}
