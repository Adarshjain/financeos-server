package com.financeos.domain.category;

import com.financeos.domain.transaction.TransactionType;

/** Which side of the ledger a category or sub-category applies to. */
public enum CategoryDirection {
    OUT, IN, BOTH;

    /** The direction a transaction of this type needs: DEBIT is OUT, CREDIT is IN. */
    public static CategoryDirection of(TransactionType type) {
        return type == TransactionType.CREDIT ? IN : OUT;
    }

    /** True when something of this direction can sit on a transaction that needs {@code needed}. */
    public boolean fits(CategoryDirection needed) {
        return this == BOTH || this == needed;
    }
}
