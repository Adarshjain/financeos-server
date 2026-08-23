package com.financeos.gmail.ingest.event;

import java.time.LocalDate;
import java.util.UUID;

public record AccountIngestChangedEvent(
    UUID userId,
    String last4,
    LocalDate ingestFromDate
) {}
