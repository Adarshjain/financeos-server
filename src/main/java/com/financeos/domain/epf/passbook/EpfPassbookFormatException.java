package com.financeos.domain.epf.passbook;

public class EpfPassbookFormatException extends RuntimeException {

    public enum Reason { NOT_A_PASSBOOK, PASSWORD_PROTECTED, UNREADABLE }

    private final Reason reason;

    public EpfPassbookFormatException(Reason reason, String message) {
        super(message);
        this.reason = reason;
    }

    public Reason getReason() {
        return reason;
    }
}
