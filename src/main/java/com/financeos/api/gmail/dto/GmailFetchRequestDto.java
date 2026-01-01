package com.financeos.api.gmail.dto;

import jakarta.validation.constraints.Min;

import java.time.Instant;

public record GmailFetchRequestDto(
        String mode,  // "MANUAL" or "PERIODIC"
        Instant fromTime,
        @Min(1) Integer maxMessages
) {}

