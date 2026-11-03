package com.financeos.gmail.ingest.event;

import java.util.UUID;

/** Published inside a transaction that added accounts or cards from suggestions: once it commits, parked mail is settled. */
public record ParkedMailSettleRequestedEvent(UUID userId) {}
