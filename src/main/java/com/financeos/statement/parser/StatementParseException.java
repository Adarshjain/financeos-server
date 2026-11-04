package com.financeos.statement.parser;

public class StatementParseException extends RuntimeException {
    public StatementParseException(String message) {
        super(message);
    }

    public StatementParseException(String message, Throwable cause) {
        super(message, cause);
    }

    /** True when the file is locked and the password given (if any) did not open it. */
    public boolean isPasswordProblem() {
        return isPasswordMessage(getMessage());
    }

    /** The shared wording of the PDF and Excel "password is missing or wrong" errors. */
    public static boolean isPasswordMessage(String message) {
        return message != null && message.contains("password-protected");
    }
}
