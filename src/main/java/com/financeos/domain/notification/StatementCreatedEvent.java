package com.financeos.domain.notification;

import java.util.UUID;

/** Published inside the statement-persisting transaction; consumed after it commits. */
public record StatementCreatedEvent(UUID statementId, UUID userId) {
}
