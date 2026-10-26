package com.financeos.api.search.dto;

import java.util.UUID;

/** An instrument the user holds, matched by (override-aware) name or symbol; one per instrument. */
public record SearchHoldingHit(UUID instrumentId, String name, String symbol) {
}
