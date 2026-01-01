package com.financeos.gmail.internal;

import java.time.Instant;

public record GmailSyncState(
        String historyId,
        Instant lastSyncedAt
) {}

