package com.financeos.domain.dashboard;

import com.financeos.domain.account.AccountType;
import org.springframework.lang.Nullable;

/**
 * Whether the current user can use a built-in widget yet. Evaluated per user when the catalog is
 * listed ({@code GET /dashboards/builtins}); a non-null reason greys the entry out in the picker
 * ("Add a credit card first"). Checks read {@link Facts}, which are computed lazily and at most
 * once per catalog request, so an entry only pays for the facts it asks about.
 */
@FunctionalInterface
public interface BuiltinAvailability {

    /** Null when the widget is usable, else a short sentence telling the user what to add first. */
    @Nullable
    String unavailableReason(Facts facts);

    /** Usable by everyone (the four original entries). */
    BuiltinAvailability ALWAYS = facts -> null;

    /** Usable once the user has at least one account of {@code type}. */
    static BuiltinAvailability requiresAccountOfType(AccountType type, String reason) {
        return facts -> facts.hasAccountOfType(type) ? null : reason;
    }

    /** Usable once the user has at least one account of any type. */
    static BuiltinAvailability requiresAnyAccount(String reason) {
        return facts -> facts.hasAnyAccount() ? null : reason;
    }

    /** Usable once the user has at least one loan. */
    static BuiltinAvailability requiresLoan(String reason) {
        return facts -> facts.hasLoan() ? null : reason;
    }

    /** Usable once the user holds (or has held) at least one instrument. */
    static BuiltinAvailability requiresHoldings(String reason) {
        return facts -> facts.hasHoldings() ? null : reason;
    }

    /** Usable once the user has recorded at least one lending entry. */
    static BuiltinAvailability requiresLending(String reason) {
        return facts -> facts.hasLending() ? null : reason;
    }

    /** What the checks may ask about the current user. */
    interface Facts {
        boolean hasAccountOfType(AccountType type);

        boolean hasAnyAccount();

        boolean hasLoan();

        boolean hasHoldings();

        boolean hasLending();
    }
}
