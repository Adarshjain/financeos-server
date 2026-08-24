package com.financeos.chat.db;

public class ChatSqlFailedException extends RuntimeException {

    public ChatSqlFailedException(String genericMessage) {
        super(genericMessage);
    }

    public ChatSqlFailedException(String genericMessage, Throwable cause) {
        super(genericMessage, cause);
    }
}
