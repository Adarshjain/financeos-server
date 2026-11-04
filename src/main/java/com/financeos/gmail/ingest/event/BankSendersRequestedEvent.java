package com.financeos.gmail.ingest.event;

import java.time.LocalDate;
import java.util.UUID;

/** An account that imports from Gmail was created or moved to a bank: add that bank's sender domains after commit. */
public record BankSendersRequestedEvent(UUID userId, String bankId, LocalDate fromDate) {}
