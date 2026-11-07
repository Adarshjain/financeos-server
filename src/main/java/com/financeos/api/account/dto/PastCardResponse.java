package com.financeos.api.account.dto;

import java.util.List;
import java.util.UUID;

/** The old card an identifier became; {@code reactivatedCount} parked mails were queued for reprocessing. */
public record PastCardResponse(UUID cardId, int reactivatedCount, List<UUID> jobIds) {}
