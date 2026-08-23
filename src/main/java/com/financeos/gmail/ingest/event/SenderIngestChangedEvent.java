package com.financeos.gmail.ingest.event;

import java.util.UUID;

public record SenderIngestChangedEvent(
    UUID userId
) {}
