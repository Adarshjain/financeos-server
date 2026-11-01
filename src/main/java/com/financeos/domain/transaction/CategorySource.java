package com.financeos.domain.transaction;

/** Who set a transaction's category; USER is never overwritten by any other source. */
public enum CategorySource {
    /** The built-in engine. */
    AUTO,
    /** Inherited through a transaction link (e.g. a refund takes the original's category). */
    LINK,
    /** One of the user's own rules. */
    RULE,
    /** The user picked it (or cleared it) by hand. */
    USER
}
