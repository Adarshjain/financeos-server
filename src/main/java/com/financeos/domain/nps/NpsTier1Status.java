package com.financeos.domain.nps;

/** What the latest statement says about the account's Tier I status. */
public enum NpsTier1Status {
    ACTIVE,
    FROZEN,
    OTHER;

    /** Maps the statement's "Tier I Status" text; null for blank (no change). */
    public static NpsTier1Status fromStatement(String text) {
        if (text == null || text.isBlank()) {
            return null;
        }
        String t = text.trim().toUpperCase(java.util.Locale.ROOT);
        if (t.equals("ACTIVE")) {
            return ACTIVE;
        }
        return t.contains("FROZEN") ? FROZEN : OTHER;
    }
}
