package com.financeos.domain.instrument.corporateaction;

public enum CorporateActionType {
    split,
    bonus,
    demerger,
    merger,
    /** The company stopped trading; the lot engine ignores it for now (its behaviour is added later). */
    delisted;

    /**
     * Same-day order: 0 keeps the instrument alive, 1 demerger, 2 merger/delisted (they end it).
     * Record-date holders receive the demerged shares, then what remains merges or delists.
     */
    public int sameDayRank() {
        return switch (this) {
            case demerger -> 1;
            case merger, delisted -> 2;
            default -> 0;
        };
    }
}
