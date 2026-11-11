package com.financeos.domain.instrument.corporateaction;

public enum CorporateActionType {
    split,
    bonus,
    demerger,
    merger,
    /** The company stopped trading; the lot engine ignores it for now (its behaviour is added later). */
    delisted
}
