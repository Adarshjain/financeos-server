package com.financeos.gmail.internal;

public class MessageGoneException extends RuntimeException {
    public MessageGoneException(String messageId) {
        super("Gmail message gone (404): " + messageId);
    }
}
