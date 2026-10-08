package com.financeos.api.inbox.dto;

/** Row counts per section; {@code badge} is what the navigation shows (act-now plus needs-look rows). */
public record InboxSummaryResponse(int actNow, int needsLook, int info, int badge) {
    public static final InboxSummaryResponse EMPTY = new InboxSummaryResponse(0, 0, 0, 0);
}
