package com.financeos.domain.inbox.collect;

import com.financeos.api.inbox.dto.InboxItemResponse;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * One producer's view of what the user should see in the inbox right now. Read-only: a collector
 * never sends, never advances a notification marker, never recomputes an engine. Rows come back
 * in any order with no snooze/dismiss state applied; {@code InboxService} does the rest.
 */
public interface InboxCollector {

    List<InboxItemResponse> collect(UUID userId, LocalDate today);
}
