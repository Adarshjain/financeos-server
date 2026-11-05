package com.financeos.domain.instrument.price;

import java.util.Set;
import java.util.UUID;

/** Published on the backfill thread after a history backfill wrote rows for these instruments. */
public record PriceHistoryBackfilledEvent(Set<UUID> instrumentIds) {
}
