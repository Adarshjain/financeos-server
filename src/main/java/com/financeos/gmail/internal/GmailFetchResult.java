package com.financeos.gmail.internal;

import java.util.List;

public record GmailFetchResult(
        List<GmailMessage> messages,
        GmailSyncState nextState
) {}

