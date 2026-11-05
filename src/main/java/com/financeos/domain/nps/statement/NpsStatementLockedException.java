package com.financeos.domain.nps.statement;

/** None of the candidate passwords opened the statement PDF. */
public class NpsStatementLockedException extends RuntimeException {
    public NpsStatementLockedException(String message) {
        super(message);
    }
}
