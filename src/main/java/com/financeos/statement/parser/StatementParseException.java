package com.financeos.statement.parser;

public class StatementParseException extends RuntimeException {

    /** Why a file could not be parsed; callers branch on this instead of matching the message text. */
    public enum Kind {
        /** Password needed or wrong. */
        LOCKED,
        UNSUPPORTED_TYPE,
        EMPTY_FILE,
        TOO_MANY_PAGES,
        NO_TEXT_LAYER,
        /** The PDF/Excel library failed to read the file. */
        UNREADABLE,
        /** No date-anchored rows: no transaction table found. */
        NO_TABLE,
        /** A date format could not be inferred, or the rows do not conform to it. */
        DATE_FORMAT,
        OTHER
    }

    private final Kind kind;

    public StatementParseException(String message) {
        this(Kind.OTHER, message);
    }

    public StatementParseException(String message, Throwable cause) {
        this(Kind.OTHER, message, cause);
    }

    public StatementParseException(Kind kind, String message) {
        super(message);
        this.kind = kind == null ? Kind.OTHER : kind;
    }

    public StatementParseException(Kind kind, String message, Throwable cause) {
        super(message, cause);
        this.kind = kind == null ? Kind.OTHER : kind;
    }

    public Kind kind() {
        return kind;
    }

    /**
     * True when the file is locked and the password given (if any) did not open it. An exception built without a
     * kind (the old constructors) is still recognised by its wording.
     */
    public boolean isPasswordProblem() {
        return kind == Kind.LOCKED || (kind == Kind.OTHER && isPasswordMessage(getMessage()));
    }

    /** The shared wording of the PDF and Excel "password is missing or wrong" errors. */
    public static boolean isPasswordMessage(String message) {
        return message != null && message.contains("password-protected");
    }
}
