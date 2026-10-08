package com.financeos.api.inbox.dto;

import jakarta.validation.constraints.NotNull;
import java.time.LocalDate;

/** Hide the row until this business date, when it comes back (must be a future date). */
public record InboxSnoozeRequest(@NotNull LocalDate until) {
}
