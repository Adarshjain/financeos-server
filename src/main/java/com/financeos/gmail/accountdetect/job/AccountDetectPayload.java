package com.financeos.gmail.accountdetect.job;

import java.util.UUID;

/**
 * ACCOUNT_DETECT job payload: the Gmail connection to read (the user's primary, the one Gmail sync reads).
 * {@code unlockRetry}: only try again the statement PDFs that are still locked (the user's name, birth date or a saved
 * statement password changed); an older payload without it reads as false.
 */
public record AccountDetectPayload(UUID connectionId, boolean unlockRetry) {

    public AccountDetectPayload(UUID connectionId) {
        this(connectionId, false);
    }
}
