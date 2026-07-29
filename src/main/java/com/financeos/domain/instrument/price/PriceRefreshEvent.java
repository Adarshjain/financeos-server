package com.financeos.domain.instrument.price;

import java.util.Set;
import java.util.UUID;

/**
 * Published after a tradebook change (manual buy/sell or bulk import) commits, carrying the
 * distinct instrument IDs that were touched. Handled by {@link PriceRefreshEventListener} which
 * fetches the latest price for each so the UI reflects it without a manual "Refresh Prices" click.
 */
public record PriceRefreshEvent(Set<UUID> instrumentIds) {
}
