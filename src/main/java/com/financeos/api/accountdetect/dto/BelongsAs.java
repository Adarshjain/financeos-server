package com.financeos.api.accountdetect.dto;

/** What a found number is on the account the user says it belongs to. */
public enum BelongsAs {
    /** An older (closed) card number of the account's primary holder. */
    OLDER_NUMBER,
    /** A card of a new add-on holder. */
    ADDON,
    /** Another open card of mine on this credit-card account (primary holder, who must have no active card). */
    OPEN_CARD,
    /** A bank account's debit card (primary holder, who must have no active card). */
    DEBIT_CARD,
    /** The same bank account under another number. */
    ALIAS
}
