package com.financeos.gmail.accountdetect.job;

import java.util.UUID;

/** ACCOUNT_DETECT job payload: the Gmail connection to read (the user's primary, the one Gmail sync reads). */
public record AccountDetectPayload(UUID connectionId) {}
