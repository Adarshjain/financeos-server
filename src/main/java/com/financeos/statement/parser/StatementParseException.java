package com.financeos.statement.parser;

public class StatementParseException extends RuntimeException {
    public StatementParseException(String message) {
        super(message);
    }

    public StatementParseException(String message, Throwable cause) {
        super(message, cause);
    }
}
