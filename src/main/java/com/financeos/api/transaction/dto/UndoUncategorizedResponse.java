package com.financeos.api.transaction.dto;

/** {@code restored}: rows set back to no category; {@code skipped}: rows that are gone or no longer carry that pick. */
public record UndoUncategorizedResponse(int restored, int skipped, boolean ruleDeleted, boolean ruleRestored) {
}
