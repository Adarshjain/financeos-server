package com.financeos.domain.report.underlying;

import org.springframework.lang.Nullable;

import java.math.BigDecimal;

/**
 * An item a computed datasource left out of the KPI figure (net worth's "Not counted").
 *
 * @param id          the item's id (account / loan / counterparty)
 * @param kind        the item's kind, using the datasource's kind values
 * @param reason      {@code excluded} / {@code closed} / {@code error}
 * @param reasonLabel the reason for people, e.g. {@code Closed on 01/10/2026}
 * @param value       the item's calculated value when known, else null
 */
public record UnderlyingExcludedItem(
        String id,
        String name,
        String kind,
        String reason,
        String reasonLabel,
        @Nullable BigDecimal value) {
}
