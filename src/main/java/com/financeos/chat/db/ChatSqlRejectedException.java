package com.financeos.chat.db;

public class ChatSqlRejectedException extends RuntimeException {

    private final String reasonCode;

    public ChatSqlRejectedException(String reasonCode, String message) {
        super(message);
        this.reasonCode = reasonCode;
    }

    public String getReasonCode() {
        return reasonCode;
    }
}
