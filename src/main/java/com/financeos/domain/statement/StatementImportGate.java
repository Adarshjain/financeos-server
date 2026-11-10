package com.financeos.domain.statement;

import com.financeos.gmail.reconcile.StatementExtractionResult;
import com.financeos.gmail.reconcile.StatementParser;

import java.util.Optional;

/**
 * Decides whether a parse is trustworthy enough to create a statement and rows. A file that gives neither an
 * account/card number nor a period end is not a statement; a REJECTED parse whose own figures contradict its rows
 * is never imported. A REJECTED parse with nothing to check against (no balances, no totals) still imports.
 */
public final class StatementImportGate {

    public enum Reason { NOT_A_STATEMENT, CONTRADICTED }

    public record Blocked(Reason reason, String message) {}

    private static final Blocked NOT_A_STATEMENT = new Blocked(Reason.NOT_A_STATEMENT,
            "This file doesn't look like an account statement, so nothing was imported.");
    private static final Blocked CONTRADICTED = new Blocked(Reason.CONTRADICTED,
            "This statement's rows don't add up to its own totals, so nothing was imported.");

    private StatementImportGate() {}

    public static Optional<Blocked> check(StatementExtractionResult result) {
        boolean hasNumber = result.accountNumber() != null && !result.accountNumber().isBlank();
        if (!hasNumber && result.periodEnd() == null) {
            return Optional.of(NOT_A_STATEMENT);
        }
        StatementDraft draft = result.draft();
        if (draft != null && draft.verdict() == StatementVerdict.REJECTED && contradicted(draft)) {
            return Optional.of(CONTRADICTED);
        }
        return Optional.empty();
    }

    private static boolean contradicted(StatementDraft draft) {
        return Boolean.FALSE.equals(draft.checksumOk())
                || StatementParser.MODE_BALANCE_CHAIN.equals(draft.parseMode());
    }
}
