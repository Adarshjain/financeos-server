package com.financeos.domain.inbox.collect;

import com.financeos.api.inbox.dto.InboxItemResponse;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/** A collector that reads part of what it needs from the build's {@link InboxContext} instead of loading it itself. */
public interface ContextAwareInboxCollector extends InboxCollector {

    List<InboxItemResponse> collect(UUID userId, LocalDate today, InboxContext context);
}
