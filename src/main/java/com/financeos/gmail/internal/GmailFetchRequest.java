package com.financeos.gmail.internal;

import java.time.Instant;

public record GmailFetchRequest(
        FetchMode mode,
        Instant fromTime,
        Integer maxMessages,
        String q
) {
    public GmailFetchRequest {
        if (maxMessages != null && maxMessages <= 0) {
            throw new IllegalArgumentException("maxMessages must be positive");
        }
    }

    public GmailFetchRequest(FetchMode mode, Instant fromTime, Integer maxMessages) {
        this(mode, fromTime, maxMessages, null);
    }
}
