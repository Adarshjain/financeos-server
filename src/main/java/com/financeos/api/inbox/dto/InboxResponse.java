package com.financeos.api.inbox.dto;

import java.time.Instant;
import java.util.List;

/** The whole inbox, already ordered (act now, needs a look, info; most urgent first within a section). */
public record InboxResponse(List<InboxItemResponse> items, InboxSummaryResponse summary, Instant generatedAt) {
}
