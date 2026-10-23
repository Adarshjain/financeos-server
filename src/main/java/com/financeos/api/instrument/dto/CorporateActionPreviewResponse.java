package com.financeos.api.instrument.dto;

import org.springframework.lang.Nullable;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

/**
 * What a corporate action would do to each of the caller's holdings of the instrument; nothing is saved.
 *
 * @param holdings one row per broker account where the caller holds the instrument (empty when none)
 */
public record CorporateActionPreviewResponse(List<HoldingEffect> holdings) {

    /**
     * One broker account's holding: open quantity, average cost per unit and total cost of the open quantity
     * before and after the action.
     *
     * @param childInstrumentId  demerger / merger target; null for a split or bonus
     * @param childQuantity      units of the target this holding receives (the change in the caller's target
     *                           holding at that broker); null without a target
     * @param childCost          cost carried into those units; null without a target
     * @param fractionalRealized realised result of the fractional units settled for cash in lieu (cash
     *                           received minus their cost); null without a target
     */
    public record HoldingEffect(
            UUID brokerAccountId,
            String brokerAccountName,
            BigDecimal quantityBefore,
            BigDecimal quantityAfter,
            BigDecimal avgCostBefore,
            BigDecimal avgCostAfter,
            BigDecimal openCostBefore,
            BigDecimal openCostAfter,
            @Nullable UUID childInstrumentId,
            @Nullable BigDecimal childQuantity,
            @Nullable BigDecimal childCost,
            @Nullable BigDecimal fractionalRealized
    ) {
    }
}
