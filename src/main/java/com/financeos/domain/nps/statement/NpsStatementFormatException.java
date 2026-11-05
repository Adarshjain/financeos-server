package com.financeos.domain.nps.statement;

/** The file is not a readable NPS transaction statement. */
public class NpsStatementFormatException extends RuntimeException {
    public NpsStatementFormatException(String message) {
        super(message);
    }

    public NpsStatementFormatException(String message, Throwable cause) {
        super(message, cause);
    }
}
