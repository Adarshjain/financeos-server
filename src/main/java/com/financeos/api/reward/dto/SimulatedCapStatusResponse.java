package com.financeos.api.reward.dto;

import com.financeos.domain.reward.CapWindow;

import java.math.BigDecimal;
import java.time.LocalDate;

public record SimulatedCapStatusResponse(
        CapWindow capWindow,
        BigDecimal totalCap,
        BigDecimal usedBefore,
        BigDecimal capRemainingBefore,
        LocalDate windowEnd,
        String bucketName
) {
}
