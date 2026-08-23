package com.financeos.api.gmail.dto;

import java.time.LocalDate;

public record CleanupPreviewResponse(
    long count,
    LocalDate before
) {}
