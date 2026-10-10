package com.financeos.domain.instrument;

/**
 * Where an instrument's {@link AssetClass} came from. {@code AMFI}: the scheme-category header of
 * the AMFI NAV feed; {@code RULE}: the instrument type (stocks) or name rules (ETFs, funds the feed
 * does not list) — the two stored on the shared instrument. {@code MANUAL}: the reading user's own
 * override ({@link UserInstrumentOverride}); reported on responses only, never stored on the
 * instrument, and invisible to every other user.
 */
public enum AssetClassSource {
    AMFI,
    RULE,
    MANUAL
}
