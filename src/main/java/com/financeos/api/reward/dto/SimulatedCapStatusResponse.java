package com.financeos.api.reward.dto;

import com.financeos.domain.reward.CapWindow;

import org.springframework.lang.Nullable;

import java.math.BigDecimal;
import java.time.LocalDate;

public record SimulatedCapStatusResponse(
        CapWindow capWindow,
        BigDecimal totalCap,
        BigDecimal usedBefore,
        BigDecimal capRemainingBefore,
        LocalDate windowEnd,
        @Nullable String bucketName
) {
}
